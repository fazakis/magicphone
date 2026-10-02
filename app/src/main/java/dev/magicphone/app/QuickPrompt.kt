// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import dev.magicphone.core.*
import kotlinx.coroutines.*

/** Local-user input on the existing Accessibility overlay; no exported command surface. */
class QuickPrompt(private val service: PhoneService) {
    var view: View? = null
        private set
    private var draft = ""
    private var input: EditText? = null
    private var submission: Job? = null
    private val runtime get() = service.runtime
    private val manager get() = service.getSystemService(WindowManager::class.java)
    private fun dp(n: Int) = (n * service.resources.displayMetrics.density).toInt()
    private fun text(id: Int) = TextView(service).apply {
        text = service.getString(id); setTextColor(0xfff3f8ee.toInt()); textSize = 15f
    }
    private fun button(label: Int, primary: Boolean = false) = text(label).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (primary) 0xff173d38.toInt() else 0xffb8f3d1.toInt())
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x337fffff),
            GradientDrawable().apply {
                setColor(if (primary) 0xffb8f3d1.toInt() else 0xff254d46.toInt())
                cornerRadius = dp(24).toFloat()
            }, null)
        isClickable = true; isFocusable = true; filterTouchesWhenObscured = true
    }
    fun show() {
        if (view != null) { input?.requestFocus(); keyboard(); return }
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !service.getSystemService(PowerManager::class.java).isInteractive) return
        val app = service.foregroundPackage()
        if (app == service.packageName) {
            service.startActivity(MainActivity.chatIntent(service)); return
        }
        runtime.pauseForChat()
        service.hideInputBubble()
        val field = EditText(service).apply {
            hint = service.getString(R.string.quick_prompt_hint)
            contentDescription = service.getString(R.string.quick_prompt_hint)
            setTextColor(0xfff3f8ee.toInt()); setHintTextColor(0xffb9cbc2.toInt()); textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2; maxLines = 4; setText(draft); setSelection(text.length)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(0xff254d46.toInt()); cornerRadius = dp(16).toFloat() }
            filters = arrayOf(android.text.InputFilter.LengthFilter(8000))
            filterTouchesWhenObscured = true
        }
        input = field
        val panel = object : LinearLayout(service) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { hide(); return true }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply { setColor(0xff173d38.toInt()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0xffb8f3d1.toInt()) }
            elevation = dp(12).toFloat(); filterTouchesWhenObscured = true
            addView(LinearLayout(service).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(R.string.quick_prompt_title).apply { textSize = 18f }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(service).apply { text = "×"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(0xfff3f8ee.toInt()); filterTouchesWhenObscured = true; contentDescription = service.getString(R.string.dismiss)
                    setOnClickListener { hide() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
            addView(text(R.string.quick_prompt_context).apply { textSize = 12f })
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
            addView(LinearLayout(service).apply {
                addView(button(R.string.task_result_open).apply {
                    setOnClickListener {
                        val value = field.text.toString(); hide()
                        service.startActivity(MainActivity.chatIntent(service).putExtra(Intent.EXTRA_TEXT, value))
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(button(R.string.send, primary = true).apply {
                    setOnClickListener {
                        val value = field.text.toString().trim()
                        if (value.isBlank()) return@setOnClickListener
                        if (runtime.settings.value.profiles.none { it.id == runtime.settings.value.selected }) {
                            Toast.makeText(service, R.string.error_provider, Toast.LENGTH_LONG).show(); return@setOnClickListener
                        }
                        val conversation = runtime.current.value
                        hide(); draft = ""
                        submission = runtime.scope.launch {
                            // Let the keyboard and our own protected overlay leave the screen first.
                            delay(350)
                            if (runtime.current.value != conversation) return@launch
                            if (service.foregroundPackage() != app || service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                                Toast.makeText(service, R.string.screen_context_changed, Toast.LENGTH_LONG).show(); return@launch
                            }
                            submission = null
                            runtime.start(value, screenContext = app)
                            if (runtime.agent.state.value == RunState.PAUSED) runtime.agent.resume()
                        }
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        view = panel
        val bounds = manager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(minOf(dp(390), bounds.width() - dp(24)), -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(24)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                title = service.getString(R.string.quick_prompt_title)
            }
        try { manager.addView(panel, params); field.requestFocus(); keyboard() }
        catch (_: RuntimeException) { hide(); service.startActivity(MainActivity.chatIntent(service)) }
    }
    private fun keyboard() {
        val field = input ?: return
        field.post { if (field.isAttachedToWindow) {
            field.requestFocus()
            field.windowInsetsController?.show(WindowInsets.Type.ime())
            service.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
        } }
    }
    fun hide() {
        input?.let { draft = it.text.toString(); service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        view?.let { runCatching { manager.removeView(it) } }
        view = null; input = null
    }
    fun cancel() { submission?.cancel(); submission = null; hide() }
}
