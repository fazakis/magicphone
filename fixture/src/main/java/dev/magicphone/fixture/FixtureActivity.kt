// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.*

class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("explanationTable", false)) { TableFixture.show(this); return }
        intent.getStringExtra("documentMode")?.let { DocumentFixture.show(this, it); return }
        var count = savedInstanceState?.getInt("count") ?: 0
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 64, 32, 32)
            }
        val result =
            TextView(this).apply {
                text = "Counter: $count"
                textSize = 28f
                id = 1001
            }
        layout.addView(
            TextView(this).apply {
                text = "MagicPhone Practice\nLocal test only — no accounts, purchases or messages."
                textSize = 22f
            }
        )
        layout.addView(result)
        if (intent.getBooleanExtra("explanationText", false)) {
            layout.addView(TextView(this).apply { text = "E = mc²"; textSize = 36f })
            layout.addView(TextView(this).apply { text = "Energy equals mass times the speed of light squared."; textSize = 18f })
        }
        layout.addView(
            Button(this).apply {
                isAllCaps = false
                text = "Add one · Προσθήκη"
                id = 1002
                setOnClickListener {
                    count++
                    result.text = "Counter: $count"
                }
            }
        )
        layout.addView(
            EditText(this).apply {
                hint = "Ordinary text · Κείμενο"
                inputType = intent.getIntExtra("inputType", android.text.InputType.TYPE_CLASS_TEXT)
                id = 1003
                if (intent.hasExtra("inputType")) contentDescription =
                    "Fixture input $inputType large ${intent.getBooleanExtra("largeTree", false)}"
            }
        )
        layout.addView(
            Button(this).apply {
                isAllCaps = false
                text = "Show manual-secret field"
                setOnClickListener {
                    layout.addView(
                        EditText(this@FixtureActivity).apply {
                            hint = "Manual password"
                            inputType = 129
                            id = 1004
                        }
                    )
                }
            }
        )
        if (intent.getBooleanExtra("largeTree", false)) {
            repeat(120) { index -> layout.addView(TextView(this).apply { text = "Ordinary row $index" }) }
        }
        if (intent.getBooleanExtra("bottomControl", false)) {
            fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
            val frame = FrameLayout(this)
            frame.addView(layout, FrameLayout.LayoutParams(-1, -1))
            frame.addView(Button(this).apply {
                text = "Covered action"; isAllCaps = false
                setOnClickListener { count++; result.text = "Counter: $count" }
            }, FrameLayout.LayoutParams(dp(220), dp(64), android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
                rightMargin = dp(16); bottomMargin = dp(100)
            })
            setContentView(frame)
        } else setContentView(layout)
    }
}
