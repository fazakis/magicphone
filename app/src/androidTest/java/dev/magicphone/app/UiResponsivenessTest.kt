// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Intent
import android.os.*
import android.view.FrameMetrics
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import dev.magicphone.core.*
import dev.magicphone.core.Message
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.util.Collections

/** Local UI only: preserves the signed-in profile and makes no model/network requests. */
@RunWith(AndroidJUnit4::class)
class UiResponsivenessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private lateinit var device: UiDevice
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15000
        while (!check() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        Assert.assertTrue(check())
    }
    private fun clickText(label: Int) {
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) {
            device.pressBack()
            device.wait(Until.gone(By.pkg("com.google.android.inputmethod.latin")), 5000)
        }
        device.waitForIdle(5000)
        val button = device.findObject(UiSelector().text(context.getString(label)))
        Assert.assertTrue(button.waitForExists(10000))
        button.click()
    }
    private fun open() {
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Assert.assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.task))), 15000))
    }
    private fun flushIfAvailable() {
        // The same benchmark also runs against 0.1.2, which saved synchronously.
        if (context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode >= 4) runBlocking { r.flushHistory() }
    }

    @Before fun setup() {
        Assert.assertTrue(android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        device = UiDevice.getInstance(instrumentation)
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        main { r.stop() }
    }

    @Test fun largeHistoryUiAndPersistence() {
        val original = r.archive.value
        val selected = r.current.value
        val profile = r.settings.value.selected
        val sample = "Local UI performance fixture. ".repeat(8)
        val messages = List(60) { Message(role = if (it % 2 == 0) "user" else "assistant", text = sample) }
        val active = Conversation(title = "UI performance active", messages = messages)
        val synthetic = List(180) { Conversation(title = "UI performance fixture $it", messages = messages.take(20)) } + active
        val frames = Collections.synchronizedList(mutableListOf<Long>())
        val worker = HandlerThread("ui-test-frames").apply { start() }
        var activity: MainActivity? = null
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            frames += frame.getMetric(FrameMetrics.TOTAL_DURATION)
        }
        try {
            main { r.saveArchive(original.copy(conversations = (original.conversations + synthetic).takeLast(500))); r.current.value = active.id }
            flushIfAvailable()
            open()
            main {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single()
                activity!!.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper))
            }
            val writes = mutableListOf<Long>()
            repeat(12) {
                main {
                    val begin = SystemClock.elapsedRealtimeNanos()
                    r.addMessage("assistant", "Local benchmark update $it")
                    writes += SystemClock.elapsedRealtimeNanos() - begin
                }
                SystemClock.sleep(60)
            }
            repeat(3) {
                clickText(R.string.history)
                val visible = device.wait(Until.hasObject(By.textStartsWith("UI performance")), 15000)
                Assert.assertTrue("History tab: pass=$it; foreground=${device.currentPackageName}; " +
                    "keyboard=${device.hasObject(By.pkg("com.google.android.inputmethod.latin"))}; " +
                    "notice=${r.notice.value}; count=${r.archive.value.conversations.size}; " +
                    "search=${device.hasObject(By.text(context.getString(R.string.search)))}", visible)
                device.swipe(540, 1800, 540, 650, 30)
                device.swipe(540, 650, 540, 1800, 30)
                clickText(R.string.task)
            }
            flushIfAvailable()
            val stored = Archives.read(r.vault.read("history")!!.toByteArray())
            Assert.assertEquals(72, stored.conversations.single { it.id == active.id }.messages.size)
            val timings = synchronized(frames) { frames.sorted() }
            Assert.assertTrue("Rendered frames captured", timings.isNotEmpty())
            val sortedWrites = writes.sorted()
            fun ms(v: Long) = v / 1_000_000.0
            println("MagicPhone UI benchmark: version=${context.packageManager.getPackageInfo(context.packageName, 0).versionName};history=181;writes=12;" +
                "mainWriteMedianMs=${ms(sortedWrites[sortedWrites.size / 2])};" +
                "mainWriteMaxMs=${ms(sortedWrites.last())};frames=${timings.size};" +
                "frameP95Ms=${ms(timings[((timings.size - 1) * .95).toInt()])};" +
                "framesOver32Ms=${timings.count { it > 32_000_000 }}")
            Assert.assertEquals(profile, r.settings.value.selected)
        } finally {
            main {
                activity?.window?.removeOnFrameMetricsAvailableListener(listener)
                r.saveArchive(original)
                r.current.value = selected
            }
            flushIfAvailable()
            worker.quitSafely()
        }
    }

    @Test fun accessibilityShortcutReturnsToCurrentDraftAndShowsKeyboard() {
        val original = r.archive.value
        val selected = r.current.value
        val profile = r.settings.value.selected
        val component = "${context.packageName}/${PhoneService::class.java.name}"
        val enabled = device.executeShellCommand("settings get secure enabled_accessibility_services").trim().split(':')
        Assert.assertTrue(component in enabled)
        Assert.assertTrue(enabled.all { it.matches(Regex("[A-Za-z0-9_./]+")) })
        val keys = listOf("accessibility_button_targets", "accessibility_button_mode")
        val previous = keys.associateWith { device.executeShellCommand("settings get secure $it").trim() }
        try {
            main { r.newConversation() }
            val conversation = r.current.value
            open()
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabled.filter { it != component }.joinToString(":").ifEmpty { "null" }}")
            SystemClock.sleep(400)
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabled.joinToString(":")}")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
            await { r.connected.value }
            device.executeShellCommand("settings put secure accessibility_button_targets $component")
            device.executeShellCommand("settings put secure accessibility_button_mode 1")
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)
            field.click()
            field.text = "Keep this draft"
            Assert.assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 5000))
            device.pressBack()
            clickText(R.string.settings)
            // The real Android floating accessibility shortcut, not an app-side test receiver.
            repeat(2) { index ->
                if (index == 1) {
                    context.startActivity(context.packageManager.getLaunchIntentForPackage("dev.magicphone.fixture")!!
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Assert.assertTrue(device.wait(Until.hasObject(By.pkg("dev.magicphone.fixture")), 5000))
                }
                device.waitForIdle(5000)
                val shortcut = device.wait(Until.findObject(By.res("com.android.systemui", "accessibility_floating_menu")), 10000)
                    ?: device.wait(Until.findObject(By.descContains("MagicPhone").pkg("com.android.systemui")), 3000)
                Assert.assertNotNull("Android accessibility shortcut is visible", shortcut)
                val namedButton = device.findObject(By.descContains("MagicPhone").pkg("com.android.systemui"))
                val button = namedButton ?: shortcut
                println("Shortcut fixture target: resource=${button.resourceName}; bounds=${button.visibleBounds}; children=${button.childCount}; named=${namedButton != null}")
                button.click()
                val input = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(context.packageName)), 10000)
                Assert.assertNotNull("Shortcut chat input; foreground=${device.currentPackageName}; connected=${r.connected.value}", input)
                Assert.assertEquals("Keep this draft", input.text)
                await { device.findObject(By.clazz("android.widget.EditText").pkg(context.packageName))?.isFocused == true }
                Assert.assertTrue("Full onscreen keyboard is visible", device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 5000))
                Assert.assertEquals(conversation, r.current.value)
                Assert.assertEquals(RunState.IDLE, r.agent.state.value)
                device.pressBack()
            }
            main {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single().recreate()
            }
            await { device.findObject(By.clazz("android.widget.EditText").pkg(context.packageName))?.text == "Keep this draft" }
            await { device.findObject(By.clazz("android.widget.EditText").pkg(context.packageName))?.isFocused == true }
            Assert.assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 5000))
            Assert.assertEquals(conversation, r.current.value)
            Assert.assertEquals(profile, r.settings.value.selected)
            println("MagicPhone shortcut: real Android button returned to current draft twice; input focused; keyboard visible; recreation preserved draft/current chat; no task started")
        } finally {
            for ((key, value) in previous) {
                Assert.assertTrue(value.matches(Regex("[A-Za-z0-9_./:]*")))
                if (value == "null") device.executeShellCommand("settings delete secure $key")
                else device.executeShellCommand("settings put secure $key $value")
            }
            main { r.saveArchive(original); r.current.value = selected }
            flushIfAvailable()
        }
    }
}
