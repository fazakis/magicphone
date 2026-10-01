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
        original = r.archive.value
        selected = r.current.value
        profile = r.settings.value.selected
        main { r.newConversation() }
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
        main { r.stop(); r.agent.clearView(); r.saveArchive(original); r.current.value = selected }
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
        main { r.newConversation() }
        val other = r.current.value
        Assert.assertNotEquals(request.conversation, other)
        pendingChat.send()
        await("Saved notification returns to original conversation") { r.current.value == request.conversation && input()?.isFocused == true }
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 5000))
        Assert.assertTrue(r.archive.value.conversations.single { it.id == request.conversation }.messages.any { it.text == provider.question })
        Assert.assertEquals(RunState.IDLE, r.agent.state.value)
    }
}
