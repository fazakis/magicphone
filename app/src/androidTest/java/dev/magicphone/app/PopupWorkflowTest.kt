// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.speech.RecognizerIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.magicphone.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/** Dedicated emulator, synthetic fixture, controlled providers/results; no personal apps. */
@RunWith(AndroidJUnit4::class)
class PopupWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private val pkg = "dev.magicphone.fixture"
    private lateinit var device: UiDevice
    private lateinit var settings: Settings
    private lateinit var archive: Archive
    private var selected: String? = null
    private val dir get() = File(context.getExternalFilesDir(null), "popup-025").apply { mkdirs() }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (!check() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(30)
        Assert.assertTrue(message, check())
    }
    private fun input() = device.findObject(By.clazz("android.widget.EditText").pkg(context.packageName))
    private fun text(id: Int) = context.getString(id)
    private fun fixture() {
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity --es documentMode plain")
        Assert.assertTrue(device.wait(Until.hasObject(By.desc("Document fixture plain")), 10000))
        await("Full chat backgrounded") { r.visibleChat.value == null }
    }
    private fun prompt() {
        main { r.phone!!.quickPrompt.show() }
        await("Prompt focused") { input()?.isFocused == true }
    }
    private fun capture(name: String) { device.takeScreenshot(File(dir, "$name.png")) }
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
        }
        await("Accessibility connected") { r.connected.value }
        main {
            r.newConversation()
            r.saveSettings(settings.copy(onboarded = true, showTaskResultBubbles = true,
                policy = PolicyConfig(apps = mapOf(pkg to AppRule(true, true)), allowAllApps = true)))
        }
        fixture()
    }
    @After fun restore() {
        main { r.stop(); r.agent.clearView(); r.saveSettings(settings); r.saveArchive(archive); r.current.value = selected }
        runBlocking { r.flushHistory() }
        device.pressBack()
    }
    private class HeldProvider : ModelProvider {
        override val supportsImages = true
        val entered = CompletableDeferred<String>()
        val finish = CompletableDeferred<String>()
        var cancelled = false
        var stream: ((String) -> Unit)? = null
        override suspend fun models() = emptyList<ModelChoice>()
        override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
            stream = delta
            val image = input.flatMap { ((it as? JsonObject)?.get("content") as? JsonArray).orEmpty() }
                .filterIsInstance<JsonObject>().first { it.str("type") == "input_image" }.str("image_url")
            entered.complete(image)
            try {
                val result = finish.await()
                if (result == "failure") throw SafeFailure("fixture_error")
                return Reply("", listOf(Call(id(), listOf(Action(if (result == "ask") Op.ASK else Op.COMPLETE,
                    text = if (result == "ask") "Which paragraph?" else "Document answer ready")))), emptyList())
            } catch (e: CancellationException) { cancelled = true; throw e }
        }
    }
    private fun startHeld(): HeldProvider {
        val p = HeldProvider()
        main {
            r.popupConversation.value = r.current.value
            r.agent.start(r.scope, p, "Read the current screen", screenContext = pkg, captureScreen = true)
        }
        runBlocking { withTimeout(15000) { p.entered.await() } }
        await("Working bubble after initial capture") { r.phone!!.workingBubble.view != null }
        return p
    }
    @Test fun initialImageExcludesProgressThenStableProgressAndStopWork() {
        val p = startHeld()
        val bubble = r.phone!!.workingBubble.view!!
        val original = Base64.getDecoder().decode(runBlocking { p.entered.await() }.substringAfter(','))
        File(dir, "initial-model.jpg").writeBytes(original)
        val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size)
        var screen: Screen? = null
        val position = IntArray(2)
        main { screen = r.phone!!.inspect(pkg); bubble.getLocationOnScreen(position) }
        Assert.assertFalse("Owned bubble must not block app reads", screen!!.mixed)
        val cx = position[0] + bubble.width / 2
        val cy = position[1] + bubble.height / 2
        Assert.assertTrue(screen!!.protectedRects.any { it.contains(cx, cy) })
        // The initial image contains the underlying fixture, not a masked working bubble.
        var light = 0; var sampled = 0
        for (x in position[0] + 12 until position[0] + bubble.width - 12 step 15) {
            val iy = cy - screen!!.captureBounds.top; val ix = x - screen!!.captureBounds.left
            if (ix in 0 until bitmap.width && iy in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(ix, iy); sampled++
                if (android.graphics.Color.red(pixel) > 120) light++
            }
        }
        bitmap.recycle(); Assert.assertTrue(sampled > 5 && light > sampled / 2)
        main { p.stream!!.invoke("Preparing a careful answer") }
        await("Gentle progress text update") { device.hasObject(By.text(text(R.string.working_answering))) }
        Assert.assertSame(bubble, r.phone!!.workingBubble.view)
        capture("working-bubble")
        runBlocking {
            try { r.gateway.run(Action(Op.TAP, pkg, screen!!.id, x = cx, y = cy)); Assert.fail("Model cannot tap Stop") }
            catch (e: SafeFailure) { Assert.assertTrue(e.code in setOf("protected_control", "stale_target")) }
        }
        device.findObject(By.desc(text(R.string.working_stop))).click()
        await("Stop cancels model and removes bubble") { p.cancelled && r.agent.state.value == RunState.STOPPED && r.phone!!.workingBubble.view == null }
        p.finish.complete("complete")
        SystemClock.sleep(200)
        Assert.assertEquals(RunState.STOPPED, r.agent.state.value)
        Assert.assertNull(r.popupConversation.value)
    }
    @Test fun progressYieldsToAnswerQuestionAndFailureBubbles() {
        for (outcome in listOf("complete", "ask", "failure")) {
            main { r.stop(); r.agent.clearView(); r.newConversation() }
            fixture()
            val p = startHeld(); p.finish.complete(outcome)
            await("Progress removed for $outcome") { r.phone!!.workingBubble.view == null &&
                r.agent.state.value in setOf(RunState.COMPLETED, RunState.WAITING_USER, RunState.FAILED) }
            await("Result/question surface for $outcome") { if (outcome == "ask") r.inputRequest.value != null else r.resultRequest.value != null }
        }
    }
    @Test fun popupFollowupReplacesWaitingRunAndCapturesAgainInSameChat() {
        // The practice provider accepts text only; popup submission must still capture locally.
        main { r.configurePractice() }
        val waiting = object : ModelProvider {
            override val supportsImages = false
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit) =
                Reply("", listOf(Call(id(), listOf(Action(Op.ASK, text = "What next?")))), emptyList())
        }
        main { r.agent.start(r.scope, waiting, "Previous task") }
        await("Old task waiting") { r.agent.state.value == RunState.WAITING_USER }
        val chat = r.current.value
        val before = r.archive.value.audits.count { it.operation == "SCREENSHOT" && it.status == "captured" }
        // Use the ordinary fixture for the practice workflow.
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity")
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Counter: 0")), 10000))
        prompt(); input().text = "Read this new current screen"
        capture("prompt-with-microphone")
        device.findObject(By.text(text(R.string.send))).click()
        await("Popup follow-up completed: ${r.agent.error.value}") { r.agent.state.value in setOf(RunState.COMPLETED, RunState.FAILED) }
        Assert.assertEquals(RunState.COMPLETED, r.agent.state.value)
        Assert.assertEquals(chat, r.current.value)
        Assert.assertEquals(before + 1, r.archive.value.audits.count { it.operation == "SCREENSHOT" && it.status == "captured" })
        Assert.assertTrue(r.archive.value.conversations.single { it.id == chat }.messages.any { it.text == "What next?" })
        Assert.assertEquals(ScreenCaptureState.CAPTURED, r.agent.screenCapture.value)
    }
    @Test fun popupVoiceAppendsGreekAndCancelKeepsDraftWithoutSubmitting() {
        prompt(); input().text = "Please"
        val chat = r.current.value
        val audits = r.archive.value.audits.size
        fun result(code: Int, transcript: String) {
            // IntentFilter also matches an explicit activity with a null action. Match the
            // recognizer action exactly so the private bridge itself really launches.
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                    if (intent.action == RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        Instrumentation.ActivityResult(code, Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(transcript)))
                    else null
            }
            instrumentation.addMonitor(monitor)
            try {
                device.findObject(By.desc(text(R.string.voice_input)).pkg(context.packageName)).click()
                await("Recognizer requested") { monitor.hits == 1 }
                await("Popup restored to same app") { input()?.isFocused == true && device.hasObject(By.pkg(pkg)) }
            } finally { instrumentation.removeMonitor(monitor) }
        }
        result(Activity.RESULT_OK, "μετάφρασε την οθόνη")
        Assert.assertEquals("Please μετάφρασε την οθόνη", input().text)
        result(Activity.RESULT_CANCELED, "ignored")
        Assert.assertEquals("Please μετάφρασε την οθόνη", input().text)
        Assert.assertEquals(chat, r.current.value); Assert.assertEquals(RunState.IDLE, r.agent.state.value)
        Assert.assertEquals(audits, r.archive.value.audits.size)
        capture("dictated-popup")
    }
    @Test fun popupActionsReceiveFreshImagesAndKeepControlsReachable() {
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity")
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Counter: 0")), 10000))
        val provider = object : ModelProvider {
            override val supportsImages = true
            var previousImage = ""
            var sawCounterOne = false
            var rounds = 0
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                delay(850) // Give the progress bubble time to appear between phone operations.
                if (++rounds > 8) throw SafeFailure("fixture_action_loop")
                val outputs = input.mapNotNull { item ->
                    val o = item.jsonObject
                    (o["output"] as? JsonPrimitive)?.content ?: (o["content"] as? JsonPrimitive)?.content
                }.flatMap { value ->
                    runCatching { JsonCodec.decodeFromString<List<ToolResult>>(value.substringAfterLast('\n')) }.getOrDefault(emptyList())
                }
                fun reply(a: Action) = Reply("", listOf(Call(id(), listOf(a))), emptyList())
                if (outputs.lastOrNull()?.status?.startsWith("not_dispatched_") == true)
                    return reply(Action(Op.OBSERVE, pkg))
                val screen = outputs.lastOrNull { it.status == "observed" }?.let { JsonCodec.decodeFromString<Screen>(it.content) }
                    ?: throw SafeFailure("fixture_no_screen")
                val images = input.flatMap { ((it as? JsonObject)?.get("content") as? JsonArray).orEmpty() }
                    .filterIsInstance<JsonObject>().filter { it.str("type") == "input_image" }.map { it.str("image_url") }
                val counterOne = screen.nodes.any { it.label == "Counter: 1" }
                if (!counterOne) {
                    previousImage = images.lastOrNull().orEmpty()
                    val button = screen.nodes.first { it.label.contains("Add one") }
                    return reply(Action(Op.TAP, pkg, screen.id, button.ref))
                }
                if (!sawCounterOne) {
                    sawCounterOne = true
                    if (images.lastOrNull() == previousImage) throw SafeFailure("fixture_image_not_refreshed")
                    if (images.size != 1) throw SafeFailure("fixture_old_images_retained")
                }
                val field = screen.nodes.firstOrNull { it.editable } ?: throw SafeFailure("fixture_field_hidden")
                return if (field.label == "Popup action verified") reply(Action(Op.COMPLETE, text = "Actions verified"))
                else reply(Action(Op.TEXT, pkg, screen.id, field.ref, "Popup action verified"))
            }
        }
        main {
            r.popupConversation.value = r.current.value
            r.agent.start(r.scope, provider, "Press Add one and enter Popup action verified", optimize = true, screenContext = pkg, captureScreen = true)
        }
        await("Action workflow ended") { r.agent.state.value in setOf(RunState.COMPLETED, RunState.FAILED) }
        Assert.assertEquals("error=${r.agent.error.value}; actions=${r.agent.actions.value}", RunState.COMPLETED, r.agent.state.value)
        Assert.assertTrue(device.hasObject(By.text("Counter: 1")))
        Assert.assertTrue(device.hasObject(By.text("Popup action verified")))
    }
    @Test fun controlUnderProgressCanBeObservedAndTappedWithoutStaleRetry() {
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity --ez bottomControl true")
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Covered action")), 10000))
        startHeld()
        val screen = runBlocking { JsonCodec.decodeFromString<Screen>(r.gateway.run(Action(Op.OBSERVE, pkg)).content) }
        val node = screen.nodes.single { it.label == "Covered action" }
        await("Progress returns while model thinks") { r.phone!!.workingBubble.view != null }
        val result = runBlocking { r.gateway.run(Action(Op.TAP, pkg, screen.id, node.ref)) }
        Assert.assertEquals("dispatched", result.status)
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Counter: 1")), 5000))
        main { r.stop() }
    }
    @Test fun sensitiveSwitchPersistsAndControlsFixtureReadsCaptureAndTyping() {
        Assert.assertTrue(decodeSettings("{}").policy.checkSensitiveContent)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun tap(label: Int) {
            device.waitForIdle(3000)
            if (!device.wait(Until.hasObject(By.text(text(label))), 3000))
                UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text(label)))
            val control = device.wait(Until.findObject(By.text(text(label))), 10000)
            Assert.assertNotNull("Settings control: ${text(label)}", control)
            control.click()
        }
        tap(R.string.settings); tap(R.string.access_section); tap(R.string.sensitive_checks)
        await("Sensitive check switch off") { !r.settings.value.policy.checkSensitiveContent }
        Assert.assertFalse(decodeSettings(r.vault.read("settings")!!).policy.checkSensitiveContent)
        device.waitForIdle(3000); SystemClock.sleep(250) // Let the switch animation settle for visual evidence.
        capture("sensitive-checks-off")
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity --ei inputType 129")
        Assert.assertTrue(device.wait(Until.hasObject(By.desc("Fixture input 129 large false")), 10000))
        runBlocking {
            r.gateway.start()
            val screen = JsonCodec.decodeFromString<Screen>(r.gateway.run(Action(Op.OBSERVE, pkg)).content)
            Assert.assertFalse(screen.sensitive)
            Assert.assertNotNull(r.gateway.run(Action(Op.SCREENSHOT, pkg, screen.id)).image)
            val field = screen.nodes.single { it.editable }
            Assert.assertEquals("dispatched", r.gateway.run(Action(Op.TEXT, pkg, screen.id, field.ref, "Synthetic value")).status)
        }
        main { r.setSensitiveContentChecks(true) }
        Assert.assertTrue(decodeSettings(r.vault.read("settings")!!).policy.checkSensitiveContent)
        runBlocking {
            r.gateway.start()
            try { r.gateway.run(Action(Op.OBSERVE, pkg)); Assert.fail("On must block detected credential screen") }
            catch (e: SafeFailure) { Assert.assertEquals("manual_secret", e.code) }
        }
        // Android's protected-window restriction remains independent of the local opt-out.
        main { r.setSensitiveContentChecks(false) }
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity --es documentMode secure")
        Assert.assertTrue(device.wait(Until.hasObject(By.desc("Document fixture secure")), 10000))
        if (android.os.Build.VERSION.SDK_INT >= 34) runBlocking {
            r.gateway.start()
            val screen = JsonCodec.decodeFromString<Screen>(r.gateway.run(Action(Op.OBSERVE, pkg)).content)
            try { r.gateway.run(Action(Op.SCREENSHOT, pkg, screen.id)); Assert.fail("Android secure window still applies") }
            catch (e: SafeFailure) { Assert.assertEquals("secure_window", e.code) }
        }
    }
    @Test fun livePopupPerformsAndVerifiesTapAndTypingWithCurrentImages() {
        Assume.assumeTrue("Account owner opted in", InstrumentationRegistry.getArguments().getString("liveChatGpt") == "true")
        Assert.assertEquals(ProviderKind.CHATGPT, r.settings.value.profiles.single { it.id == r.settings.value.selected }.kind)
        device.executeShellCommand("am start -W --activity-clear-task -n $pkg/.FixtureActivity")
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Counter: 0")), 10000))
        val before = r.archive.value.audits.count { it.operation == "SCREENSHOT" && it.status == "captured" }
        prompt()
        input().text = "In MagicPhone Practice tap Add one exactly once and type Popup live verified into the Ordinary text field. Verify Counter: 1 and the entered text, then finish. Do not operate any other app."
        device.findObject(By.text(text(R.string.send))).click()
        val deadline = SystemClock.elapsedRealtime() + 180000
        while (r.agent.state.value !in setOf(RunState.COMPLETED, RunState.FAILED) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        Assert.assertEquals("error=${r.agent.error.value}; actions=${r.agent.actions.value}", RunState.COMPLETED, r.agent.state.value)
        Assert.assertTrue(device.hasObject(By.text("Counter: 1")))
        Assert.assertTrue(device.hasObject(By.text("Popup live verified")))
        Assert.assertTrue(r.archive.value.audits.count { it.operation == "SCREENSHOT" && it.status == "captured" } - before >= 3)
        capture("live-popup-actions-completed")
        println("Live popup tap/type verified; modelCalls=${r.agent.metrics.value.modelCalls}; shots=${r.archive.value.audits.count { it.operation == "SCREENSHOT" && it.status == "captured" } - before}")
    }
    @Test fun installedRecognizerFromPopupReturnsToUnderlyingScreen() {
        val component = device.executeShellCommand("cmd package resolve-activity --brief -a android.speech.action.RECOGNIZE_SPEECH")
            .lineSequence().map { it.trim() }.lastOrNull { it.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) }
        val provider = component?.substringBefore('/')
        Assume.assumeTrue("Installed speech provider required", provider != null && provider != "android")
        prompt(); input().text = "Keep popup draft"
        device.findObject(By.desc(text(R.string.voice_input)).pkg(context.packageName)).click()
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg(provider!!)), 10000))
        repeat(3) { if (input() == null) { device.pressBack(); device.waitForIdle(3000) } }
        await("Real recognizer canceled to same draft and screen") { input()?.text == "Keep popup draft" && device.hasObject(By.pkg(pkg)) }
        Assert.assertEquals(RunState.IDLE, r.agent.state.value)
    }
}
