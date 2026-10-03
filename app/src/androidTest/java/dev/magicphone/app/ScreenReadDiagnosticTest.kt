// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.magicphone.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Regresses the 0.2.3 content-loss paths on an isolated rendered PDF, not personal documents. */
@RunWith(AndroidJUnit4::class)
class ScreenReadDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private val pkg = "dev.magicphone.fixture"
    private lateinit var device: UiDevice
    private lateinit var settings: Settings
    private lateinit var archive: Archive
    private var selected: String? = null
    private val dir get() = File(context.getExternalFilesDir(null), "screen-read-diagnostics").apply { mkdirs() }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, timeout: Long = 15000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        Assert.assertTrue(message, predicate())
    }
    @Before fun setup() {
        Assert.assertTrue(android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        device = UiDevice.getInstance(instrumentation)
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        settings = r.settings.value; archive = r.archive.value; selected = r.current.value
        val component = "${context.packageName}/${context.packageName}.PhoneService"
        val enabled = device.executeShellCommand("settings get secure enabled_accessibility_services").trim().split(":")
        Assert.assertTrue(component in enabled)
        if (r.phone == null) {
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabled.filter { it != component }.joinToString(":").ifEmpty { "null" }}")
            SystemClock.sleep(400)
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabled.joinToString(":")}")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
        }
        await("Accessibility connected") { r.connected.value }
        main {
            r.stop(); r.agent.clearView(); r.newConversation()
            r.saveSettings(settings.copy(showTaskResultBubbles = false, policy = PolicyConfig(apps = mapOf(pkg to AppRule(observe = true)))))
        }
    }
    @After fun restore() {
        main {
            r.stop(); r.agent.clearView(); r.screenReadNotice.cancel()
            r.saveSettings(settings); r.saveArchive(archive); r.current.value = selected
        }
        runBlocking { r.flushHistory() }
        device.pressBack()
    }
    private fun fixture(mode: String) {
        main { r.stop(); r.agent.clearView(); r.screenReadNotice.cancel() }
        require(mode in setOf("plain", "large", "text", "animated", "secure", "late_password", "very_long"))
        // Shell launch is fixture setup only, not a model operation; avoids background-start
        // restrictions when instrumentation has just replaced the target app process.
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity --es documentMode $mode")
        val ready = device.wait(Until.hasObject(By.desc("Document fixture $mode")), 10000)
        if (!ready && device.hasObject(By.pkg(pkg))) {
            device.dumpWindowHierarchy(File(dir, "fixture-$mode-hierarchy.xml"))
            device.takeScreenshot(File(dir, "fixture-$mode-failure.png"))
        }
        Assert.assertTrue("Document marker mode=$mode foreground=${device.currentPackageName}", ready)
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) device.pressBack()
        // Fixture readiness is not a production retry/backoff. Capture the final laid-out page.
        SystemClock.sleep(250)
    }
    private fun observe(): Screen = runBlocking {
        r.gateway.start()
        try { JsonCodec.decodeFromString<Screen>(r.gateway.run(Action(Op.OBSERVE, pkg)).content) }
        finally { r.gateway.stop() }
    }
    private fun record(name: String, data: JsonObject) { File(dir, "$name.json").writeText(data.toString()) }
    private fun summary(screen: Screen) = buildJsonObject {
        put("nodes", screen.nodes.size); put("partial", screen.partial); put("mixed", screen.mixed)
        put("sensitive", screen.sensitive); put("focused", screen.focused)
        put("documentTextPresent", screen.nodes.any { it.label.contains("A small research team") })
        put("documentCodePresent", screen.nodes.any { it.label.contains("4827") })
        put("maxLabelCharacters", screen.nodes.maxOfOrNull { it.label.length } ?: 0)
    }
    private fun inputProbe(name: String): JsonObject {
        val result = CompletableDeferred<JsonObject>()
        val provider = object : ModelProvider {
            override val supportsImages = true
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                val images = input.flatMap { item ->
                    val content = item.jsonObject["content"]
                    if (content is JsonArray) content.filterIsInstance<JsonObject>().filter { it.str("type") == "input_image" }.map { it.str("image_url") }
                    else emptyList()
                }
                val texts = input.mapNotNull { (it.jsonObject["content"] as? JsonPrimitive)?.content }.joinToString("\n")
                // Only synthetic fixture images, never account screens or raw provider payloads.
                if (images.isNotEmpty()) File(dir, "$name-model.jpg").writeBytes(java.util.Base64.getDecoder().decode(images.first().substringAfter(',')))
                result.complete(buildJsonObject {
                    put("imageCount", images.size); put("documentTextPresent", texts.contains("A small research team"))
                    put("documentCodePresent", texts.contains("4827"))
                    put("partialImageOmitted", texts.contains("partial_screen_image_omitted"))
                    put("observationUnavailable", texts.contains("observation_unavailable"))
                })
                return Reply("Diagnostic input recorded.", emptyList(), emptyList())
            }
        }
        // Match current-screen popup submission, including its bounded capture recovery.
        main { r.agent.start(r.scope, provider, "Read the visible document", screenContext = pkg, captureScreen = true) }
        val data = runBlocking { withTimeout(15000) { result.await() } }
        await("Diagnostic finished, error=${r.agent.error.value}") { r.agent.state.value == RunState.COMPLETED }
        return data
    }
    @Test fun compareClearPageAndSamePageWithLargeAccessibilityTree() {
        fixture("plain")
        val plain = observe()
        Assert.assertFalse(plain.partial)
        Assert.assertFalse(plain.nodes.any { it.label.contains("4827") })
        val normalInput = inputProbe("plain")
        Assert.assertEquals(1, normalInput["imageCount"]!!.jsonPrimitive.int)
        Assert.assertTrue(device.takeScreenshot(File(dir, "plain-ui.png")))
        fixture("large")
        val large = observe()
        Assert.assertTrue(large.partial)
        Assert.assertFalse(large.mixed); Assert.assertFalse(large.sensitive)
        val largeInput = inputProbe("large")
        Assert.assertEquals(1, largeInput["imageCount"]!!.jsonPrimitive.int)
        Assert.assertFalse(largeInput["documentTextPresent"]!!.jsonPrimitive.boolean)
        Assert.assertFalse(largeInput["partialImageOmitted"]!!.jsonPrimitive.boolean)
        record("plain-versus-large", buildJsonObject {
            put("plainScreen", summary(plain)); put("plainModelInput", normalInput)
            put("largeScreen", summary(large)); put("largeModelInput", largeInput)
            put("largeInspection", r.phone!!.inspectionDiagnostics)
        })
        Assert.assertTrue(device.takeScreenshot(File(dir, "large-ui.png")))
    }
    @Test fun keyboardPreservesPdfImageAndMasksCoveredPixels() {
        fixture("plain")
        device.findObject(By.clazz("android.widget.EditText").pkg(pkg)).click()
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 5000))
        val screen = observe()
        val input = inputProbe("keyboard")
        record("keyboard", buildJsonObject { put("screen", summary(screen)); put("input", input); put("inspection", r.phone!!.inspectionDiagnostics) })
        Assert.assertTrue(screen.partial)
        Assert.assertEquals(1, input["imageCount"]!!.jsonPrimitive.int)
        Assert.assertFalse(input["documentTextPresent"]!!.jsonPrimitive.boolean)
        val bitmap = android.graphics.BitmapFactory.decodeFile(File(dir, "keyboard-model.jpg").path)
        var checked = 0
        for (area in r.phone!!.inspect(pkg).protectedRects) {
            val left = maxOf(area.left, screen.captureBounds.left) + 8
            val right = minOf(area.right, screen.captureBounds.right) - 8
            val top = maxOf(area.top, screen.captureBounds.top) + 8
            val bottom = minOf(area.bottom, screen.captureBounds.bottom) - 8
            if (left < right && top < bottom) {
                val pixel = bitmap.getPixel((left + right) / 2 - screen.captureBounds.left, (top + bottom) / 2 - screen.captureBounds.top)
                Assert.assertTrue(android.graphics.Color.red(pixel) < 12 && android.graphics.Color.green(pixel) < 12 && android.graphics.Color.blue(pixel) < 12)
                checked++
            }
        }
        bitmap.recycle()
        // resize-mode IMEs can sit entirely outside the app crop; otherwise pixels must be masked.
        record("keyboard-mask", buildJsonObject { put("maskedRegionsChecked", checked) })
        Assert.assertTrue(device.takeScreenshot(File(dir, "keyboard-ui.png")))
    }
    @Test fun longAccessibleDocumentTextPreservesParagraphAndCode() {
        fixture("text")
        val screen = observe()
        val body = screen.nodes.single { it.label.startsWith("A small research team") }
        record("long-text", summary(screen))
        Assert.assertTrue(body.label.length > 500)
        Assert.assertTrue(body.label.contains("4827"))
        Assert.assertFalse(screen.partial)
    }
    @Test fun stableDocumentCaptureVersusChangingToolbar() {
        val records = mutableListOf<JsonElement>()
        for (mode in listOf("plain", "animated")) {
            fixture(mode)
            repeat(4) { index ->
                SystemClock.sleep(1300) // Isolate binding failures from the existing screenshot throttle.
                val outcome = runBlocking {
                    r.gateway.start()
                    try {
                        val screen = JsonCodec.decodeFromString<Screen>(r.gateway.run(Action(Op.OBSERVE, pkg)).content)
                        try { r.gateway.run(Action(Op.SCREENSHOT, pkg, screen.id)).status }
                        catch (e: SafeFailure) { e.code }
                    } finally { r.gateway.stop() }
                }
                records += buildJsonObject { put("mode", mode); put("attempt", index + 1); put("outcome", outcome) }
            }
        }
        record("toolbar-captures", buildJsonObject { put("attempts", JsonArray(records)) })
        Assert.assertEquals(4, records.count { it.jsonObject.str("mode") == "plain" && it.jsonObject.str("outcome") == "captured" })
        Assert.assertEquals(8, records.count { it.jsonObject.str("outcome") == "captured" })
    }
    @Test fun passwordBeyondTextBudgetAndSecureWindowStillRejectCapture() {
        fixture("late_password")
        var screen: Screen? = null
        main { screen = r.phone!!.inspect(pkg) }
        Assert.assertTrue("Visible password beyond child 100 is scanned", screen!!.sensitive)
        runBlocking {
            r.gateway.start()
            try {
                try { r.gateway.run(Action(Op.SCREENSHOT, pkg, "old")); Assert.fail("Password must stay manual") }
                catch (e: SafeFailure) { Assert.assertEquals("manual_secret", e.code) }
            } finally { r.gateway.stop() }
        }
        // The explicit secure-window error is provided by the window API on Android 14+.
        if (android.os.Build.VERSION.SDK_INT < 34) return
        fixture("secure")
        SystemClock.sleep(1300)
        runBlocking {
            r.gateway.start()
            try {
                val fresh = JsonCodec.decodeFromString<Screen>(r.gateway.run(Action(Op.OBSERVE, pkg)).content)
                try { r.gateway.run(Action(Op.SCREENSHOT, pkg, fresh.id)); Assert.fail("Secure window must not capture") }
                catch (e: SafeFailure) { Assert.assertEquals("secure_window", e.code) }
            } finally { r.gateway.stop() }
        }
    }
    @Test fun largerTextTruncationIsExplicitAndStillAllowsImageFallback() {
        fixture("very_long")
        val screen = observe()
        Assert.assertTrue(screen.partial)
        Assert.assertTrue(screen.captureReady)
        Assert.assertTrue(screen.nodes.any { it.label.length == 8000 })
        Assert.assertEquals(1, inputProbe("very-long")["imageCount"]!!.jsonPrimitive.int)
    }
    @Test fun actualChatGptReadsBothClearAndPartialPdf() {
        Assume.assumeTrue("Existing account-owner opt-in", InstrumentationRegistry.getArguments().getString("liveChatGpt") == "true")
        val profile = settings.profiles.single { it.id == settings.selected }
        Assert.assertEquals(ProviderKind.CHATGPT, profile.kind)
        Assert.assertTrue(profile.images)
        val reports = mutableListOf<JsonElement>()
        for (mode in listOf("plain", "large")) {
            fixture(mode)
            val before = r.archive.value.audits.map { it.id }.toSet()
            main { r.start("Read the four-digit document code printed inside the currently visible PDF page. " +
                "Return only that code once you can actually read it. Do not guess. " +
                "If the content is unavailable, try OBSERVE and SCREENSHOT once more before asking me for help. " +
                "Do not navigate, tap or type in the other app.", screenContext = pkg) }
            await("Live diagnostic finished, mode=$mode", 180000) {
                r.agent.state.value in setOf(RunState.COMPLETED, RunState.WAITING_USER, RunState.FAILED)
            }
            val conversation = r.archive.value.conversations.single { it.id == r.current.value }
            val answer = conversation.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty()
            val audits = r.archive.value.audits.filter { it.id !in before }
            reports += buildJsonObject {
                put("mode", mode); put("state", r.agent.state.value.name); put("error", r.agent.error.value)
                put("codeReadCorrectly", answer.contains("4827")); put("modelCalls", r.agent.metrics.value.modelCalls)
                put("imageCaptured", audits.any { it.operation == "SCREENSHOT" && it.status == "captured" })
                put("askedForInput", r.agent.state.value == RunState.WAITING_USER)
                put("operations", JsonArray(audits.map { buildJsonObject { put("op", it.operation); put("status", it.status) } }))
            }
            record("live-chatgpt", buildJsonObject { put("cases", JsonArray(reports)) })
            Assert.assertTrue("Live model should read the code from PDF pixels, mode=$mode", answer.contains("4827"))
            Assert.assertEquals(RunState.COMPLETED, r.agent.state.value)
            Assert.assertTrue(audits.any { it.operation == "SCREENSHOT" && it.status == "captured" })
            main { r.stop(); r.newConversation() }
        }
    }
    @Test fun actualFoldTransitionsKeepServiceAndMeasureFreshScreens() {
        Assume.assumeTrue("Dedicated fold emulator opt-in", InstrumentationRegistry.getArguments().getString("foldDevice") == "true")
        fixture("plain")
        val reports = mutableListOf<JsonElement>()
        var previous: Screen? = null
        val service = r.phone
        r.gateway.start()
        try {
            for (phase in listOf("open", "closed", "reopened")) {
                File(dir, "fold-ready").writeText(phase)
                await("Host fold transition: $phase", 90000) { File(dir, "fold-go").takeIf { it.exists() }?.readText()?.trim() == phase }
                val staleStatus = previous?.let { old -> runBlocking {
                    try { r.gateway.run(Action(Op.SCREENSHOT, pkg, old.id)).status }
                    catch (e: SafeFailure) { e.code }
                } } ?: "no_previous_snapshot"
                val samples = mutableListOf<JsonElement>()
                repeat(6) { index ->
                    val sampled = runBlocking {
                        try {
                            val screen = JsonCodec.decodeFromString<Screen>(r.gateway.run(Action(Op.OBSERVE, pkg)).content)
                            previous = screen
                            buildJsonObject {
                                put("sample", index); put("status", "observed"); put("screen", summary(screen))
                                put("width", screen.width); put("height", screen.height); put("window", screen.window)
                                put("captureLeft", screen.captureBounds.left); put("captureTop", screen.captureBounds.top)
                                put("captureRight", screen.captureBounds.right); put("captureBottom", screen.captureBounds.bottom)
                            }
                        } catch (e: SafeFailure) { buildJsonObject { put("sample", index); put("status", e.code) } }
                    }
                    samples += sampled
                    SystemClock.sleep(250)
                }
                val shot = previous?.let { screen -> runBlocking {
                    try {
                        val result = r.gateway.run(Action(Op.SCREENSHOT, pkg, screen.id))
                        result.image?.let { File(dir, "fold-$phase-model.jpg").writeBytes(java.util.Base64.getDecoder().decode(it.substringAfter(','))) }
                        result.status
                    } catch (e: SafeFailure) { e.code }
                } } ?: "no_screen"
                device.takeScreenshot(File(dir, "fold-$phase-ui.png"))
                reports += buildJsonObject {
                    put("phase", phase); put("deviceState", device.executeShellCommand("cmd device_state print-state").trim())
                    put("displayWidth", device.displayWidth); put("displayHeight", device.displayHeight)
                    put("sameService", service === r.phone); put("oldSnapshotCapture", staleStatus)
                    put("freshCapture", shot); put("samples", JsonArray(samples))
                }
                record(if (InstrumentationRegistry.getArguments().getString("resizeTransitions") == "true") "fold-resize-transitions" else "fold-transitions", buildJsonObject { put("phases", JsonArray(reports)) })
            }
        } finally { r.gateway.stop(); File(dir, "fold-ready").writeText("done") }
        Assert.assertEquals(3, reports.size)
        for (report in reports) {
            Assert.assertEquals("captured", report.jsonObject.str("freshCapture"))
            Assert.assertTrue(report.jsonObject["sameService"]!!.jsonPrimitive.boolean)
            Assert.assertTrue(report.jsonObject["samples"]!!.jsonArray.all { it.jsonObject.str("status") == "observed" })
        }
    }

}
