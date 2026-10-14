// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.magicphone.core.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic files only; no provider calls or shared user documents. */
@RunWith(AndroidJUnit4::class)
class DocumentsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = Documents(context)
    private fun ref(f: PreparedAttachment) = Attachment("a".repeat(64), name=f.name, mimeType=f.mimeType, size=f.size, pages=f.pages)
    private fun zip(vararg items: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> items.forEach { (name, xml) -> z.putNextEntry(ZipEntry(name)); z.write(xml.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun pdfTextVisualPagesAndEncryptedReload() {
        val file = reader.prepare("context.pdf", "application/octet-stream", syntheticPaper())
        Assert.assertEquals("application/pdf", file.mimeType); Assert.assertEquals(2, file.pages)
        if (Build.VERSION.SDK_INT >= 35) Assert.assertTrue(file.chunks.first().contains("Orion"))
        val store = AttachmentStore(Vault(context)); val ref = store.saveFiles(listOf(file)).single()
        try {
            val encrypted = java.io.File(context.noBackupFilesDir, "vault/attachment-${ref.id}.enc").readBytes().toString(Charsets.ISO_8859_1)
            Assert.assertFalse(encrypted.contains("Orion")); Assert.assertFalse(encrypted.contains(file.data.take(100)))
            val result = AttachmentStore(Vault(context)).read(ref, Action(Op.RECALL,node=ref.id,text="page 2"), reader)!!
            Assert.assertNotNull(result.image); Assert.assertTrue(result.content.contains("DOCUMENT PAGE 2"))
            if (Build.VERSION.SDK_INT >= 35) Assert.assertTrue(result.content.contains("paired"))
            Assert.assertTrue(reader.read(ref,file,"page 9").content.contains("out of range"))
            Assert.assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("magicphone-document-") })
        } finally { store.finish(listOf(ref)); Vault(context).delete("attachment-${ref.id}") }
    }

    @Test fun plainTextCsvJsonCodeAndUnicodeRemainReadable() {
        for (name in listOf("data.csv", "metadata.json", "notes.md", "script.py", "unknown.custom")) {
            val f = reader.prepare(name,"application/octet-stream", "Greek: \u03b1\u03ba\u03c1\u03af\u03b2\u03b5\u03b9\u03b1\nidentifier,00001234567890123\nLyra,85.5".toByteArray())
            val result = reader.read(ref(f), f, "Lyra")
            Assert.assertTrue(result.content.contains("00001234567890123")); Assert.assertTrue(result.content.contains("85.5"))
        }
        val utf16 = reader.prepare("notes.txt","text/plain", byteArrayOf(0xff.toByte(),0xfe.toByte()) + "Unicode text".toByteArray(Charsets.UTF_16LE))
        Assert.assertEquals("Unicode text", utf16.chunks.single())
    }

    @Test fun wordSlidesAndOpenDocumentExtractTextWithoutExecutingAnything() {
        val word = reader.prepare("paper.docx","application/octet-stream", zip("word/document.xml" to "<document><p>Method Orion</p><p>Accuracy 84.2</p><p>A&amp;B <![CDATA[<comparison>]]></p></document>"))
        Assert.assertTrue(word.chunks.joinToString().contains("Method Orion"))
        Assert.assertTrue(word.chunks.single().contains("A&B <comparison>"))
        val slides = reader.prepare("results.pptx","application/octet-stream", zip("ppt/slides/slide2.xml" to "<slide><p>Second slide</p></slide>", "ppt/slides/slide1.xml" to "<slide><p>First slide</p></slide>"))
        Assert.assertTrue(slides.chunks.single().indexOf("First slide") < slides.chunks.single().indexOf("Second slide"))
        val odt = reader.prepare("paper.odt","application/octet-stream", zip("content.xml" to "<document><p>Background methodology</p></document>"))
        Assert.assertTrue(odt.chunks.single().contains("Background methodology"))
    }

    @Test fun workbookResolvesSharedStringsToCellValues() {
        val f = reader.prepare("results.xlsx","application/octet-stream", zip(
            "xl/sharedStrings.xml" to "<sst><si><t>Method</t></si><si><t>Lyra</t></si></sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet><row><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\"><v>85.5</v></c></row></worksheet>"))
        val text = f.chunks.single()
        Assert.assertTrue(text.contains("A1=Method")); Assert.assertTrue(text.contains("A2=Lyra")); Assert.assertTrue(text.contains("B2=85.5"))
    }

    @Test fun unknownBinaryAndBrokenPdfAreRetainedWithoutInventedContents() {
        val file = reader.prepare("file.custom","application/octet-stream", byteArrayOf(0, 0xff.toByte(), 4, 0, 5))
        Assert.assertTrue(file.chunks.isEmpty()); Assert.assertTrue(reader.read(ref(file),file,"").content.contains("no readable content"))
        val pdf = reader.prepare("broken.pdf","application/pdf","%PDF-1.7 broken data".toByteArray())
        Assert.assertEquals(0,pdf.pages); Assert.assertTrue(pdf.note.contains("could not be opened"))
        val fakeImage = reader.prepare("fake.jpg","image/jpeg",byteArrayOf(0, 1, 2, 3))
        Assert.assertFalse(fakeImage.isImage)
    }

    @Test fun sizeBoundsAndTextPartsAreExplicit() {
        try { reader.prepare("large.bin","application/octet-stream",ByteArray(Documents.MAX_BYTES+1)); Assert.fail("Oversize accepted") }
        catch (e: SafeFailure) { Assert.assertEquals("file_too_large",e.code) }
        val f = reader.prepare("long.txt","text/plain",("a".repeat(16000)+"UniqueMethod 9472"+"b".repeat(16000)).toByteArray())
        Assert.assertTrue(reader.read(ref(f),f,"part 2").content.contains("UniqueMethod 9472"))
        Assert.assertTrue(reader.read(ref(f),f,"UniqueMethod").content.contains("UniqueMethod 9472"))
        Assert.assertTrue(reader.read(ref(f),f,"part 99").content.contains("out of range"))
    }

    @Test fun archiveExpansionAndExternalEntitiesDoNotEscapeReader() {
        val bomb = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("ignored.bin")); z.write(ByteArray(8*1024*1024+1)); z.closeEntry() } }.toByteArray()
        Assert.assertTrue(reader.prepare("bomb.docx","application/octet-stream",bomb).chunks.isEmpty())
        val xml = "<!DOCTYPE d [<!ENTITY x SYSTEM 'file:///data/local/tmp/private'>]><document><p>&x;</p></document>"
        Assert.assertTrue(reader.prepare("external.docx","application/octet-stream",zip("word/document.xml" to xml)).chunks.isEmpty())
    }
}

internal fun syntheticPaper(): ByteArray {
    val out = ByteArrayOutputStream()
    val pdf = PdfDocument()
    try {
        listOf(
            listOf("SYNTHETIC RESEARCH PAPER - TEST ONLY", "Method A is the Orion baseline classifier.", "Method B is the Lyra calibration method.", "Accuracy is measured on 72 paired test samples.", "Times are seconds per complete evaluation run."),
            listOf("EXPERIMENTAL CONTEXT - TEST ONLY", "The same 72 paired samples are used by both methods.", "This is not an independent replication or a clinical study.", "No uncertainty interval is reported; significance is unknown.", "Keep these limitations when explaining the visible results.")
        ).forEachIndexed { i, lines ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(600,800,i+1).create())
            page.canvas.drawColor(Color.WHITE)
            val paint=Paint().apply { color=Color.BLACK; textSize=18f }
            lines.forEachIndexed { row,text -> page.canvas.drawText(text,25f,80f+row*45f,paint) }
            pdf.finishPage(page)
        }
        pdf.writeTo(out)
    } finally { pdf.close() }
    return out.toByteArray()
}
