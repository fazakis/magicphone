// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.windowfixture

import android.app.Activity
import android.os.Bundle
import android.widget.Button

class PaneActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(Button(this).apply { isAllCaps = false; text = "Focus other pane"; setBackgroundColor(0xffff00ff.toInt()) })
    }
}
