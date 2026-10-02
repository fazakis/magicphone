// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import dev.magicphone.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ManualModelOptionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!check() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        Assert.assertTrue(label, check())
    }
    private fun tapText(text: String) {
        device.waitForIdle(3000)
        if (!device.hasObject(By.text(text))) {
            val scroll = UiScrollable(UiSelector().scrollable(true))
            scroll.scrollToBeginning(20)
            scroll.scrollIntoView(UiSelector().text(text))
        }
        device.wait(Until.findObject(By.text(text)), 10000).click()
    }
    private fun tap(label: Int) = tapText(context.getString(label))
    private fun shot(name: String) {
        device.waitForIdle(3000)
        device.takeScreenshot(File(context.getExternalFilesDir(null), "$name.png"))
    }

    @Test fun manualChoicesPersistAndDynamicModeIsStillAvailable() {
        Assert.assertTrue(android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = r.settings.value
        val savedModels = r.models.value
        val p = Profile(id = "manual-ui-fixture", name = "OpenAI", kind = ProviderKind.OPENAI, model = "gpt-6-astra",
            modelChoice = ModelChoice("gpt-6-astra", "GPT-6 Astra", listOf("low", "high"), listOf("fast")), reasoningEffort = "low")
        try {
            main {
                r.stop(); r.agent.clearView()
                r.saveSettings(original.copy(profiles = listOf(p), selected = p.id))
                // Fixture catalog: prevent network access during this local UI test.
                r.modelsLoading.value = true
                r.models.value = listOf(p.modelChoice!!)
            }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            tap(R.string.settings)
            tap(R.string.manual_model_options)
            await("Manual mode on") { r.settings.value.profiles.single().manualModelOptions }
            tapText("GPT-6 Astra")
            tapText("GPT-6.1 Sol")
            await("Unlisted Sol selected") { r.settings.value.profiles.single().model == "gpt-6.1-sol" }
            tap(R.string.thinking_low)
            tap(R.string.thinking_ultra)
            tap(R.string.account_default)
            tap(R.string.speed_ultrafast)
            var selected = r.settings.value.profiles.single()
            Assert.assertEquals("ultra", selected.reasoningEffort)
            Assert.assertEquals("ultrafast", selected.serviceTier)
            Assert.assertTrue(selected.manualModelOptions)
            Assert.assertEquals(selected, decodeSettings(r.vault.read("settings")!!).profiles.single())
            shot("manual-options-selected")
            main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single().recreate() }
            await("Restored manual selection") { device.hasObject(By.text(context.getString(R.string.speed_ultrafast))) }
            tap(R.string.enter_model)
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)
            input.click()
            input.text = "custom-model-id"
            if (device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 3000)) device.pressBack()
            tap(R.string.save)
            await("Custom model saved") { r.settings.value.profiles.single().model == "custom-model-id" }
            device.wait(Until.gone(By.pkg("com.google.android.inputmethod.latin")), 5000)
            // Re-enter settings to verify the saved mode and avoid UiScrollable skipping a
            // descendant of the oversized lazy-list settings card on its reverse search.
            tap(R.string.task)
            tap(R.string.settings)
            tap(R.string.manual_model_options)
            selected = r.settings.value.profiles.single()
            Assert.assertFalse(selected.manualModelOptions)
            Assert.assertNull(selected.reasoningEffort)
            Assert.assertNull(selected.serviceTier)
            Assert.assertTrue(ModelOptions.reasoningChoices(selected).isEmpty())
            main {
                r.agent.modelInfo.value = ModelRunInfo("gpt-6.1-sol", "low", "ultrafast", "gpt-6.1-sol", "low", "default")
            }
            tap(R.string.task)
            val reported = context.getString(R.string.reported_speed, context.getString(R.string.speed_standard))
            if (!device.hasObject(By.text(reported))) UiScrollable(UiSelector().scrollable(true)).scrollToEnd(20)
            await("Server fallback is visible") { device.hasObject(By.text(reported)) }
            Assert.assertTrue(device.hasObject(By.text(context.getString(R.string.speed_not_confirmed))))
            shot("manual-options-reported-speed")
        } catch (e: Throwable) {
            shot("manual-ui-failure")
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "manual-ui-failure.xml"))
            throw e
        } finally {
            main { r.stop(); r.agent.clearView(); r.saveSettings(original); r.models.value = savedModels; r.modelsLoading.value = false }
            device.pressHome()
        }
    }

    @Test fun liveSolTaskUsesManualUltrafastAndReportsServerTier() {
        Assume.assumeTrue("Requires explicit liveManualOptions=true and account-owner authorization",
            InstrumentationRegistry.getArguments().getString("liveManualOptions") == "true")
        Assert.assertTrue(android.os.Build.MODEL.contains("sdk"))
        val original = r.settings.value
        val archive = r.archive.value
        val conversation = r.current.value
        val selected = original.profiles.single { it.id == original.selected }
        Assert.assertEquals(ProviderKind.CHATGPT, selected.kind)
        try {
            val manual = ModelOptions.selected(ModelOptions.withManualOptions(selected, true),
                ModelOptions.manualModels.single { it.id == "gpt-6.1-sol" })
                .copy(reasoningEffort = "low", serviceTier = "ultrafast")
            main { r.saveSettings(original.copy(profiles = original.profiles.map { if (it.id == manual.id) manual else it })) }
            LiveChatGptTest().automaticAccessOpensTapsAndTypesWithoutApprovals()
            val info = r.agent.modelInfo.value!!
            Assert.assertEquals("gpt-6.1-sol", info.requestedModel)
            Assert.assertEquals("low", info.requestedEffort)
            Assert.assertEquals("ultrafast", info.requestedTier)
            Assert.assertEquals("gpt-6.1-sol", info.reportedModel)
            Assert.assertNotNull("Server reports its actual tier", info.reportedTier)
            println("MagicPhone manual options: requested=${info.requestedModel}/${info.requestedEffort}/${info.requestedTier};reported=${info.reportedModel}/${info.reportedEffort}/${info.reportedTier};speedConfirmed=${info.speedConfirmed}")
            UiScrollable(UiSelector().scrollable(true)).scrollToEnd(20)
            shot("manual-options-live-sol")
        } finally {
            main { r.stop(); r.agent.clearView(); r.saveSettings(original); r.saveArchive(archive); r.current.value = conversation }
            runBlocking { r.flushHistory() }
            device.pressHome()
        }
    }
}
