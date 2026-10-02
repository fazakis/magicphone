// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.fixture

import android.app.Activity
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.*
import java.io.File

/** Synthetic PDF with no accounts or private content, only for emulator diagnostics. */
object DocumentFixture {
    const val body = "A small research team compared two ways of reading a visible document. " +
        "The first method used the text exposed by the application. The second method used a picture of the page. " +
        "Both methods read the same words when the application supplied complete information. " +
        "When the document was drawn as an image, only the picture contained the actual paragraph."
    const val code = "4827"
    fun show(activity: Activity, mode: String) {
        if (mode == "secure") activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(48), dp(12), dp(12))
            setBackgroundColor(Color.rgb(241, 243, 241))
        }
        root.addView(TextView(activity).apply {
            text = "Synthetic document · $mode"
            textSize = 18f
            contentDescription = "Document fixture $mode"
        })
        val status = TextView(activity).apply { text = "Page 1"; textSize = 12f }
        root.addView(status)
        if (mode == "text") {
            root.addView(TextView(activity).apply { text = "$body $body Final document code: $code"; textSize = 14f })
        } else {
            val file = File(activity.cacheDir, "screen-read-fixture.pdf")
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 710, 1).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
                page.canvas.drawText("A controlled screen-reading study", 35f, 50f, paint)
                paint.textSize = 19f
                page.canvas.drawText("Abstract", 35f, 103f, paint)
                paint.typeface = Typeface.DEFAULT; paint.textSize = 18f
                var line = ""; var y = 143f
                for (word in body.split(' ')) {
                    val next = if (line.isEmpty()) word else "$line $word"
                    if (paint.measureText(next) > 525) { page.canvas.drawText(line, 35f, y, paint); y += 26; line = word }
                    else line = next
                }
                page.canvas.drawText(line, 35f, y, paint)
                paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 24f
                page.canvas.drawText("Document code: $code", 35f, y + 70, paint)
                pdf.finishPage(page)
                file.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            val bitmap = Bitmap.createBitmap(900, 1065, Bitmap.Config.ARGB_8888)
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                renderer.openPage(0).use { page -> page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            }
            root.addView(object : View(activity) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
                }
            }, LinearLayout.LayoutParams(-1, dp(425)))
        }
        root.addView(EditText(activity).apply { hint = "Fixture note"; isSingleLine = true; id = 1003 })
        if (mode == "large") repeat(120) { index -> root.addView(TextView(activity).apply { text = "Row $index" }) }
        if (mode == "late_password") {
            // Zero-height siblings put a visible password beyond the old child scan limit.
            repeat(120) { root.addView(TextView(activity), LinearLayout.LayoutParams(1, 0)) }
            root.addView(EditText(activity).apply { hint = "Manual password"; inputType = 129 })
        }
        if (mode == "very_long") root.addView(TextView(activity).apply { text = body.repeat(30) })
        if (mode == "animated") {
            status.post(object : Runnable {
                var tick = 0
                override fun run() {
                    if (!status.isAttachedToWindow) return
                    status.text = "Page 1 · toolbar tick ${++tick}"
                    status.postDelayed(this, 30)
                }
            })
        }
        root.isFocusableInTouchMode = true; root.requestFocus()
        activity.setContentView(root)
    }
}
