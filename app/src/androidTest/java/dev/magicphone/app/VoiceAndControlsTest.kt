// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import dev.magicphone.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import org.junit.*
import org.junit.runner.RunWith

/** No account changes or cloud calls. Recognition results are controlled Android activity results. */
@RunWith(AndroidJUnit4::class)
class VoiceAndControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private lateinit var device: UiDevice
    private lateinit var original: Archive
    private var conversation: String? = null
    private var profile = ""
    private var shortcuts = emptyMap<String, String>()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        Assert.assertTrue(message, check())
    }
    private fun input() = device.findObject(By.clazz("android.widget.EditText").pkg(context.packageName))
    private fun open() {
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Composer visible") { input() != null }
    }
    private fun mic() {
        device.wait(Until.findObject(By.desc(context.getString(R.string.voice_input))), 10000).click()
    }
    private fun notification() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == TaskNotifications.ID }?.notification
    private fun awaitAction(label: Int) = await("Notification action ${context.getString(label)}") {
        notification()?.actions?.any { it.title.toString() == context.getString(label) } == true
    }
    private fun tapNotificationAction(label: Int) {
        device.openNotification()
        val text = context.getString(label)
        // Expand the actual system notification if its actions are initially collapsed.
        if (!device.hasObject(By.text(text)))
            device.findObject(By.descContains("Expand"))?.click()
        val action = device.wait(Until.findObject(By.text(text)), 10000)
        Assert.assertNotNull("Visible notification action $text", action)
        action.click()
    }
    private fun pendingProvider() = object : ModelProvider {
        override val supportsImages = false
        override suspend fun models() = emptyList<ModelChoice>()
        override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
            awaitCancellation()
        }
    }

    @Before fun setup() {
        Assert.assertTrue(android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        device = UiDevice.getInstance(instrumentation)
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val component = "${context.packageName}/${context.packageName}.PhoneService"
        val enabled = device.executeShellCommand("settings get secure enabled_accessibility_services").trim().split(":")
        Assert.assertTrue("Service must already be enabled", component in enabled)
        shortcuts = listOf("accessibility_button_targets", "accessibility_button_mode", "accessibility_button_target_component")
            .associateWith { device.executeShellCommand("settings get secure $it").trim() }
        original = r.archive.value
        conversation = r.current.value
        profile = r.settings.value.selected
        main { r.newConversation(); r.agent.clearView() }
        open()
        // Instrumentation replaces the target process; rebind this dedicated emulator's service.
        if (r.phone == null) {
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabled.filter { it != component }.joinToString(":").ifEmpty { "null" }}")
            SystemClock.sleep(400)
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabled.joinToString(":")}")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
        }
        await("Service connected") { r.phone != null }
        device.executeShellCommand("settings put secure accessibility_button_targets $component")
        device.executeShellCommand("settings put secure accessibility_button_mode ${if (android.os.Build.VERSION.SDK_INT >= 31) 1 else 0}")
        device.executeShellCommand("settings put secure accessibility_button_target_component $component")
    }

    @After fun restore() {
        main { r.stop(); r.agent.clearView(); r.saveArchive(original); r.current.value = conversation }
        runBlocking { r.flushHistory() }
        for ((key, value) in shortcuts) {
            Assert.assertTrue(value.matches(Regex("[A-Za-z0-9_./:]*")))
            if (value == "null") device.executeShellCommand("settings delete secure $key")
            else device.executeShellCommand("settings put secure $key $value")
        }
        Assert.assertEquals(profile, r.settings.value.selected)
        device.pressBack()
    }

    @Test fun voiceAppendsGreekResultAndCancellationPreservesDraft() {
        input().text = "Please"
        fun result(code: Int, text: String) {
            val monitor = instrumentation.addMonitor(IntentFilter(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                Instrumentation.ActivityResult(code, Intent().putStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))), true)
            try {
                mic()
                await("Recognizer was requested") { monitor.hits == 1 }
                instrumentation.waitForIdleSync()
            } finally { instrumentation.removeMonitor(monitor) }
        }
        result(Activity.RESULT_OK, "άνοιξε την εφαρμογή")
        await("Transcript appended") { input()?.text == "Please άνοιξε την εφαρμογή" }
        Assert.assertEquals(RunState.IDLE, r.agent.state.value)
        Assert.assertTrue(r.archive.value.conversations.find { it.id == r.current.value }?.messages.isNullOrEmpty())
        result(Activity.RESULT_CANCELED, "ignored")
        Assert.assertEquals("Please άνοιξε την εφαρμογή", input().text)
        main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>().single().recreate() }
        await("Dictated draft survives recreation") { input()?.text == "Please άνοιξε την εφαρμογή" }
        Assert.assertEquals(RunState.IDLE, r.agent.state.value)
        println("MagicPhone voice: controlled Greek activity result appended to draft; cancel and recreation preserve it; no task or model call")
    }

    @Test fun voiceContractIsFreeFormBoundedAndDoesNotShareTaskContext() {
        val contract = VoiceInput()
        val intent = contract.createIntent(context, Unit)
        Assert.assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        Assert.assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        Assert.assertEquals(setOf(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.EXTRA_PROMPT, RecognizerIntent.EXTRA_MAX_RESULTS), intent.extras!!.keySet())
        Assert.assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent()))
        Assert.assertEquals("", contract.parseResult(Activity.RESULT_OK, Intent()))
        Assert.assertEquals(8000, contract.parseResult(Activity.RESULT_OK,
            Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("x".repeat(12000))))!!.length)
    }

    @Test fun installedAndroidRecognizerOpensAndCanBeCanceled() {
        // Stock CI images may have no speech activity. Resolve the installed provider
        // through the test shell instead of assuming the manually provisioned QA package.
        val component = device.executeShellCommand(
            "cmd package resolve-activity --brief -a android.speech.action.RECOGNIZE_SPEECH")
            .lineSequence().map { it.trim() }.lastOrNull { it.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) }
        val providerPackage = component?.substringBefore('/')
        Assume.assumeTrue("No installed Android speech recognition activity on this image",
            providerPackage != null && providerPackage != "android")
        input().text = "Keep this unsent draft"
        mic()
        Assert.assertTrue("Installed Android speech provider opened",
            device.wait(Until.hasObject(By.pkg(providerPackage!!)), 10000))
        repeat(3) {
            if (input() == null) { device.pressBack(); device.waitForIdle(3000) }
        }
        await("Cancel returns to unchanged draft") { input()?.text == "Keep this unsent draft" }
        Assert.assertEquals(RunState.IDLE, r.agent.state.value)
        println("MagicPhone voice: real installed Android speech activity opened and canceled; acoustic recognition was not measured")
    }

    @Test fun ongoingNotificationPausesResumesStopsWithNoFloatingBar() {
        Assert.assertTrue("Notification permission required for this device test", TaskNotifications.enabled(context))
        main { r.agent.start(r.scope, pendingProvider(), "Local control test") }
        awaitAction(R.string.pause)
        context.startActivity(context.packageManager.getLaunchIntentForPackage("dev.magicphone.fixture")!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("Fixture visible") { device.hasObject(By.pkg("dev.magicphone.fixture")) }
        Assert.assertTrue("No app overlay control window", r.phone!!.windows.none {
            it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY })
        Assert.assertTrue(notification()!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
        Assert.assertEquals(Notification.VISIBILITY_SECRET, notification()!!.visibility)
        tapNotificationAction(R.string.pause)
        await("Paused from shade") { r.agent.state.value == RunState.PAUSED }
        awaitAction(R.string.resume)
        tapNotificationAction(R.string.resume)
        await("Resumed from shade") { r.agent.state.value == RunState.PLANNING }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            await("Resume closes notification shade") { device.hasObject(By.pkg("dev.magicphone.fixture")) }
        } else {
            // Android 11 has no GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE. A user closes
            // the shade manually; Pause/Resume/Stop state assertions still run below.
            device.pressBack()
            await("Fixture visible after closing shade") { device.hasObject(By.pkg("dev.magicphone.fixture")) }
        }
        awaitAction(R.string.stop)
        tapNotificationAction(R.string.stop)
        await("Stopped from shade") { r.agent.state.value == RunState.STOPPED }
        await("Notification stays without obsolete actions") { notification() != null && notification()!!.actions.isNullOrEmpty() }
        device.pressBack()
        println("MagicPhone controls: real notification shade Pause/Resume/Stop; ongoing flag; no floating control window; notification remains after Stop")
    }

    @Test fun backgroundShortcutPausesActiveRunAndNotificationReturnsToDraft() {
        input().text = "Keep my correction"
        val current = r.current.value
        main { r.agent.start(r.scope, pendingProvider(), "Local shortcut test") }
        awaitAction(R.string.pause)
        repeat(3) {
            device.pressHome()
            val shortcut = device.wait(Until.findObject(By.res("com.android.systemui", "accessibility_button")),
                if (android.os.Build.VERSION.SDK_INT < 31) 10000 else 500)
                ?: device.wait(Until.findObject(By.res("com.android.systemui", "accessibility_floating_menu")), 10000)
                ?: device.wait(Until.findObject(By.desc("MagicPhone").pkg("com.android.systemui")), 5000)
            Assert.assertNotNull("System accessibility button", shortcut)
            shortcut.click()
            await("Current draft focused from background") { input()?.text == "Keep my correction" && input()?.isFocused == true }
            Assert.assertTrue("Keyboard visible", device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 5000))
            Assert.assertEquals(RunState.PAUSED, r.agent.state.value)
            Assert.assertEquals(current, r.current.value)
        }
        device.pressHome()
        device.openNotification()
        device.waitForIdle(5000)
        val notificationTitle = device.findObject(UiSelector().resourceId("android:id/title")
            .text(context.getString(R.string.app_name)))
        Assert.assertTrue(notificationTitle.waitForExists(10000))
        notificationTitle.click()
        await("Notification opens same draft") { input()?.text == "Keep my correction" && input()?.isFocused == true }
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 5000))
        Assert.assertEquals(current, r.current.value)
        Assert.assertEquals(RunState.PAUSED, r.agent.state.value)
        println("MagicPhone shortcut: three background returns while running; current draft and keyboard; task pauses; notification tap returns to same chat")
    }
}
