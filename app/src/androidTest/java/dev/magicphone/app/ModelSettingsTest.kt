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
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ModelSettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val r get() = context.runtime
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!check() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        Assert.assertTrue(label, check())
    }

    @Test fun legacySettingsRemovePlanOnlyAndPreserveAccountsAndPermissions() {
        val profile = Profile(id = "fixture", name = "Fixture", kind = ProviderKind.CHATGPT, model = "gpt-6-astra")
        val original = Settings(onboarded = true, profiles = listOf(profile), selected = profile.id,
            activeAccount = "fixture-account", host = "fixture-host", retentionDays = 90,
            policy = PolicyConfig(allowAllApps = true, apps = mapOf("blocked.app" to AppRule(deny = true))))
        val json = JsonCodec.encodeToJsonElement(Settings.serializer(), original).jsonObject
        for (plan in listOf(true, false)) for (fast in listOf(true, false)) {
            val oldProfile = JsonCodec.encodeToJsonElement(Profile.serializer(), profile).jsonObject - "reasoningEffort"
            val legacy = JsonObject(json + mapOf(
                "policy" to JsonObject(json.getValue("policy").jsonObject + ("planOnly" to JsonPrimitive(plan))),
                "profiles" to JsonArray(listOf(JsonObject(oldProfile))), "fastDecisions" to JsonPrimitive(fast)))
            val migrated = decodeSettings(legacy.toString())
            Assert.assertEquals(original.copy(profiles = listOf(profile.copy(reasoningEffort = if (fast) "low" else null))), migrated)
            val saved = JsonCodec.encodeToString(Settings.serializer(), migrated)
            Assert.assertFalse(saved.contains("planOnly"))
            Assert.assertFalse(saved.contains("fastDecisions"))
            Assert.assertEquals(migrated, decodeSettings(saved))
        }
        Assert.assertEquals(original, decodeSettings(JsonCodec.encodeToString(Settings.serializer(), original)))
        Assert.assertTrue(runCatching { decodeSettings(JsonObject(json + ("unknownField" to JsonPrimitive(true))).toString()) }.isFailure)
    }

    @Test fun thinkingAndSpeedSelectionsPersistAndLayoutSupportsKeyboardAndDarkMode() {
        Assert.assertTrue(android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val device = UiDevice.getInstance(instrumentation)
        val settings = r.settings.value
        val archive = r.archive.value
        val conversation = r.current.value
        val night = device.executeShellCommand("cmd uimode night").trim().substringAfterLast(' ')
        val scale = device.executeShellCommand("settings get system font_scale").trim()
        fun tap(label: Int) {
            val text = context.getString(label)
            if (!device.hasObject(By.text(text))) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
            device.wait(Until.findObject(By.text(text)), 10000).click()
        }
        fun open() { context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
        fun shot(name: String) {
            device.waitForIdle(3000)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "$name.png"))
        }
        val p = Profile(id = "ui-fixture", name = "OpenAI", kind = ProviderKind.OPENAI, model = "gpt-6-astra",
            modelChoice = ModelChoice("gpt-6-astra", "GPT-6 Astra"), reasoningEffort = "low")
        try {
            main { r.stop(); r.agent.clearView(); r.saveSettings(settings.copy(onboarded = true, selected = p.id, profiles = listOf(p))); r.newConversation() }
            device.executeShellCommand("cmd uimode night no")
            open()
            await("Welcome screen") { device.hasObject(By.text(context.getString(R.string.welcome_title))) }
            shot("refresh-home-light")
            Assert.assertFalse(device.hasObject(By.textContains("Plan only")))
            tap(R.string.settings)
            tap(R.string.thinking_low)
            Assert.assertFalse(device.hasObject(By.text(context.getString(R.string.thinking_none))))
            tap(R.string.thinking_high)
            await("High selected") { r.settings.value.profiles.single().reasoningEffort == "high" }
            tap(R.string.account_default)
            tap(R.string.speed_ultrafast)
            await("Ultrafast selected") { r.settings.value.profiles.single().serviceTier == "ultrafast" }
            val saved = decodeSettings(r.vault.read("settings")!!).profiles.single()
            Assert.assertEquals("high", saved.reasoningEffort)
            Assert.assertEquals("ultrafast", saved.serviceTier)
            shot("refresh-models-light")
            main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single().recreate() }
            await("Selection after recreation") { device.hasObject(By.text(context.getString(R.string.thinking_high))) }
            device.executeShellCommand("cmd uimode night yes")
            await("Theme recreation finished") { device.hasObject(By.text(context.getString(R.string.thinking_high))) }
            shot("refresh-models-dark")
            tap(R.string.task)
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(context.packageName)), 10000)
            input.click(); input.text = "Open the practice app"
            await("Keyboard opens") { device.hasObject(By.pkg("com.google.android.inputmethod.latin")) }
            shot("refresh-composer-dark")
            device.pressBack()
            device.executeShellCommand("settings put system font_scale 1.3")
            await("Scaled composer still usable") { device.hasObject(By.text(context.getString(R.string.start))) }
            shot("refresh-large-text")
        } finally {
            device.executeShellCommand("settings put system font_scale ${scale.toFloatOrNull() ?: 1f}")
            device.executeShellCommand("cmd uimode night ${if (night in setOf("yes", "no", "auto")) night else "no"}")
            main { r.stop(); r.agent.clearView(); r.saveSettings(settings); r.saveArchive(archive); r.current.value = conversation }
            runBlocking { r.flushHistory() }
            device.pressHome()
        }
    }

    @Test fun liveCatalogListsOnlyNonSensitiveModelCapabilities() {
        Assume.assumeTrue("Read-only account catalog check requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("liveCatalog") == "true")
        Assert.assertTrue(android.os.Build.MODEL.contains("sdk"))
        val p = r.settings.value.profiles.single { it.id == r.settings.value.selected }
        Assert.assertEquals(ProviderKind.CHATGPT, p.kind)
        main { r.discoverModels() }
        await("Catalog completed") { !r.modelsLoading.value }
        Assert.assertEquals("", r.modelsError.value)
        Assert.assertTrue(r.models.value.isNotEmpty())
        // Only allowlisted public capabilities, never account details or raw responses.
        for (m in r.models.value) if (m.id.matches(Regex("[A-Za-z0-9._-]+")))
            println("MagicPhone catalog: ${m.id}; thinking=${m.reasoningEfforts ?: "documented-defaults"}; speed=${m.serviceTiers}")
    }
}
