// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import dev.magicphone.core.*
import kotlinx.coroutines.*

/** A local, explicitly enabled listening session. No gestures or external-app mutations. */
class GuidedExplanation(private val service: PhoneService) {
    private val runtime get() = service.runtime
    private val manager get() = service.getSystemService(WindowManager::class.java)
    var armed = false; private set
    var revision = 0L; private set
    var plan: Explanation? = null; private set
    var section = 0; private set
    var paused = false; private set
    var refreshing = false; private set
    var drawing: View? = null; private set
    var controls: View? = null; private set
    val visibleViews get() = listOfNotNull(drawing, controls)
    private var source: Screen? = null
    private var windowLayout = ""
    internal val sourceSnapshot get() = source?.id
    internal val highlightBounds get() = source?.let { screen ->
        plan?.sections?.getOrNull(section)?.regions?.map { it.onScreen(screen.captureBounds) }
    }.orEmpty()
    private var target = ""
    private var conversation: String? = null
    private var refresh: ((String) -> Unit)? = null
    private var dirty = false
    private var speaking = false
    private var suspended = 0
    private var epoch = 0L
    private var debounce: Job? = null
    private var monitor: Job? = null
    private var speechObserver: Job? = null
    private var displaySize = android.graphics.Rect()
    private var controlPosition: Pair<Int, Int>? = null
    private fun dp(n: Int) = (n * service.resources.displayMetrics.density).toInt()
    private fun label(id: Int) = service.getString(id)

    fun arm(app: String, onRefresh: (String) -> Unit) {
        stop()
        armed = true; target = app; conversation = runtime.current.value; refresh = onRefresh
        displaySize = manager.maximumWindowMetrics.bounds
        monitor = runtime.scope.launch {
            while (isActive) {
                delay(350)
                if (runtime.current.value != conversation) { stop(); break }
                val foreground = service.foregroundPackage()
                if (foreground.isNotEmpty() && foreground != target && !service.quickPrompt.voiceActive) { stop(); break }
                val bounds = manager.maximumWindowMetrics.bounds
                if (bounds != displaySize) { displaySize = bounds; screenChanged() }
                checkWindowLayout()
            }
        }
        speechObserver = runtime.scope.launch {
            runtime.speech.active.collect { key ->
                if (key != null && !key.startsWith("explain:")) stop()
            }
        }
    }

    fun present(action: Action, screen: Screen): ToolResult {
        if (!armed || target != action.app || conversation != runtime.current.value)
            throw SafeFailure("explanation_not_enabled")
        if (screen.id != action.snapshot) throw SafeFailure("stale_target")
        val value = action.explanation ?: throw SafeFailure("invalid_action")
        value.validate()
        source = screen; windowLayout = service.explanationLayout(screen.window, screen.displayId)
        plan = value; section = 0; dirty = false; refreshing = false
        debounce?.cancel(); debounce = null
        playSection()
        return ToolResult("explaining", "Spoken explanation queued locally with synchronized highlights.")
    }

    /** Text-only fallback still fulfills the local user's request to hear the answer. */
    fun presentText(text: String) {
        if (!armed || (plan != null && !refreshing) || text.isBlank()) return
        plan = Explanation(text.chunked(1100).take(8).map { ExplanationSection(it) })
        source = null; section = 0; dirty = false; refreshing = false; playSection()
    }

    private fun playSection() {
        if (!armed) return
        val value = plan ?: return
        epoch++
        val generation = epoch
        runtime.speech.stop(); speaking = false
        if (section >= value.sections.size) { paused = false; redraw(); return }
        if (dirty) { scheduleRefresh(); return }
        paused = false
        redraw()
        runtime.speech.play("explain:$generation:$section", value.sections[section].text,
            started = {
                if (armed && generation == epoch) { speaking = true; redraw() }
            }, finished = {
                if (armed && generation == epoch) { section++; playSection() }
            }, failed = {
                if (armed && generation == epoch) { speaking = false; paused = true; redraw() }
            })
    }

    fun togglePause() {
        if (!armed) return
        if (paused || section >= (plan?.sections?.size ?: 0)) {
            paused = false
            if (runtime.agent.state.value == RunState.PAUSED) runtime.agent.resume()
            if (refreshing) { if (dirty) scheduleRefresh(); redraw() }
            else { if (section >= (plan?.sections?.size ?: 0)) section = 0; playSection() }
        } else {
            epoch++; paused = true; speaking = false; runtime.speech.stop()
            debounce?.cancel(); debounce = null
            if (refreshing) runtime.agent.pause()
            redraw()
        }
    }

    fun skip(delta: Int) {
        val count = plan?.sections?.size ?: return
        if (refreshing) return
        section = (section + delta).coerceIn(0, count)
        playSection()
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (!armed || event == null) return
        // Our own overlay insertion/removal also emits window events. Check stable metadata
        // in the monitor instead of invalidating in the middle of a presentation update.
        if (event.packageName?.toString() != target) return
        if (event.eventType in setOf(AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) screenChanged()
    }

    private fun checkWindowLayout() {
        val screen = source ?: return
        val current = service.explanationLayout(screen.window, screen.displayId)
        if (windowLayout != current) { windowLayout = current; screenChanged() }
    }

    fun screenChanged() {
        if (!armed) return
        revision++
        if (plan == null || section >= plan!!.sections.size) { hideDrawing(); return }
        dirty = true; speaking = false; epoch++; runtime.speech.stop(); hideDrawing()
        if (!paused) scheduleRefresh()
        redraw()
    }

    private fun scheduleRefresh() {
        refreshing = true
        debounce?.cancel()
        debounce = runtime.scope.launch {
            delay(500) // Debounce user scrolling/zooming, never a delay on ordinary app actions.
            if (!armed || paused || runtime.current.value != conversation || service.foregroundPackage() != target) return@launch
            dirty = false
            refresh?.invoke(plan?.sections?.drop(section)?.joinToString("\n\n") { it.text }.orEmpty())
        }
    }

    /** Let the normal reply bubble appear while retaining this locally authorized mode. */
    fun waitForInput() {
        if (!armed) return
        epoch++; speaking = false; paused = false; refreshing = false; dirty = false
        debounce?.cancel(); debounce = null
        if (runtime.speech.active.value?.startsWith("explain:") == true) runtime.speech.stop()
        plan = null; source = null; section = 0
        hideViews()
    }

    fun suspendForTool(): List<Int> {
        suspended++
        val ids = visibleViews.filter { it.isAttachedToWindow }.map { view ->
            val node = view.createAccessibilityNodeInfo()
            val id = node.windowId
            @Suppress("DEPRECATION") node.recycle()
            id
        }
        hideViews()
        return ids
    }
    fun resumeAfterTool() { suspended = (suspended - 1).coerceAtLeast(0); redraw() }

    fun stop() {
        val ownedSpeech = runtime.speech.active.value?.startsWith("explain:") == true
        epoch++; armed = false; speaking = false; paused = false; refreshing = false; dirty = false
        debounce?.cancel(); debounce = null; monitor?.cancel(); monitor = null
        speechObserver?.cancel(); speechObserver = null
        if (ownedSpeech) runtime.speech.stop()
        plan = null; source = null; refresh = null; section = 0; controlPosition = null
        hideViews()
    }

    private fun hideDrawing() { drawing?.let { runCatching { manager.removeView(it) } }; drawing = null }
    private fun hideViews() {
        hideDrawing()
        controls?.let { runCatching { manager.removeView(it) } }; controls = null
    }
    private fun redraw() {
        if (!armed || suspended > 0 || plan == null) { hideViews(); return }
        hideDrawing()
        if (speaking && !dirty && !refreshing && !paused && source != null && section < plan!!.sections.size) {
            val screen = source!!
            val regions = plan!!.sections[section].regions
            if (regions.isNotEmpty()) {
                val view = object : View(service) {
                    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
                    override fun onDraw(canvas: Canvas) {
                        val origin = IntArray(2); getLocationOnScreen(origin)
                        canvas.save(); canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
                        canvas.clipRect(screen.captureBounds.left, screen.captureBounds.top, screen.captureBounds.right, screen.captureBounds.bottom)
                        screen.protectedRects.forEach { canvas.clipOutRect(it.left, it.top, it.right, it.bottom) }
                        regions.forEachIndexed { index, region ->
                            val b = region.onScreen(screen.captureBounds)
                            val rect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                            fun outline() {
                                when (region.style) {
                                    "ellipse" -> canvas.drawOval(rect, paint)
                                    "underline" -> canvas.drawLine(rect.left, rect.bottom - dp(2), rect.right, rect.bottom - dp(2), paint)
                                    "pointer" -> {
                                        canvas.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), paint)
                                        val y = rect.centerY(); val x = rect.left + dp(16)
                                        canvas.drawLine(rect.left + dp(3), y, x, y, paint)
                                        canvas.drawLine(x - dp(6), y - dp(6), x, y, paint)
                                        canvas.drawLine(x - dp(6), y + dp(6), x, y, paint)
                                    }
                                    else -> canvas.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), paint)
                                }
                            }
                            if (region.style == "highlight") {
                                paint.style = Paint.Style.FILL; paint.color = 0x5536efb3
                                canvas.drawRoundRect(rect, dp(3).toFloat(), dp(3).toFloat(), paint)
                            } else {
                                // Dark outer stroke remains legible on both white pages and dark apps.
                                paint.style = Paint.Style.STROKE; paint.color = Color.rgb(19, 65, 57); paint.strokeWidth = dp(5).toFloat()
                                outline()
                                paint.color = Color.rgb(102, 239, 195); paint.strokeWidth = dp(2).toFloat()
                                outline()
                            }
                            if (regions.size > 1 && region.style !in setOf("highlight", "underline")) {
                                paint.style = Paint.Style.FILL; canvas.drawCircle(rect.left + dp(10), rect.top + dp(10), dp(9).toFloat(), paint)
                                paint.color = Color.rgb(19, 65, 57); paint.textSize = dp(12).toFloat(); paint.textAlign = Paint.Align.CENTER
                                canvas.drawText("${index + 1}", rect.left + dp(10), rect.top + dp(14), paint)
                            }
                        }
                        canvas.restore()
                    }
                }
                val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.START
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    setFitInsetsTypes(0); title = label(R.string.explain_highlights)
                }
                runCatching { manager.addView(view, params); drawing = view }
            }
        }
        showControls()
    }

    private fun showControls() {
        // Rebuild only at section/control transitions, never at speech-word frequency.
        controls?.let { runCatching { manager.removeView(it) } }; controls = null
        val value = plan ?: return
        val finished = section >= value.sections.size
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(0xff173d38.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0xff91cdb1.toInt()) }
            elevation = dp(6).toFloat(); filterTouchesWhenObscured = true
        }
        val bounds = manager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(minOf(dp(330), bounds.width() - dp(16)), -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (controlPosition?.first ?: dp(8)).coerceIn(0, (bounds.width() - width).coerceAtLeast(0))
                y = (controlPosition?.second ?: (bounds.height() - dp(220))).coerceIn(0, (bounds.height() - dp(220)).coerceAtLeast(0))
                title = label(R.string.explain_controls)
            }
        fun text(value: String) = TextView(service).apply { text = value; textSize = 14f; setTextColor(0xfff3f8ee.toInt()); filterTouchesWhenObscured = true }
        val heading = text(when {
            refreshing -> label(R.string.explain_refreshing)
            finished -> label(R.string.explain_finished)
            paused -> label(R.string.explain_paused)
            else -> service.getString(R.string.explain_section, section + 1, value.sections.size)
        }).apply { contentDescription = label(R.string.explain_move); setPadding(dp(4), dp(8), dp(4), dp(8)) }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        heading.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + event.rawX - downX).toInt().coerceIn(0, (bounds.width() - panel.width).coerceAtLeast(0))
                    params.y = (startY + event.rawY - downY).toInt().coerceIn(0, (bounds.height() - panel.height - dp(24)).coerceAtLeast(0))
                    controlPosition = params.x to params.y
                    if (panel.isAttachedToWindow) manager.updateViewLayout(panel, params)
                    true
                }
                MotionEvent.ACTION_UP -> { heading.performClick(); true }
                else -> false
            }
        }
        panel.addView(heading)
        if (!finished) panel.addView(text(value.sections[section].text).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 12f })
        val buttons = LinearLayout(service)
        fun button(symbol: String, description: Int, click: () -> Unit) {
            buttons.addView(text(symbol).apply {
                contentDescription = label(description); gravity = Gravity.CENTER; isClickable = true; isFocusable = true
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        button("‹", R.string.explain_previous) { skip(-1) }
        button(if (paused || finished) "▶" else "Ⅱ", if (paused || finished) R.string.explain_resume else R.string.pause) { togglePause() }
        button("›", R.string.explain_next) { skip(1) }
        button("■", R.string.stop) { runtime.stop() }
        button("↗", R.string.task_result_open) {
            val chat = conversation
            stop(); runtime.resultRequest.value = null
            service.startActivity(MainActivity.chatIntent(service, chat))
        }
        panel.addView(buttons)
        runCatching { manager.addView(panel, params); controls = panel }
    }
}
