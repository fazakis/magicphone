// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.windowfixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*

/** Separate synthetic app for foreign-window geometry tests; no permissions or data. */
class WindowActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); render() }
    override fun onNewIntent(value: Intent) { super.onNewIntent(value); intent = value; render() }
    private fun render() {
        setFinishOnTouchOutside(true)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setBackgroundDrawableResource(android.R.color.holo_red_dark)
        val label = TextView(this).apply { text = "FOREIGN FIXTURE CONTENT"; textSize = 20f }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xffff00ff.toInt()); addView(label)
            addView(Button(this@WindowActivity).apply { text = "Close fixture window"; setOnClickListener { finish() } })
        })
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = intent.getIntExtra("left", 400); y = intent.getIntExtra("top", 900)
            width = intent.getIntExtra("width", 500); height = intent.getIntExtra("height", 400)
        }
    }
}
