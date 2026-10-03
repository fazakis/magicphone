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

/** Opt-in public-page Chrome navigation on a dedicated emulator; no account actions. */
@RunWith(AndroidJUnit4::class)
class ChromeNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private val pkg = "dev.magicphone.fixture"
    private lateinit var device: UiDevice
    private lateinit var settings: Settings
    private lateinit var archive: Archive
    private var selected: String? = null
    private val dir get() = File(context.getExternalFilesDir(null), "chrome-027").apply { mkdirs() }
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
        Assume.assumeTrue("Public Chrome navigation opted in", InstrumentationRegistry.getArguments().getString("chromeNavigation") == "true")
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
        if (!::settings.isInitialized) return
        main { r.stop(); r.agent.clearView(); r.saveSettings(settings); r.saveArchive(archive); r.current.value = selected }
        runBlocking { r.flushHistory() }
        device.pressBack()
    }
    @Test fun chromeAddressBarRemainsReadableAcrossPopupActions() {
        val chrome = "com.android.chrome"
        device.executeShellCommand("am start -W -a android.intent.action.VIEW -d https://magicphone.org -p $chrome")
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg(chrome)), 15000))
        // Only the public test page is used; no account action is requested.
        SystemClock.sleep(1800)
        var reads = 0
        runBlocking {
            r.gateway.start()
            repeat(3) {
                try {
                    val result = r.gateway.run(Action(Op.OBSERVE, chrome))
                    val screen = JsonCodec.decodeFromString<Screen>(result.content)
                    reads++
                    println("Chrome observation nodes=${screen.nodes.size} focused=${screen.focused} mixed=${screen.mixed} partial=${screen.partial} captureReady=${screen.captureReady}")
                    val image = r.gateway.run(Action(Op.SCREENSHOT, chrome, screen.id))
                    println("Chrome screenshot=${image.status}")
                } catch (e: SafeFailure) { println("Chrome read failure=${e.code}; inspection=${r.phone?.inspectionDiagnostics}") }
                delay(250)
            }
        }
        Assert.assertEquals("Stable Chrome must be readable", 3, reads)
        var rounds = 0
        val p = object : ModelProvider {
            override val supportsImages = true
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                // Read observations from matched tool results, no raw requests/results are logged.
                val results = input.mapNotNull { (it as? JsonObject)?.get("output")?.jsonPrimitive?.content }
                    .flatMap { runCatching { JsonCodec.decodeFromString<List<ToolResult>>(it) }.getOrDefault(emptyList()) }
                val screen = results.lastOrNull { it.status == "observed" }?.let { JsonCodec.decodeFromString<Screen>(it.content) }
                println("Chrome popup round=${rounds}; statuses=${results.map { it.status }}; inspection=${r.phone?.inspectionDiagnostics}")
                val action = when (rounds++) {
                    0 -> Action(Op.OBSERVE, chrome)
                    1 -> {
                        val s = screen ?: throw SafeFailure("fixture_missing_observation")
                        val address = s.nodes.firstOrNull { it.label.contains("magicphone.org", true) && it.bounds.top < s.height / 3 }
                            ?: throw SafeFailure("fixture_missing_address")
                        Action(Op.TAP, chrome, s.id, address.ref)
                    }
                    2 -> {
                        val s = screen ?: throw SafeFailure("fixture_missing_observation")
                        val field = s.nodes.firstOrNull { it.editable } ?: throw SafeFailure("fixture_missing_editor")
                        Action(Op.TEXT, chrome, s.id, field.ref, "https://magicphone.org/#features")
                    }
                    3 -> {
                        Assert.assertTrue("Typed URL is present in the latest observation", screen!!.nodes.any { it.editable && it.label == "https://magicphone.org/#features" })
                        Action(Op.BACK, chrome, screen.id)
                    }
                    else -> Action(Op.COMPLETE, text = "Chrome address entry verified")
                }
                return Reply("", listOf(Call(id(), listOf(action))), emptyList())
            }
        }
        main { r.popupConversation.value = r.current.value; r.agent.start(r.scope, p, "Use Chrome", screenContext = chrome, captureScreen = true) }
        val deadline = SystemClock.elapsedRealtime() + 30000
        while (r.agent.state.value !in setOf(RunState.COMPLETED, RunState.FAILED) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        println("Chrome final=${r.agent.state.value}; error=${r.agent.error.value}; actions=${r.agent.actions.value}; inspection=${r.phone?.inspectionDiagnostics}")
        capture("chrome-action-result")
        Assert.assertEquals(RunState.COMPLETED, r.agent.state.value)
        Assert.assertEquals(1, r.agent.actions.value.count { it.operation == Op.TEXT && it.status == "dispatched" })
    }
    @Test fun livePopupNavigatesChromeToPublicSite() {
        Assume.assumeTrue("Live ChatGPT opted in", InstrumentationRegistry.getArguments().getString("liveChatGpt") == "true")
        Assert.assertEquals(ProviderKind.CHATGPT, r.settings.value.profiles.single { it.id == r.settings.value.selected }.kind)
        val chrome = "com.android.chrome"
        device.executeShellCommand("am start -W -a android.intent.action.VIEW -d https://magicphone.org -p $chrome")
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg(chrome)), 15000))
        SystemClock.sleep(1800)
        prompt()
        input().text = "In Chrome, navigate to https://example.org using the address bar. Verify that Example Domain is visible and then finish. Do not open any other app, sign in or operate an account."
        device.findObject(By.text(text(R.string.send))).click()
        val deadline = SystemClock.elapsedRealtime() + 180000
        while (r.agent.state.value !in setOf(RunState.COMPLETED, RunState.FAILED, RunState.WAITING_USER) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        println("Live Chrome final=${r.agent.state.value}; error=${r.agent.error.value}; calls=${r.agent.metrics.value.modelCalls}; actions=${r.agent.actions.value}; inspection=${r.phone?.inspectionDiagnostics}")
        capture("live-chrome-result")
        Assert.assertEquals("error=${r.agent.error.value}; actions=${r.agent.actions.value}", RunState.COMPLETED, r.agent.state.value)
        Assert.assertTrue(device.hasObject(By.text("Example Domain")))
        Assert.assertTrue(r.agent.actions.value.any { it.operation == Op.TEXT && it.status == "dispatched" })
    }

}
