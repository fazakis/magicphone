// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.app.KeyguardManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.magicphone.core.*
import kotlinx.coroutines.*

/** One stable, non-focusable local Stop control; never part of the initial capture. */
class WorkingBubble(private val service: PhoneService) {
    var view: View? = null
        private set
    private var label: TextView? = null
    private var pending: Job? = null
    private var lastUpdate = 0L
    private var desired = ""
    private var conversation: String? = null
    private val r get() = service.runtime
    private val manager get() = service.getSystemService(WindowManager::class.java)
    private fun dp(value: Int) = (service.resources.displayMetrics.density * value).toInt()
    private fun eligible(): Boolean {
        val chat = r.popupConversation.value ?: return false
        return chat == r.current.value && r.visibleChat.value != chat && !service.hasPromptOrReply &&
            r.agent.screenCapture.value in setOf(ScreenCaptureState.CAPTURED, ScreenCaptureState.UNAVAILABLE) &&
            r.agent.state.value in setOf(RunState.PLANNING, RunState.ACTING, RunState.PAUSED, RunState.WAITING_APPROVAL) &&
            service.getSystemService(PowerManager::class.java).isInteractive &&
            !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }
    fun update() {
        if (!eligible()) { hide(); return }
        desired = service.getString(when {
            r.agent.state.value == RunState.PAUSED -> R.string.state_paused
            r.agent.state.value == RunState.WAITING_APPROVAL -> R.string.state_waiting_approval
            r.agent.activeTool.value in setOf(Op.OBSERVE, Op.SCREENSHOT) -> R.string.working_reading
            r.agent.activeTool.value == Op.OPEN -> R.string.working_opening
            r.agent.activeTool.value != null || r.agent.state.value == RunState.ACTING -> R.string.working_acting
            r.agent.stream.value.isNotBlank() -> R.string.working_answering
            else -> R.string.working_thinking
        })
        if (view == null) { show(); return }
        if (label?.text == desired || pending?.isActive == true) return
        pending = r.scope.launch {
            delay((750 - (SystemClock.elapsedRealtime() - lastUpdate)).coerceAtLeast(0))
            pending = null
            if (!eligible()) { hide(); return@launch }
            label?.apply {
                // Stable bounds and limited text updates avoid flashing/recreating the overlay.
                animate().cancel()
                text = desired
                alpha = .7f
                animate().alpha(1f).setDuration(180).start()
            }
            lastUpdate = SystemClock.elapsedRealtime()
        }
    }
    private fun show() {
        conversation = r.popupConversation.value
        val owner = conversation
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(6), dp(10)); minimumHeight = dp(64)
            background = GradientDrawable().apply {
                setColor(0xff173d38.toInt()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0xff91cdb1.toInt())
            }
            elevation = dp(7).toFloat(); filterTouchesWhenObscured = true
            addView(LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(service).apply {
                    text = service.getString(R.string.working_title); textSize = 14f
                    setTextColor(0xfff3f8ee.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(service).apply {
                    label = this; text = desired; textSize = 12f; setTextColor(0xffb8d9c9.toInt())
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    contentDescription = service.getString(R.string.working_progress)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(service).apply {
                text = service.getString(R.string.stop); textSize = 14f; gravity = Gravity.CENTER
                setTextColor(0xfff3f8ee.toInt()); contentDescription = service.getString(R.string.working_stop)
                isClickable = true; isFocusable = true; filterTouchesWhenObscured = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x338fffff),
                    GradientDrawable().apply { setColor(0xff2c514b.toInt()); cornerRadius = dp(20).toFloat() }, null)
                setOnClickListener {
                    if (owner == r.popupConversation.value && owner == r.current.value) r.stop()
                }
            }, LinearLayout.LayoutParams(dp(56), dp(48)).apply { marginStart = dp(8) })
        }
        val bounds = manager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(minOf(dp(260), bounds.width() - dp(24)), -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END; x = dp(12); y = minOf(dp(96), bounds.height() / 5)
                title = service.getString(R.string.working_title)
            }
        view = row
        lastUpdate = SystemClock.elapsedRealtime()
        try { manager.addView(row, params) }
        catch (_: RuntimeException) { hide() }
    }
    fun hide() {
        pending?.cancel(); pending = null
        label?.animate()?.cancel()
        view?.let { runCatching { manager.removeView(it) } }
        view = null; label = null; conversation = null
    }
}
