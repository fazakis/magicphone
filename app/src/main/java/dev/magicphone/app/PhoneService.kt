// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.Path
import android.os.*
import android.view.*
import android.view.accessibility.*
import android.widget.*
import dev.magicphone.core.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

class PhoneService : AccessibilityService() {
    // Counts/flags only, for the opt-in debug harness. No screen text or package names.
    var inspectionDiagnostics: String = ""
        private set
    var shortcutClicks: Int = 0
        private set
    private var revision = 0L
    private var lastSignature = ""
    private var lastScreen = Screen()
    private var refs = mutableMapOf<String, AccessibilityNodeInfo>()
    private var overlay: View? = null
    private var inputBubble: View? = null
    private var bubbleRequest: String? = null
    private var dismissedRequest: String? = null
    val quickPrompt by lazy { QuickPrompt(this) }
    private var bubbleSpeech: TextView? = null
    private var speechObserver: Job? = null
    private var lastShot = 0L
    private var screenReceiver: BroadcastReceiver? = null
    private val shortcut = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            if (BuildConfig.DEBUG) shortcutClicks++
            quickPrompt.show()
        }
    }
    private val manager
        get() = getSystemService(WindowManager::class.java)

    override fun onServiceConnected() {
        super.onServiceConnected()
        runtime.phone = this
        runtime.connected.value = true
        speechObserver = runtime.scope.launch { runtime.speech.active.collect { updateInputBubble() } }
        accessibilityButtonController.registerAccessibilityButtonCallback(shortcut, Handler(Looper.getMainLooper()))
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) {
                        hideInputBubble()
                        quickPrompt.cancel()
                        runtime.speech.stop()
                        runtime.localApproval(false)
                        runtime.agent.pause()
                    } else updateInputBubble()
                }
            }
        screenReceiver = receiver
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                    addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
        else registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        })
        updateControls()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // State binding is derived from a fresh filtered tree, not delayed event order.
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            runtime.agent.pause()
            quickPrompt.cancel()
            runtime.speech.stop()
            hideInputBubble()
        } else if (event?.eventType in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED)) updateInputBubble()
    }

    override fun onInterrupt() {
        runtime.localApproval(false)
        runtime.agent.pause()
    }

    override fun onDestroy() {
        accessibilityButtonController.unregisterAccessibilityButtonCallback(shortcut)
        quickPrompt.cancel()
        speechObserver?.cancel()
        runtime.speech.shutdown()
        runtime.stop()
        runtime.phone = null
        runtime.connected.value = false
        screenReceiver?.let { unregisterReceiver(it) }
        hideApproval()
        hideInputBubble()
        TaskNotifications.cancel(this)
        recycleRefs()
        super.onDestroy()
    }

    private fun recycleRefs() {
        refs.values.forEach { it.recycle() }
        refs.clear()
    }

    fun foregroundPackage(): String {
        val root = rootInActiveWindow ?: return ""
        return try { root.packageName?.toString().orEmpty() } finally { root.recycle() }
    }

    private suspend fun awaitSettled(app: String, timeoutMs: Long) {
        val stability = ScreenStability(80)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            currentCoroutineContext().ensureActive()
            val current = try { inspect(app) } catch (_: SafeFailure) { null }
            if (stability.ready(current, SystemClock.elapsedRealtime())) return
            delay(40)
        } while (SystemClock.elapsedRealtime() < deadline)
        // Readiness is best-effort, not a dispatch result. The subsequent observation
        // still checks full policy; an executed action is never replayed on timeout.
    }

    fun inspect(requested: String): Screen {
        val rule = Policy(packageName).appRule(requested, runtime.settings.value.policy)
        if (!rule.observe || rule.deny) throw SafeFailure("app_not_allowed")
        // Cached nodes can lag behind a visible change until Accessibility events arrive.
        // Revalidation must query live nodes, including on API 30-32 without a cache switch.
        val refreshNodes = Build.VERSION.SDK_INT < 33 || !setCacheEnabled(false)
        val locked =
            getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
                !getSystemService(PowerManager::class.java).isInteractive
        val windows = windows
        val applications = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val roots = applications.mapNotNull { w -> w.root?.let { w to it } }
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        try {
            val selected = roots.firstOrNull { (w, n) ->
                n.packageName?.toString() == requested && (w.isActive || w.isFocused)
            }
            val active = roots.firstOrNull { it.first.isActive }
            val app =
                selected?.second?.packageName?.toString()
                    ?: active?.second?.packageName?.toString().orEmpty()
            val root = selected?.second
            val display = resources.displayMetrics
            val geometry = manager.currentWindowMetrics.bounds
            val width = geometry.width().takeIf { it > 0 } ?: display.widthPixels
            val height = geometry.height().takeIf { it > 0 } ?: display.heightPixels
            // Never traverse another application's root. Unknown overlays/multi-window
            // remain blocked; a visible keyboard permits filtered app text, never a capture.
            val ownedIds = listOfNotNull(overlay, inputBubble, quickPrompt.view).filter { it.isAttachedToWindow }.mapNotNull { view ->
                val info = view.createAccessibilityNodeInfo() ?: return@mapNotNull null
                try { info.windowId.takeIf { it >= 0 } } finally { info.recycle() }
            }.toSet()
            val mixedApplications =
                roots.any { (w, n) ->
                    n.packageName?.toString() != requested &&
                        w.layer >= (selected?.first?.layer ?: 0)
                }
            val activeSystem = windows.any { w ->
                        w.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                            w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                            w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                            w.isActive
                    }
            val foreignOverlay = windows.any { w ->
                        w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                            w.id !in ownedIds
                    }
            val mixed = mixedApplications || activeSystem || foreignOverlay
            if (BuildConfig.DEBUG) inspectionDiagnostics =
                "root=${root != null},locked=$locked,mixedApplications=$mixedApplications," +
                    "activeSystem=$activeSystem,foreignOverlay=$foreignOverlay," +
                    "ownedWindows=${listOfNotNull(overlay, inputBubble, quickPrompt.view).count { it.isAttachedToWindow }}," +
                    "overlayWindows=${windows.count { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }}," +
                    "ownedIdMatches=${windows.count { it.id in ownedIds }}"
            if (root == null || locked || mixed) {
                recycleRefs()
                return Screen(
                    app = app,
                    width = width,
                    height = height,
                    locked = locked,
                    mixed = true,
                    focused = false,
                )
            }
            // A keyboard is normal app input, not an unknown foreground app. Only read
            // the permitted app's nodes outside it; never traverse the IME's own root.
            val keyboardRects = windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .mapNotNull { window ->
                    val bounds = android.graphics.Rect()
                    window.getBoundsInScreen(bounds)
                    if (bounds.isEmpty) null else Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                }
            val controlRects = listOfNotNull(overlay, inputBubble, quickPrompt.view)
                .filter { it.isAttachedToWindow }.map { view ->
                    val pos = IntArray(2)
                    view.getLocationOnScreen(pos)
                    Rect(pos[0], pos[1], pos[0] + view.width, pos[1] + view.height)
                }
            val rects = keyboardRects + controlRects
            val nodes = mutableListOf<Node>()
            var sensitive = false
            var partial = rects.isNotEmpty()
            var count = 0
            fun visit(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 30 || ++count > 500) {
                    partial = true
                    return
                }
                if (refreshNodes && !n.refresh()) {
                    if (BuildConfig.DEBUG) inspectionDiagnostics = "node_refresh_failed"
                    partial = true
                    return // Keep other freshly read branches; never retain this stale node.
                }
                if (n.childCount > 100) partial = true
                if (!n.isVisibleToUser || n.packageName?.toString() != requested) return
                val password =
                    InputFields.password(n.isPassword, n.inputType)
                if (password) sensitive = true
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                val label =
                    if (password) "[manual field]"
                    else
                        Sanitizer.text(
                                (n.text ?: n.contentDescription ?: n.hintText ?: "").toString()
                            )
                            .take(500)
                val covered = rects.any { it.left < r.right && it.right > r.left &&
                    it.top < r.bottom && it.bottom > r.top }
                if (!covered && (label.isNotBlank() || n.isClickable || n.isEditable || n.isScrollable)) {
                    nodes +=
                        Node(
                            "",
                            label,
                            Rect(r.left, r.top, r.right, r.bottom),
                            n.isClickable,
                            n.isEditable,
                            n.isScrollable,
                            password,
                            n.isFocused,
                            n.isEnabled,
                        )
                    candidates += AccessibilityNodeInfo.obtain(n)
                }
                for (i in 0 until n.childCount.coerceAtMost(100)) n.getChild(i)?.let { child ->
                    try {
                        visit(child, depth + 1)
                    } finally {
                        child.recycle()
                    }
                }
            }
            visit(root, 0)
            val rotation =
                getSystemService(android.hardware.display.DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)
                    ?.rotation ?: 0
            if (selected.first.displayId != Display.DEFAULT_DISPLAY)
                throw SafeFailure("screen_uncertain")
            val capture = android.graphics.Rect()
            root.getBoundsInScreen(capture)
            val systemInsets =
                manager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars()
                )
            if (
                !capture.intersect(
                    systemInsets.left,
                    systemInsets.top,
                    width - systemInsets.right,
                    height - systemInsets.bottom,
                )
            )
                throw SafeFailure("capture_uncertain")
            val signature =
                digest(
                    "$app|${selected.first.id}|$width|$height|$rotation|${selected.first.isFocused}|$rects|$nodes"
                )
            if (signature != lastSignature) {
                revision++
                lastSignature = signature
            }
            val snapshot = "$revision-${signature.take(20)}"
            recycleRefs()
            val tagged = nodes.mapIndexed { index, n ->
                val ref = "$snapshot:$index"
                refs[ref] = candidates[index]
                n.copy(ref = ref)
            }
            candidates.clear() // The reference map now owns these node copies.
            return Screen(
                    snapshot,
                    app,
                    selected.first.id,
                    revision,
                    width,
                    height,
                    rotation,
                    selected.first.isActive || selected.first.isFocused,
                    locked,
                    false,
                    sensitive,
                    tagged,
                    rects,
                    Rect(capture.left, capture.top, capture.right, capture.bottom),
                    partial = partial,
                )
                .also { lastScreen = it }
        } finally {
            candidates.forEach { it.recycle() }
            roots.forEach { it.second.recycle() }
        }
    }

    suspend fun perform(action: Action, screen: Screen): ToolResult {
        if (action.op == Op.OBSERVE)
            return ToolResult(
                "observed",
                JsonCodec.encodeToString(
                    Screen.serializer(),
                    screen.copy(protectedRects = emptyList()),
                ),
            )
        if (action.op == Op.OPEN) {
            val intent =
                packageManager.getLaunchIntentForPackage(action.app)
                    ?: throw SafeFailure("app_missing")
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitSettled(action.app, 1200)
            return ToolResult("dispatched", "Application launch requested; observe to confirm.")
        }
        if (action.op == Op.WAIT_FOR) {
            val end = SystemClock.elapsedRealtime() + action.millis
            do {
                val latest = inspect(action.app)
                if (
                    !latest.mixed &&
                        !latest.sensitive &&
                        !latest.locked &&
                        latest.nodes.any { it.label.contains(action.text) }
                )
                    return ToolResult(
                        "condition_observed",
                        JsonCodec.encodeToString(
                            Screen.serializer(),
                            latest.copy(protectedRects = emptyList()),
                        ),
                    )
                delay(250)
            } while (SystemClock.elapsedRealtime() < end)
            return ToolResult("failed", "Condition not observed before timeout.")
        }
        val latest = inspect(action.app)
        if (latest.binding != screen.binding) throw SafeFailure("stale_target")
        val checked =
            Policy(packageName)
                .decide(action, latest, runtime.settings.value.policy, System.currentTimeMillis())
        if (checked is Decision.Deny) throw SafeFailure(checked.reason)
        val node = refs[action.node]
        val accepted =
            when (action.op) {
                Op.SCREENSHOT -> return screenshot(action.app, screen)
                Op.TAP ->
                    if (node != null) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    else gesture(action.x, action.y, action.x, action.y, 80)
                Op.LONG_PRESS ->
                    if (node != null) node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    else gesture(action.x, action.y, action.x, action.y, 650)
                Op.TEXT -> {
                    if (node?.isPassword != false) throw SafeFailure("manual_secret")
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                action.text,
                            )
                        },
                    )
                }
                Op.SCROLL ->
                    node?.performAction(
                        if (action.text == "forward") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ) ?: false
                Op.SWIPE ->
                    gesture(action.x, action.y, action.x2, action.y2, action.millis.toLong())
                Op.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                Op.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                Op.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                Op.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                else -> throw SafeFailure("unknown_tool")
            }
        if (accepted) awaitSettled(action.app, 350)
        return ToolResult(
            if (accepted) "dispatched" else "failed",
            if (accepted) "Android accepted the action; observe to verify."
            else "Android rejected the action.",
        )
    }

    private suspend fun gesture(x: Int, y: Int, x2: Int, y2: Int, duration: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val path =
                Path().apply {
                    moveTo(x.toFloat(), y.toFloat())
                    lineTo(x2.toFloat(), y2.toFloat())
                }
            val g =
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                    .build()
            val sent =
                dispatchGesture(
                    g,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    null,
                )
            if (!sent && continuation.isActive) continuation.resume(false)
        }

    private suspend fun screenshot(pkg: String, before: Screen): ToolResult {
        if (SystemClock.elapsedRealtime() - lastShot < 1200)
            throw SafeFailure("screenshot_throttled")
        if (before.mixed || before.sensitive || before.partial || before.protectedRects.isNotEmpty())
            throw SafeFailure("capture_uncertain")
        lastShot = SystemClock.elapsedRealtime()
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            if (!continuation.isActive) return
                            if (inspect(pkg).binding != before.binding)
                                throw SafeFailure("stale_target")
                            val hardware =
                                Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    ?: throw SafeFailure("screenshot_failed")
                            val bitmap =
                                try {
                                    hardware.copy(Bitmap.Config.ARGB_8888, false)
                                } finally {
                                    hardware.recycle()
                                }
                            val rect = before.captureBounds
                            val left = rect.left.coerceIn(0, bitmap.width - 1)
                            val top = rect.top.coerceIn(0, bitmap.height - 1)
                            val width =
                                (rect.right.coerceAtMost(bitmap.width) - left).coerceAtLeast(1)
                            val height =
                                (rect.bottom.coerceAtMost(bitmap.height) - top).coerceAtLeast(1)
                            val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
                            val output = ByteArrayOutputStream()
                            try {
                                cropped.compress(Bitmap.CompressFormat.JPEG, 70, output)
                            } finally {
                                if (cropped !== bitmap) cropped.recycle()
                                bitmap.recycle()
                            }
                            if (output.size() > 3 * 1024 * 1024)
                                throw SafeFailure("image_too_large")
                            continuation.resume(
                                ToolResult(
                                    "captured",
                                    image =
                                        "data:image/jpeg;base64," +
                                            Base64.getEncoder()
                                                .encodeToString(output.toByteArray()),
                                )
                            )
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        } finally {
                            buffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive)
                            continuation.resumeWithException(
                                SafeFailure(
                                    if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
                                        "secure_window"
                                    else "screenshot_failed"
                                )
                            )
                    }
                },
            )
        }
    }

    private fun panel(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0xff173d38.toInt())
            filterTouchesWhenObscured = true
        }

    private fun text(value: String) =
        TextView(this).apply {
            text = value
            setTextColor(0xfff3f8ee.toInt())
            textSize = 16f
        }

    private fun button(label: Int, click: () -> Unit) =
        Button(this).apply {
            isAllCaps = false
            setText(label)
            filterTouchesWhenObscured = true
            setOnClickListener { click() }
        }

    private fun show(view: View, bottom: Boolean = true) {
        val params =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT,
                )
                .apply { gravity = if (bottom) Gravity.BOTTOM else Gravity.TOP }
        manager.addView(view, params)
    }

    fun showApproval(approval: Approval) {
        hideInputBubble()
        hideApproval()
        overlay =
            panel()
                .apply {
                    addView(text(getString(R.string.approval_title)))
                    val destination =
                        runtime.settings.value.servers
                            .find { it.id == approval.action.server }
                            ?.endpoint ?: approval.action.app
                    val details =
                        ScrollView(this@PhoneService).apply {
                            addView(
                                text(
                                    destination +
                                        "\n" +
                                        JsonCodec.encodeToString(
                                            Action.serializer(),
                                            approval.action,
                                        )
                                )
                            )
                        }
                    addView(
                        details,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (resources.displayMetrics.heightPixels * 0.30).toInt(),
                        ),
                    )
                    addView(button(R.string.approve_once) { runtime.localApproval(true) })
                    addView(button(R.string.reject) { runtime.localApproval(false) })
                    addView(button(R.string.stop) { runtime.stop() })
                }
                .also { show(it) }
    }

    fun hideApproval() {
        overlay?.let { runCatching { manager.removeView(it) } }
        overlay = null
    }

    fun updateControls() {
        val state = runtime.agent.state.value
        val request = runtime.inputRequest.value.takeIf {
            state in setOf(RunState.WAITING_USER, RunState.PAUSED) && runtime.agent.question.value.isNotBlank()
        }
        TaskNotifications.update(this, state, request)
        updateInputBubble()
    }

    fun hideInputBubble() {
        inputBubble?.let { runCatching { manager.removeView(it) } }
        inputBubble = null
        bubbleSpeech = null
        bubbleRequest = null
    }

    private fun currentBubble(): InputRequest? {
        val state = runtime.agent.state.value
        if (state in setOf(RunState.WAITING_USER, RunState.PAUSED) && runtime.agent.question.value.isNotBlank())
            return runtime.inputRequest.value
        return runtime.resultRequest.value?.takeIf {
            runtime.settings.value.showTaskResultBubbles && it.conversation == runtime.current.value &&
                ((it.kind == BubbleKind.COMPLETED && state == RunState.COMPLETED) ||
                    (it.kind == BubbleKind.FAILED && state == RunState.FAILED))
        }
    }

    private fun updateInputBubble() {
        val request = currentBubble()
        val interactive = getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (request == null || !interactive || overlay != null || quickPrompt.view != null ||
            runtime.visibleChat.value == request.conversation || request.id == dismissedRequest) {
            hideInputBubble()
            return
        }
        if (bubbleRequest == request.id && inputBubble != null) {
            bubbleSpeech?.text = getString(if (runtime.speech.active.value == request.id) R.string.stop_reading else R.string.read_aloud)
            return
        }
        hideInputBubble()
        val titleResource = when (request.kind) {
            BubbleKind.QUESTION -> R.string.input_bubble_title
            BubbleKind.COMPLETED -> R.string.task_result_completed_title
            BubbleKind.FAILED -> R.string.task_result_failed_title
        }
        val openResource = if (request.kind == BubbleKind.QUESTION) R.string.input_bubble_reply else R.string.task_result_open
        fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
        fun reply() {
            // Ignore a stale local surface. Model/script operations cannot invoke this control.
            if (currentBubble()?.id != request.id) return
            dismissedRequest = request.id
            runtime.speech.stop()
            hideInputBubble()
            startActivity(MainActivity.chatIntent(this, request.conversation))
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(12), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xff173d38.toInt())
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0xffb8f3d1.toInt())
            }
            elevation = dp(8).toFloat()
            filterTouchesWhenObscured = true
            isClickable = true
            setOnClickListener { reply() }
            addView(LinearLayout(this@PhoneService).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(getString(titleResource)).apply {
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@PhoneService).apply {
                    text = "×"
                    textSize = 24f
                    setTextColor(0xfff3f8ee.toInt())
                    gravity = Gravity.CENTER
                    contentDescription = getString(R.string.dismiss)
                    filterTouchesWhenObscured = true
                    setOnClickListener { dismissedRequest = request.id; runtime.speech.stop(); hideInputBubble() }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
            addView(text(spokenText(request.message).replace(Regex("\\n\\s*\\n"), "\n")).apply {
                maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 0, dp(6), dp(10))
            })
            addView(text(getString(R.string.read_aloud)).apply {
                bubbleSpeech = this
                minHeight = dp(40); gravity = Gravity.CENTER_VERTICAL
                setTextColor(0xffb8f3d1.toInt()); filterTouchesWhenObscured = true
                setOnClickListener { if (currentBubble()?.id == request.id) runtime.speech.toggle(request.id, request.message) }
            })
            addView(text(getString(openResource)).apply {
                textSize = 14f
                setTextColor(0xffb8f3d1.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener { reply() }
                filterTouchesWhenObscured = true
                minHeight = dp(40)
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        val bounds = manager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            minOf(dp(340), (bounds.width() - dp(32)).coerceAtLeast(dp(160))),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(16)
            y = minOf(dp(96), bounds.height() / 5)
            title = getString(titleResource)
        }
        // Set ownership before attaching: window events must not recursively create another bubble.
        bubbleRequest = request.id
        inputBubble = bubble
        try { manager.addView(bubble, params) }
        catch (_: WindowManager.BadTokenException) { hideInputBubble() }
        catch (_: IllegalStateException) { hideInputBubble() }
        catch (_: SecurityException) { hideInputBubble() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        quickPrompt.cancel()
        hideInputBubble()
        updateInputBubble()
    }

}

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "stop" -> context.runtime.stop()
            "pause" -> context.runtime.agent.pause()
            "resume" -> {
                val runtime = context.runtime
                if (runtime.agent.state.value != RunState.PAUSED) return
                // A local notification button, never a model/script tool. Close this control
                // surface before allowing the agent to observe the underlying app again.
                val closing = Build.VERSION.SDK_INT >= 31 && runtime.phone?.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) == true
                runtime.scope.launch {
                    if (closing) delay(350)
                    if (runtime.phone != null && runtime.agent.state.value == RunState.PAUSED)
                        runtime.agent.resume()
                }
            }
        }
    }
}

class MagicTile : android.service.quicksettings.TileService() {
    // The PendingIntent overload does not exist on Android 11–13.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = MainActivity.chatIntent(this)
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
