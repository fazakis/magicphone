// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
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

/** Isolated questions and fixture screens only; never changes the signed-in provider. */
@RunWith(AndroidJUnit4::class)
class InputBubbleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private lateinit var device: UiDevice
    private lateinit var originalSettings: Settings
    private lateinit var original: Archive
    private var selected: String? = null
    private var profile = ""
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        Assert.assertTrue(message, predicate())
    }
    private fun input() = device.findObject(By.clazz("android.widget.EditText").pkg(context.packageName))
    private fun bubble() = device.findObject(By.text(context.getString(R.string.input_bubble_reply)).pkg(context.packageName))
    private fun notification() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.single { it.id == TaskNotifications.ID }.notification
    private fun fixture() {
        context.startActivity(context.packageManager.getLaunchIntentForPackage("dev.magicphone.fixture")!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg("dev.magicphone.fixture")), 10000))
    }
    private class AskingProvider(val question: String, val tool: Boolean) : ModelProvider {
        override val supportsImages = false
        val ready = CompletableDeferred<Unit>()
        var receivedAnswer = false
        private var rounds = 0
        override suspend fun models() = emptyList<ModelChoice>()
        override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
            if (++rounds == 1) {
                ready.await()
                return if (tool) Reply("", listOf(Call(id(), listOf(Action(Op.ASK, text = question)))), emptyList())
                    else Reply(question, emptyList(), emptyList())
            }
            receivedAnswer = input.any { it.toString().contains("Use the blue option") }
            return Reply("", listOf(Call(id(), listOf(Action(Op.COMPLETE, text = "Reply received")))), emptyList())
        }
    }
    private fun ask(tool: Boolean, text: String = "Which option should I choose? Διάλεξε μια επιλογή."): AskingProvider {
        val provider = AskingProvider(text, tool)
        main { r.agent.start(r.scope, provider, "Local input bubble test") }
        provider.ready.complete(Unit)
        await("Question waiting") { r.agent.state.value == RunState.WAITING_USER && r.inputRequest.value != null }
        return provider
    }

    @Before fun setup() {
        Assert.assertTrue(android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        device = UiDevice.getInstance(instrumentation)
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        originalSettings = r.settings.value
        original = r.archive.value
        selected = r.current.value
        profile = r.settings.value.selected
        main { r.saveSettings(originalSettings.copy(showTaskResultBubbles = true)); r.newConversation() }
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Composer") { input() != null }
        val component = "${context.packageName}/${context.packageName}.PhoneService"
        val enabled = device.executeShellCommand("settings get secure enabled_accessibility_services").trim().split(":")
        Assert.assertTrue(component in enabled)
        if (r.phone == null) {
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabled.filter { it != component }.joinToString(":").ifEmpty { "null" }}")
            SystemClock.sleep(400)
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabled.joinToString(":")}")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
        }
        await("Service connected") { r.connected.value }
    }

    @After fun restore() {
        main { r.stop(); r.agent.clearView(); r.saveSettings(originalSettings); r.saveArchive(original); r.current.value = selected }
        runBlocking { r.flushHistory() }
        Assert.assertEquals(profile, r.settings.value.selected)
        device.pressBack()
    }

    private fun answerFromBubble(tool: Boolean) {
        input().text = "Existing answer draft"
        val conversation = r.current.value
        fixture()
        val provider = ask(tool)
        await("Message bubble shown") { bubble() != null }
        Assert.assertTrue(device.hasObject(By.text(provider.question)))
        Assert.assertTrue(device.hasObject(By.pkg("dev.magicphone.fixture")))
        if (tool) Assert.assertTrue(device.takeScreenshot(java.io.File(context.filesDir, "qa-input-bubble.png")))
        bubble().click()
        await("Chat input focused") { input()?.isFocused == true }
        Assert.assertEquals("Existing answer draft", input().text)
        Assert.assertEquals(conversation, r.current.value)
        Assert.assertEquals(RunState.WAITING_USER, r.agent.state.value)
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 5000))
        Assert.assertTrue(r.archive.value.conversations.single { it.id == conversation }
            .messages.any { it.role == "assistant" && it.text == provider.question })
        await("Bubble gone in chat") { bubble() == null }
        main {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
            Assert.assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (tool) Assert.assertTrue(device.takeScreenshot(java.io.File(context.filesDir, "qa-screenshots-enabled.png")))
        input().text = "Use the blue option"
        device.wait(Until.findObject(By.text(context.getString(R.string.send))), 10000).click()
        await("Answer continues task without Resume") { r.agent.state.value == RunState.COMPLETED }
        Assert.assertTrue(provider.receivedAnswer)
        await("Question cleared") { r.inputRequest.value == null && r.agent.question.value.isEmpty() }
    }

    @Test fun explicitAskBubbleOpensFocusedChatAndSendContinues() = answerFromBubble(true)
    @Test fun textOnlyQuestionAlsoShowsBubbleAndSendContinues() = answerFromBubble(false)

    @Test fun foregroundQuestionStaysInlineAndBubbleDismissalDoesNotStopTask() {
        val provider = ask(false)
        await("Current chat visible") { r.visibleChat.value == r.current.value }
        Assert.assertNull(bubble())
        fixture()
        await("Bubble shown outside chat") { bubble() != null }
        device.findObject(By.desc(context.getString(R.string.dismiss)).pkg(context.packageName)).click()
        await("Bubble dismissed") { bubble() == null }
        main { r.phone!!.updateControls() }
        Assert.assertNull(bubble())
        Assert.assertEquals(RunState.WAITING_USER, r.agent.state.value)
        Assert.assertEquals(provider.question, r.inputRequest.value?.message)
        Assert.assertTrue(notification().extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("Which option"))
        main { r.stop() }
        await("Stop removes request") { r.inputRequest.value == null }
    }

    @Test fun savedQuestionNotificationTargetsItsOwnConversationAndLockHidesBubble() {
        fixture()
        val provider = ask(true)
        await("Bubble visible") { bubble() != null }
        await("Notification has request") { notification().extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("Which option") }
        val request = r.inputRequest.value!!
        val pendingChat = notification().contentIntent
        device.sleep()
        await("Bubble hidden on screen off") { bubble() == null }
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        main { r.saveSettings(originalSettings.copy(showTaskResultBubbles = true)); r.newConversation() }
        val other = r.current.value
        Assert.assertNotEquals(request.conversation, other)
        pendingChat.send()
        await("Saved notification returns to original conversation") { r.current.value == request.conversation && input()?.isFocused == true }
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 5000))
        Assert.assertTrue(r.archive.value.conversations.single { it.id == request.conversation }.messages.any { it.text == provider.question })
        Assert.assertEquals(RunState.IDLE, r.agent.state.value)
    }
    private fun resultBubble() = device.findObject(By.text(context.getString(R.string.task_result_open)).pkg(context.packageName))
    private fun tap(label: Int) {
        val text = context.getString(label)
        if (!device.hasObject(By.text(text))) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
        device.wait(Until.findObject(By.text(text)), 10000).click()
    }
    private fun screenshot(name: String) {
        device.waitForIdle(3000)
        SystemClock.sleep(200)
        Assert.assertTrue(device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "$name.png")))
    }
    private fun outcome(fail: Boolean = false): CompletableDeferred<Unit> {
        val ready = CompletableDeferred<Unit>()
        val provider = object : ModelProvider {
            override val supportsImages = false
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                ready.await()
                if (fail) throw SafeFailure("usage_limit")
                return Reply("", listOf(Call(id(), listOf(Action(Op.COMPLETE, text = "Practice actions finished.")))), emptyList())
            }
        }
        main { r.stop(); r.agent.start(r.scope, provider, "Local outcome test") }
        return ready
    }

    @Test fun completionBubbleOpensFocusedChatWithoutResumingAndDismissesPermanently() {
        Assert.assertTrue(decodeSettings("{}").showTaskResultBubbles)
        input().text = "Keep this draft"
        val conversation = r.current.value
        fixture()
        outcome().complete(Unit)
        await("Completion bubble") { resultBubble() != null }
        Assert.assertTrue(device.hasObject(By.text(context.getString(R.string.task_result_completed_title))))
        Assert.assertTrue(device.hasObject(By.text("Practice actions finished.")))
        Assert.assertEquals(RunState.COMPLETED, r.agent.state.value)
        screenshot("completion-bubble")
        resultBubble().click()
        await("Result opens focused chat") { input()?.isFocused == true && r.current.value == conversation }
        Assert.assertEquals("Keep this draft", input().text)
        Assert.assertEquals(RunState.COMPLETED, r.agent.state.value)
        Assert.assertTrue(r.archive.value.conversations.single { it.id == conversation }.messages.any { it.text == "Practice actions finished." })
        fixture()
        main { r.phone!!.updateControls() }
        Assert.assertNull(resultBubble())
        // A later run with the same summary still creates a new, visible result.
        outcome().complete(Unit)
        await("New run produces a new bubble") { resultBubble() != null }
        main { r.newConversation() }
        await("Changing chats clears old result") { resultBubble() == null && r.resultRequest.value == null }
    }

    @Test fun finalErrorIsLocalizedHiddenOnLockAndDismissible() {
        fixture()
        outcome(fail = true).complete(Unit)
        await("Final error bubble") { resultBubble() != null }
        Assert.assertTrue(device.hasObject(By.text(context.getString(R.string.task_result_failed_title))))
        Assert.assertTrue(device.hasObject(By.text(context.getString(R.string.error_limit))))
        Assert.assertFalse(device.hasObject(By.text("usage_limit")))
        screenshot("error-bubble")
        device.sleep()
        await("Results hidden on lock") { resultBubble() == null }
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        await("Result returns when unlocked") { resultBubble() != null }
        device.findObject(By.desc(context.getString(R.string.dismiss)).pkg(context.packageName)).click()
        await("Result dismissed") { resultBubble() == null }
        main { r.phone!!.updateControls() }
        Assert.assertNull(resultBubble())
        Assert.assertEquals(RunState.FAILED, r.agent.state.value)
        main { r.stop() }
        Assert.assertNull(r.resultRequest.value)
    }

    @Test fun resultSwitchPersistsDoesNotStopRunningTaskAndKeepsQuestionBubbles() {
        val gate = outcome(fail = true)
        tap(R.string.settings); tap(R.string.access_section)
        tap(R.string.task_result_bubbles)
        await("Result setting off") { !r.settings.value.showTaskResultBubbles }
        Assert.assertEquals(RunState.PLANNING, r.agent.state.value)
        Assert.assertFalse(decodeSettings(r.vault.read("settings")!!).showTaskResultBubbles)
        main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>().single().recreate() }
        await("Settings restored") { device.hasObject(By.text(context.getString(R.string.task_result_bubbles))) }
        screenshot("result-bubble-setting")
        fixture(); gate.complete(Unit)
        await("Failure without a result bubble") { r.agent.state.value == RunState.FAILED }
        main { r.phone!!.updateControls() }
        Assert.assertNull(resultBubble()); Assert.assertNull(r.resultRequest.value)
        ask(true)
        await("Questions remain enabled") { bubble() != null }
        main { r.stop(); r.saveSettings(r.settings.value.copy(showTaskResultBubbles = true)) }
        Assert.assertNull(r.resultRequest.value)
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun onboardingAndAutomaticAccessShowAutomationDisclaimerInBothLanguages() {
        val locales = context.getSystemService(android.app.LocaleManager::class.java)
        val originalLocales = locales.applicationLocales
        try {
            for (language in listOf("en", "el")) {
                main {
                    r.stop(); r.agent.clearView()
                    r.saveSettings(r.settings.value.copy(onboarded = false, selected = "", policy = r.settings.value.policy.copy(allowAllApps = false)))
                    locales.applicationLocales = android.os.LocaleList.forLanguageTags(language)
                }
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                val phrase = if (language == "en") "open-source automation tool" else "εργαλείο αυτοματισμού ανοικτού κώδικα"
                UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().textContains(phrase))
                await("Localized disclaimer visible") { device.hasObject(By.textContains(phrase)) }
                screenshot("disclaimer-onboarding-$language")
                val resources = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(language))
                }).resources
                fun localizedTap(id: Int) {
                    val text = resources.getString(id)
                    if (!device.hasObject(By.text(text))) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
                    device.wait(Until.findObject(By.text(text)), 10000).click()
                }
                localizedTap(R.string.understand)
                localizedTap(R.string.access_section)
                UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().textContains(phrase))
                await("Access disclaimer visible") { device.hasObject(By.textContains(phrase)) }
                screenshot("disclaimer-access-$language")
                localizedTap(R.string.all_apps_enable)
                await("Existing access control remains usable") { r.settings.value.policy.allowAllApps }
            }
        } finally { main { locales.applicationLocales = originalLocales } }
    }

    private fun openQuickPrompt() {
        // Same implementation as the real system shortcut, covered separately by shortcut tests.
        main { r.phone!!.quickPrompt.show() }
        await("Quick prompt over current app") { device.hasObject(By.text(context.getString(R.string.quick_prompt_title))) }
        await("Overlay input focused") { input()?.isFocused == true }
        Assert.assertTrue("Keyboard in overlay", device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 5000))
    }

    @Test fun currentScreenPromptSendsFromOverlayAndReturnsResultToFullChat() {
        main { r.configurePractice(); r.saveSettings(r.settings.value.copy(policy = r.settings.value.policy.copy(
            grants = listOf(Grant("dev.magicphone.fixture", setOf(Op.OPEN, Op.TAP), Long.MAX_VALUE))))) }
        fixture()
        val chat = r.current.value
        openQuickPrompt()
        Assert.assertTrue(device.hasObject(By.pkg("dev.magicphone.fixture")))
        input().text = "Work with this current practice screen"
        screenshot("current-screen-prompt")
        device.findObject(By.text(context.getString(R.string.send))).click()
        await("Overlay task finished: ${r.agent.error.value}") { r.agent.state.value in setOf(RunState.COMPLETED, RunState.FAILED) }
        Assert.assertEquals("error=${r.agent.error.value}", RunState.COMPLETED, r.agent.state.value)
        await("Overlay result shown") { resultBubble() != null }
        Assert.assertTrue(r.agent.actions.value.any { it.operation == Op.OBSERVE })
        Assert.assertTrue(device.hasObject(By.text(context.getString(R.string.read_aloud))))
        screenshot("current-screen-answer")
        resultBubble().click()
        await("Full conversation focused") { r.current.value == chat && input()?.isFocused == true }
        Assert.assertTrue(r.archive.value.conversations.single { it.id == chat }.messages.any { it.text == "Work with this current practice screen" })
        Assert.assertTrue(device.hasObject(By.text(context.getString(R.string.read_aloud))))
    }

    @Test fun currentScreenPromptCancelAndBlockedAppNeverReadScreen() {
        fixture(); openQuickPrompt()
        input().text = "Draft for this screen"
        val audits = r.archive.value.audits.size
        device.findObject(By.desc(context.getString(R.string.dismiss))).click()
        await("Prompt dismissed") { r.phone!!.quickPrompt.view == null }
        Assert.assertEquals(audits, r.archive.value.audits.size)
        Assert.assertEquals(RunState.IDLE, r.agent.state.value)
        main { r.configurePractice(); r.saveSettings(r.settings.value.copy(policy = r.settings.value.policy.copy(
            apps = mapOf("dev.magicphone.fixture" to AppRule(deny = true))))) }
        openQuickPrompt()
        Assert.assertEquals("Draft for this screen", input().text)
        device.findObject(By.text(context.getString(R.string.send))).click()
        await("Blocked current screen fails") { r.agent.state.value == RunState.FAILED }
        Assert.assertEquals("screen_context_changed", r.agent.error.value)
        Assert.assertFalse(r.agent.actions.value.any { it.operation == Op.OBSERVE || it.operation == Op.SCREENSHOT })
        Assert.assertEquals(0, r.agent.metrics.value.modelCalls)
    }

    @Test fun androidReadAloudStartsAndStopsFromAnswerBubble() {
        fixture()
        outcome().complete(Unit)
        await("Answer bubble") { resultBubble() != null }
        device.findObject(By.text(context.getString(R.string.read_aloud))).click()
        await("Android speech engine started playback") { r.speech.started.value }
        await("Stop reading visible") { device.hasObject(By.text(context.getString(R.string.stop_reading))) }
        device.findObject(By.text(context.getString(R.string.stop_reading))).click()
        await("Playback stopped") { r.speech.active.value == null && !r.speech.started.value }
        Assert.assertEquals(RunState.COMPLETED, r.agent.state.value)
        Assert.assertNotNull(resultBubble())
    }

    @Test fun liveCurrentScreenGreekAnswerFromExistingChatGptConnection() {
        Assume.assumeTrue("Explicit account-owner opt-in", InstrumentationRegistry.getArguments().getString("liveChatGpt") == "true")
        val profile = r.settings.value.profiles.single { it.id == r.settings.value.selected }
        Assert.assertEquals(ProviderKind.CHATGPT, profile.kind)
        main { r.saveSettings(r.settings.value.copy(policy = r.settings.value.policy.copy(allowAllApps = false,
            apps = mapOf("dev.magicphone.fixture" to AppRule(observe = true))))) }
        fixture(); openQuickPrompt()
        input().text = "Read the currently open screen. Translate the visible heading, description and button labels into Greek. Give the translation as your final answer. Do not tap, type, navigate, or change anything."
        device.findObject(By.text(context.getString(R.string.send))).click()
        val deadline = SystemClock.elapsedRealtime() + 180000
        while (r.agent.state.value !in setOf(RunState.COMPLETED, RunState.FAILED) && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        Assert.assertEquals("error=${r.agent.error.value}", RunState.COMPLETED, r.agent.state.value)
        await("Live Greek answer bubble") { resultBubble() != null }
        val answer = r.resultRequest.value!!.message
        Assert.assertTrue("Greek translation", answer.count { it in '\u0370'..'\u03ff' } > 30)
        Assert.assertTrue("Screenshot passed through Gateway", r.archive.value.audits.any { it.operation == Op.SCREENSHOT.name && it.status == "captured" })
        Assert.assertFalse("No mutating device actions", r.agent.actions.value.any { it.operation in setOf(Op.TAP, Op.TEXT, Op.OPEN, Op.BACK) })
        screenshot("live-current-screen-greek-answer")
        device.findObject(By.text(context.getString(R.string.read_aloud))).click()
        await("Greek speech playback started") { r.speech.started.value }
        device.findObject(By.text(context.getString(R.string.stop_reading))).click()
        await("Greek playback stopped") { r.speech.active.value == null }
        resultBubble().click()
        await("Full translation chat") { input()?.isFocused == true }
        device.pressBack()
        screenshot("live-current-screen-greek-chat")
        println("Live current-screen translation completed; Greek characters=${answer.count { it in '\u0370'..'\u03ff' }}; modelCalls=${r.agent.metrics.value.modelCalls}; elapsedMs=${r.agent.metrics.value.elapsedMs}")
    }

}
