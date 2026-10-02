// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.magicphone.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import org.junit.*
import org.junit.runner.RunWith

/** Dedicated emulator and local fixture only. Never changes or calls the account provider. */
@RunWith(AndroidJUnit4::class)
class ScreenReadRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private val pkg = "dev.magicphone.fixture"
    private lateinit var device: UiDevice
    private lateinit var settings: Settings
    private lateinit var archive: Archive
    private var selected: String? = null
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
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
            r.saveSettings(settings.copy(showTaskResultBubbles = false, policy = PolicyConfig(
                apps = mapOf(pkg to AppRule(true, true)), allowAllApps = true)))
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
    private fun fixture(inputType: Int = 1, large: Boolean = false) {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("inputType", inputType).putExtra("largeTree", large))
        // Counter: 0 also exists on the outgoing activity; wait for this exact fixture.
        Assert.assertTrue(device.wait(Until.hasObject(By.desc("Fixture input $inputType large $large")), 10000))
        Assert.assertTrue(device.hasObject(By.text("Counter: 0")))
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) device.pressBack()
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg(pkg)), 5000))
    }
    private fun keyboard() {
        device.findObject(By.res(pkg, "1003"))?.click()
            ?: device.findObject(By.clazz("android.widget.EditText").pkg(pkg)).click()
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 5000))
    }
    private fun inspect(): Screen {
        var screen: Screen? = null
        main { screen = r.phone!!.inspect(pkg) }
        return screen!!
    }
    @Test fun keyboardReadContinuesWhileNoticeDismissesAfterTwoSeconds() {
        fixture(); keyboard()
        val screen = inspect()
        Assert.assertFalse(r.phone!!.inspectionDiagnostics, screen.mixed)
        Assert.assertTrue(screen.partial)
        Assert.assertTrue(screen.nodes.any { it.label == "Counter: 0" })
        Assert.assertTrue(screen.protectedRects.isNotEmpty())
        for (node in screen.nodes) Assert.assertFalse(screen.protectedRects.any {
            it.left < node.bounds.right && it.right > node.bounds.left && it.top < node.bounds.bottom && it.bottom > node.bounds.top
        })
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val provider = object : ModelProvider {
            override val supportsImages = true
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                Assert.assertTrue(input.toString().contains("Counter: 0"))
                Assert.assertTrue(input.toString().contains("partial_screen_image_omitted"))
                Assert.assertFalse(input.toString().contains("input_image"))
                entered.complete(Unit)
                finish.await()
                return Reply("The visible counter is 0.", emptyList(), emptyList())
            }
        }
        main { r.agent.start(r.scope, provider, "Read this screen", screenContext = pkg) }
        runBlocking { withTimeout(10000) { entered.await() } }
        await("Notice visible while model is already running") { r.screenReadNotice.visible.value }
        Assert.assertEquals(RunState.PLANNING, r.agent.state.value)
        Assert.assertNull(r.resultRequest.value)
        Assert.assertTrue(r.notice.value.isEmpty())
        val shown = SystemClock.elapsedRealtime()
        await("Notice auto-dismissed") { !r.screenReadNotice.visible.value }
        Assert.assertTrue("Dismiss within two seconds plus scheduling margin", SystemClock.elapsedRealtime() - shown <= 2300)
        Assert.assertEquals(RunState.PLANNING, r.agent.state.value)
        Assert.assertEquals("", r.agent.error.value)
        finish.complete(Unit)
        await("Same task completed without restart") { r.agent.state.value == RunState.COMPLETED }
        Assert.assertTrue(r.agent.question.value.isEmpty())
        Assert.assertFalse(r.agent.actions.value.any { it.operation == Op.SCREENSHOT })
    }
    @Test fun keyboardAllowsVisibleTargetButRejectsKeyboardCoordinatesAndCapture() {
        fixture(); keyboard()
        runBlocking {
            r.gateway.start()
            val result = r.gateway.run(Action(Op.OBSERVE, pkg))
            val screen = JsonCodec.decodeFromString<Screen>(result.content)
            val key = inspect().protectedRects.first()
            try {
                r.gateway.run(Action(Op.TAP, pkg, screen.id, x = (key.left + key.right) / 2, y = (key.top + key.bottom) / 2))
                Assert.fail("Keyboard coordinates must not execute")
            } catch (e: SafeFailure) { Assert.assertEquals("protected_control", e.code) }
            try {
                r.gateway.run(Action(Op.SCREENSHOT, pkg, screen.id))
                Assert.fail("IME content must not be captured")
            } catch (e: SafeFailure) { Assert.assertEquals("capture_uncertain", e.code) }
            val button = screen.nodes.single { it.label == "Add one · Προσθήκη" }
            Assert.assertEquals("dispatched", r.gateway.run(Action(Op.TAP, pkg, screen.id, button.ref)).status)
            val after = r.gateway.run(Action(Op.OBSERVE, pkg))
            Assert.assertTrue(after.content.contains("Counter: 1"))
            r.gateway.stop()
        }
    }
    @Test fun ordinaryWebInputsAndLargeTreeAreReadableButPasswordsStayManual() {
        // These used to trip the 0x80 password bit check (web edit and web email).
        for (type in listOf(0xa1, 0xd1)) {
            fixture(type)
            val screen = inspect()
            Assert.assertFalse("Ordinary type $type", screen.sensitive)
            Assert.assertTrue(screen.nodes.any { it.editable && !it.sensitive })
            runBlocking {
                r.gateway.start()
                Assert.assertEquals("observed", r.gateway.run(Action(Op.OBSERVE, pkg)).status)
                r.gateway.stop()
            }
        }
        fixture(large = true)
        val large = inspect()
        Assert.assertFalse(large.sensitive)
        Assert.assertTrue(large.partial)
        Assert.assertTrue(large.nodes.any { it.label == "Counter: 0" })
        runBlocking {
            r.gateway.start()
            Assert.assertEquals("observed", r.gateway.run(Action(Op.OBSERVE, pkg)).status)
            try { r.gateway.run(Action(Op.SCREENSHOT, pkg, large.id)); Assert.fail("Incomplete scan must not capture") }
            catch (e: SafeFailure) { Assert.assertEquals("capture_uncertain", e.code) }
            r.gateway.stop()
        }
        fixture(0x81)
        Assert.assertTrue(inspect().sensitive)
        runBlocking {
            r.gateway.start()
            try { r.gateway.run(Action(Op.OBSERVE, pkg)); Assert.fail("Password field stays manual") }
            catch (e: SafeFailure) { Assert.assertEquals("manual_secret", e.code) }
            r.gateway.stop()
        }
    }
}
