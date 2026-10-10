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

/** Real rendered PDF table; cells are pixels, not text exposed through Accessibility. */
object TableFixture {
    fun show(activity: Activity) {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 600, 1).create())
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 56, 50); textSize = 25f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("Comparing two reading methods", 25f, 42f, paint)
        val rows = listOf(listOf("Metric", "Method A", "Method B"), listOf("Accuracy", "84.2%", "85.5%"),
            listOf("Time", "12 seconds", "8 seconds"), listOf("Samples", "72", "72"))
        rows.forEachIndexed { row, cells ->
            val y = 80f + row * 65
            paint.color = if (row == 0) Color.rgb(213, 239, 227) else Color.rgb(246, 249, 246)
            canvas.drawRect(20f, y, 580f, y + 65, paint)
            paint.color = Color.rgb(30, 60, 50); paint.textSize = 20f; paint.typeface = if (row == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            cells.forEachIndexed { column, text -> canvas.drawText(text, 30f + column * 185, y + 40, paint) }
            paint.strokeWidth = 1f; canvas.drawLine(20f, y + 65, 580f, y + 65, paint)
        }
        paint.textSize = 16f
        canvas.drawText("Synthetic values for testing, not research claims.", 25f, 382f, paint)
        paint.textSize = 20f; paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("A separate equation example", 25f, 445f, paint)
        paint.textSize = 34f
        canvas.drawText("E = mc²", 25f, 495f, paint)
        paint.textSize = 16f; paint.typeface = Typeface.DEFAULT
        canvas.drawText("Energy = mass × speed of light squared.", 25f, 535f, paint)
        canvas.drawText("This equation is independent of the table above.", 25f, 570f, paint)
        pdf.finishPage(page)
        val file = File(activity.cacheDir, "explanation-table.pdf")
        file.outputStream().use { pdf.writeTo(it) }; pdf.close()
        val bitmap = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888)
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            renderer.openPage(0).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        }
        // One PDF page, split into two accessible image regions so a partial overlay
        // on its lower part does not suppress the entire table node in tree-only tests.
        val tableBitmap = Bitmap.createBitmap(bitmap, 0, 0, 1200, 840)
        val equationBitmap = Bitmap.createBitmap(bitmap, 0, 840, 1200, 360)
        bitmap.recycle()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(32), dp(12), dp(12)); setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(activity).apply { text = "PDF table · MagicPhone Practice"; textSize = 20f })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun image(pixels: Bitmap, description: String, heightDp: Int) {
            body.addView(object : View(activity) {
                private val brush = Paint(Paint.FILTER_BITMAP_FLAG)
                init { contentDescription = description; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
                override fun onDraw(c: Canvas) { c.drawBitmap(pixels, null, Rect(0, 0, width, height), brush) }
            }, LinearLayout.LayoutParams(-1, dp(heightDp)))
        }
        image(tableBitmap, "PDF comparison table", 280)
        image(equationBitmap, "PDF equation", 120)
        body.addView(Button(activity).apply {
            text = "Touch-through test: 0"; isAllCaps = false
            var count = 0
            setOnClickListener { text = "Touch-through test: ${++count}" }
        })
        repeat(14) { n -> body.addView(TextView(activity).apply { text = "Document notes ${n + 1}\nThis is synthetic content for scroll and resize tests."; textSize = 17f; setPadding(0, dp(16), 0, dp(16)) }) }
        root.addView(ScrollView(activity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        activity.setContentView(root)
    }
}
