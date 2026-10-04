// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.magicphone.core.*
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith

/** Real storage/UI/provider serialization with a local synthetic server; no account traffic. */
@RunWith(AndroidJUnit4::class)
class ConversationAttachmentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var settings: Settings
    private lateinit var archive: Archive
    private var current: String? = null
    private var keepForRestart = false
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(timeout: Long = 20000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        Assert.fail("Condition timed out: state=${r.agent.state.value}, error=${r.agent.error.value}, notice=${r.notice.value}")
    }
    private fun flush() = runBlocking { r.flushHistory() }

    @Before fun setup() {
        Assert.assertTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        main {
            r.stop(); r.notice.value = ""; settings = r.settings.value; archive = r.archive.value; current = r.current.value
            r.saveSettings(settings.copy(onboarded = true, policy = PolicyConfig(), showTaskResultBubbles = false))
        }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.waitForIdle()
    }

    @After fun cleanup() {
        main {
            r.stop(); r.saveSettings(settings)
            if (!keepForRestart) { r.saveArchive(archive); r.current.value = current }
        }
        flush()
    }

    private fun image(): String {
        val bitmap = Bitmap.createBitmap(900, 500, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK; textSize = 36f }
            drawText("SYNTHETIC BILL - TEST ONLY", 25f, 80f, paint)
            drawText("Customer: 00001234567890123", 25f, 180f, paint)
            drawText("Receipt: 00000987654321098", 25f, 270f, paint)
        }
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes); bitmap.recycle()
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun configure(server: SyntheticServer) = main {
        val profile = Profile(name = "Synthetic attachment test", kind = ProviderKind.COMPATIBLE,
            endpoint = "http://127.0.0.1:${server.port}/v1/", model = "synthetic", localOptIn = true, images = true)
        r.saveSettings(r.settings.value.copy(profiles = r.settings.value.profiles + profile, selected = profile.id))
    }

    private fun submit(text: String, photos: List<String> = emptyList()) {
        var accepted = false
        main { r.start(text, photos, onAccepted = { accepted = true }) }
        await { accepted && !r.submitting.value }
    }
    private fun done(timeout: Long = 20000) = await(timeout) { r.agent.state.value == RunState.COMPLETED }

    @Test fun followupBranchThumbnailAndDeletion() {
        val image = image()
        SyntheticServer { Action(Op.COMPLETE, text = "Customer: 00001234567890123") }.use { server ->
            configure(server)
            main { r.newConversation() }
            val original = r.current.value!!
            submit("Read this synthetic bill", listOf(image)); done(); flush()
            val message = r.archive.value.conversations.single { it.id == original }.messages.first()
            val ref = message.attachments.single()
            Assert.assertEquals(image, AttachmentStore(Vault(context)).load(ref))
            val encrypted = java.io.File(context.noBackupFilesDir, "vault/attachment-${ref.id}.enc").readBytes()
            Assert.assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(image.take(80)))
            main { repeat(30) { r.addMessage("user", "Synthetic intervening step $it") } }
            submit("Use the earlier bill without asking for another photo"); done()
            Assert.assertTrue(server.requests.last().contains(image))
            Assert.assertTrue(server.requests.last().contains("00001234567890123"))
            main { r.branch(original, message.id) }
            val branch = r.current.value!!
            submit("Read the bill in this branch"); done()
            Assert.assertTrue(server.requests.last().contains(image))
            context.startActivity(MainActivity.chatIntent(context, branch))
            device.waitForIdle()
            repeat(4) {
                if (!device.hasObject(By.desc(context.getString(R.string.retained_photo)))) {
                    device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.UP, 1f)
                    SystemClock.sleep(200)
                }
            }
            device.takeScreenshot(java.io.File(context.cacheDir, "retained-photo-before-assert.png"))
            Assert.assertTrue(device.wait(Until.hasObject(By.desc(context.getString(R.string.retained_photo))), 8000))
            device.findObject(By.desc(context.getString(R.string.retained_photo))).click()
            Assert.assertTrue(device.wait(Until.hasObject(By.text(context.getString(android.R.string.ok))), 3000))
            device.pressBack()
            device.takeScreenshot(java.io.File(context.cacheDir, "retained-photo-verified.png"))
            main { r.deleteConversation(original) }; flush()
            Assert.assertNotNull(r.attachments.load(ref))
            main { r.deleteConversation(branch) }; flush()
            Assert.assertNull(r.attachments.load(ref))
        }
    }

    @Test fun attachedAnswerIsDeliveredWhileAgentWaits() {
        var requests = 0
        val image = image()
        SyntheticServer {
            if (requests++ == 0) Action(Op.ASK, text = "Attach the synthetic photo")
            else Action(Op.COMPLETE, text = "Synthetic photo received")
        }.use { server ->
            configure(server); main { r.newConversation() }
            submit("Read a synthetic bill")
            await { r.agent.state.value == RunState.WAITING_USER }
            submit("Here it is", listOf(image)); done(); flush()
            Assert.assertTrue(server.requests.last().contains(image))
            val saved = r.archive.value.conversations.single { it.id == r.current.value }.messages.single { it.text == "Here it is" }
            Assert.assertNotNull(AttachmentStore(Vault(context)).load(saved.attachments.single()))
            submit("Use that photo once more"); done()
            Assert.assertTrue(server.requests.last().contains(image))
        }
    }

    @Test fun unsupportedModelDoesNotAcknowledgeOrDropPhoto() {
        var accepted = false
        main {
            val p = Profile(name = "Text only test", kind = ProviderKind.MOCK, model = "fixture", images = false)
            r.saveSettings(r.settings.value.copy(profiles = listOf(p), selected = p.id))
            r.newConversation()
            r.start("Read photo", listOf(image()), onAccepted = { accepted = true })
        }
        Assert.assertFalse(accepted)
        Assert.assertEquals("images_unsupported", r.notice.value)
        Assert.assertTrue(r.archive.value.conversations.single { it.id == r.current.value }.messages.isEmpty())
    }

    @Test fun stoppedSubmissionCannotStartNewRun() {
        SyntheticServer { Action(Op.COMPLETE, text = "Should not run") }.use { server ->
            configure(server)
            var accepted = false
            val image = image()
            main { r.newConversation(); r.start("Read photo", listOf(image), onAccepted = { accepted = true }); r.stop() }
            await { !r.submitting.value }
            Assert.assertFalse(accepted)
            Assert.assertTrue(server.requests.isEmpty())
        }
    }

    @Test fun liveChatGptUsesPhotoInFollowup() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveChatGpt") == "true")
        val profile = settings.profiles.firstOrNull { it.id == settings.selected && it.kind == ProviderKind.CHATGPT && it.images }
        Assume.assumeTrue("Requires the owner's existing signed-in profile", profile != null && settings.activeAccount.isNotBlank())
        main {
            r.saveSettings(r.settings.value.copy(profiles = r.settings.value.profiles.map {
                if (it.id == profile!!.id) it.copy(reasoningEffort = "low", serviceTier = null) else it
            }, selected = profile!!.id))
            r.newConversation()
        }
        submit("Read this synthetic test bill and COMPLETE with its exact customer and receipt identifiers, preserving leading zeros. Do not operate other apps.", listOf(image()))
        done(90000)
        submit("From the photo I already sent, give both identifiers again exactly. Do not ask me to resend it. COMPLETE with the two values; do not operate apps.")
        done(90000)
        val answer = r.archive.value.conversations.single { it.id == r.current.value }.messages.last { it.role == "assistant" }.text
        Assert.assertTrue("Customer identifier retained", answer.contains("00001234567890123"))
        Assert.assertTrue("Receipt identifier retained", answer.contains("00000987654321098"))
        Assert.assertTrue(r.archive.value.audits.drop(archive.audits.size).any { it.operation == "RECALL" && it.status == "attachment" })
    }

    @Test fun prepareProcessRestart() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("attachmentRestart") == "true")
        SyntheticServer { Action(Op.COMPLETE, text = "Synthetic receipt 00000987654321098") }.use { server ->
            configure(server); main { r.newConversation() }
            submit("Restart test synthetic bill", listOf(image())); done(); flush()
            r.vault.write("attachment-restart-test", r.current.value!!)
            keepForRestart = true
        }
    }

    @Test fun verifyProcessRestart() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("attachmentRestart") == "true")
        val conversation = r.vault.read("attachment-restart-test") ?: error("Prepare first, then force-stop the app")
        val saved = r.archive.value.conversations.single { it.id == conversation }
        val ref = saved.messages.first().attachments.single()
        Assert.assertNotNull(r.attachments.load(ref))
        SyntheticServer { Action(Op.COMPLETE, text = "Retained across restart") }.use { server ->
            configure(server); main { r.selectConversation(conversation) }
            submit("Use my earlier bill"); done()
            Assert.assertTrue(server.requests.last().contains(r.attachments.load(ref)!!))
        }
        archive = archive.copy(conversations = archive.conversations.filterNot { it.id == conversation })
        current = archive.conversations.maxByOrNull { it.updated }?.id
        r.vault.delete("attachment-restart-test")
    }

    private class SyntheticServer(private val next: () -> Action) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        val requests = Collections.synchronizedList(mutableListOf<String>())
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) try {
                socket.accept().use { connection ->
                    connection.soTimeout = 10000
                    val input = connection.getInputStream().buffered()
                    fun line(): String {
                        val bytes = ByteArrayOutputStream()
                        while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) bytes.write(b) }
                        return bytes.toString("UTF-8")
                    }
                    line()
                    var length = 0
                    while (true) {
                        val header = line(); if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    require(length in 1..20_000_000)
                    val bytes = ByteArray(length)
                    java.io.DataInputStream(input).readFully(bytes)
                    val body = bytes.toString(Charsets.UTF_8)
                    requests += body
                    val args = JsonCodec.encodeToString(Action.serializer(), next())
                    val delta = obj("tool_calls" to JsonArray(listOf(obj("index" to JsonPrimitive(0), "id" to j(id()),
                        "type" to j("function"), "function" to obj("name" to j("perform"), "arguments" to j(args))))))
                    val event = obj("choices" to JsonArray(listOf(obj("delta" to delta, "finish_reason" to j("tool_calls")))))
                    val response = "data: $event\n\ndata: [DONE]\n\n".toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(response); flush()
                    }
                }
            } catch (_: Exception) { /* Closing the local test server terminates accept. */ }
        }
        override fun close() { socket.close(); worker.join(1000) }
    }
}
