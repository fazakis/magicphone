// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.magicphone.core.*
import java.util.Locale
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith

/** Only run on a dedicated emulator. The fixture has no external side effects. */
@RunWith(AndroidJUnit4::class)
class DeviceAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = run {
        androidx.test.uiautomator.Configurator.getInstance()
            .setUiAutomationFlags(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            )
        UiDevice.getInstance(instrumentation)
    }
    private val r
        get() = context.runtime

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(timeout: Long = 15000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        Assert.fail("Condition was not met")
    }

    @Before
    fun setup() {
        Assert.assertTrue(
            "Only the test emulator is allowed",
            android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.MODEL.contains("sdk"),
        )
        device.wakeUp()
        device.pressHome()
        main {
            r.stop()
            r.saveSettings(r.settings.value.copy(onboarded = true, floating = false))
            r.configurePractice()
            r.newConversation()
        }
        // Test-harness-only setup. The production APK has no permission-granting tool or receiver.
        instrumentation.getUiAutomation(
            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        )
        device.executeShellCommand("settings put secure enabled_accessibility_services null")
        device.executeShellCommand("settings put secure accessibility_enabled 0")
        device.executeShellCommand("am force-stop dev.magicphone.fixture")
        SystemClock.sleep(400)
        device.executeShellCommand(
            "settings put secure enabled_accessibility_services ${context.packageName}/${PhoneService::class.java.name}"
        )
        device.executeShellCommand("settings put secure accessibility_enabled 1")
        await(30000) { r.connected.value }
    }

    @After
    fun cleanup() {
        main { r.stop() }
    }

    @Test
    fun deterministicAgentActuallyTapsFixtureAndStoresNoArguments() {
        main {
            r.newConversation()
            r.start("Run the controlled fixture demo")
        }
        var approvals = 0
        val end = SystemClock.elapsedRealtime() + 45000
        while (
            SystemClock.elapsedRealtime() < end &&
                r.agent.state.value !in setOf(RunState.COMPLETED, RunState.FAILED)
        ) {
            val approve = device.findObject(By.text(context.getString(R.string.approve_once)))
            if (approve != null) {
                approve.click()
                approvals++
            }
            SystemClock.sleep(150)
        }
        Assert.assertEquals(
            "Error: ${r.agent.error.value}; approvals=$approvals; audit=${r.archive.value.audits.takeLast(6).map {it.operation+":"+it.status}}",
            RunState.COMPLETED,
            r.agent.state.value,
        )
        Assert.assertTrue(
            "The actual counter must change",
            device.wait(Until.hasObject(By.text("Counter: 1")), 5000),
        )
        Assert.assertEquals(2, approvals)
        val serialized = JsonCodec.encodeToString(Archive.serializer(), r.archive.value)
        Assert.assertFalse(serialized.contains("\"arguments\""))
        Assert.assertTrue(
            r.archive.value.audits.any { it.operation == "TAP" && it.status == "dispatched" }
        )
    }

    @Test
    fun staleTargetAfterExternalChangeIsRejected() = runBlocking {
        context.startActivity(
            Intent()
                .setClassName("dev.magicphone.fixture", "dev.magicphone.fixture.FixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Add one · Προσθήκη")), 10000))
        main { r.gateway.start() }
        val before =
            withContext(Dispatchers.Main) {
                r.gateway.run(Action(Op.OBSERVE, "dev.magicphone.fixture"))
            }
        val snapshot = JsonCodec.decodeFromString(Screen.serializer(), before.content)
        val target = snapshot.nodes.single { it.label == "Add one · Προσθήκη" }
        device.findObject(By.text("Add one · Προσθήκη")).click()
        Assert.assertTrue(
            "The external change must be visible before checking the old snapshot",
            device.wait(Until.hasObject(By.text("Counter: 1")), 5000),
        )
        val failure = runCatching {
            withTimeout(5000) {
                withContext(Dispatchers.Main) {
                    r.gateway.run(Action(Op.TAP, snapshot.app, snapshot.id, target.ref))
                }
            }
        }
            .exceptionOrNull()
        Assert.assertEquals("stale_target", (failure as? SafeFailure)?.code)
    }

    @Test
    fun passwordScreenIsBlockedEvenWithoutFlagSecure() = runBlocking {
        context.startActivity(
            Intent()
                .setClassName("dev.magicphone.fixture", "dev.magicphone.fixture.FixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Show manual-secret field")), 10000))
        device.findObject(By.text("Show manual-secret field")).click()
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Manual password")), 5000))
        main { r.gateway.start() }
        val failure = runCatching {
            withContext(Dispatchers.Main) {
                r.gateway.run(Action(Op.OBSERVE, "dev.magicphone.fixture"))
            }
        }
            .exceptionOrNull()
        Assert.assertEquals("manual_secret", (failure as? SafeFailure)?.code)
    }

    @Test
    fun vaultEncryptsAndAuthenticatesStoredHistory() {
        val vault = Vault(context)
        val marker = "sensitive-test-value-9384"
        vault.write("test-qa", marker)
        Assert.assertEquals(marker, vault.read("test-qa"))
        val file = java.io.File(context.noBackupFilesDir, "vault/test-qa.enc")
        Assert.assertFalse(file.readBytes().decodeToString().contains(marker))
        val backup = java.io.File(file.path + ".bak")
        Assert.assertTrue(file.renameTo(backup))
        Assert.assertEquals(marker, vault.read("test-qa"))
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        Assert.assertTrue(runCatching { vault.read("test-qa") }.isFailure)
        vault.delete("test-qa")
    }

    @Test
    fun greekResourcesAndUiEntryPointsArePresent() {
        val greek =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale("el")) }
            )
        Assert.assertEquals("Διακοπή", greek.getString(R.string.stop))
        Assert.assertEquals("Έγκριση μία φορά", greek.getString(R.string.approve_once))
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Δοκιμή από κοινή χρήση")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Assert.assertTrue(
            device.wait(Until.hasObject(By.textContains("Δοκιμή από κοινή χρήση")), 10000)
        )
    }

    @Test
    fun unicodeScriptAndScreenshotUseRealGateway() = runBlocking {
        context.startActivity(
            Intent()
                .setClassName("dev.magicphone.fixture", "dev.magicphone.fixture.FixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Ordinary text · Κείμενο")), 10000))
        main { r.gateway.start() }
        val first =
            withContext(Dispatchers.Main) {
                r.gateway.run(Action(Op.OBSERVE, "dev.magicphone.fixture"))
            }
        val screen = JsonCodec.decodeFromString(Screen.serializer(), first.content)
        val shot =
            withContext(Dispatchers.Main) {
                r.gateway.run(Action(Op.SCREENSHOT, screen.app, screen.id))
            }
        Assert.assertTrue(shot.image!!.startsWith("data:image/jpeg;base64,"))
        val script =
            Script(
                name = "Unicode",
                parameters = listOf(Parameter("note")),
                steps =
                    listOf(
                        ScriptStep(
                            Action(Op.TEXT, "dev.magicphone.fixture"),
                            textParameter = "note",
                            findLabel = "Ordinary text · Κείμενο",
                        )
                    ),
                enabled = true,
            )
        val execution =
            async(Dispatchers.Main) {
                ScriptRunner(r.gateway).run(script, mapOf("note" to "Καλημέρα 🌿"))
            }
        Assert.assertTrue(
            device.wait(Until.hasObject(By.text(context.getString(R.string.approve_once))), 10000)
        )
        device.findObject(By.text(context.getString(R.string.approve_once))).click()
        execution.await()
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Καλημέρα 🌿")), 5000))
    }

    @Test
    fun uiRendersAtLargeTextInDarkMode() {
        device.executeShellCommand("cmd uimode night yes")
        device.executeShellCommand("settings put system font_scale 1.4")
        try {
            val activity =
                instrumentation.startActivitySync(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ) as MainActivity
            Assert.assertTrue(device.wait(Until.hasObject(By.text("MagicPhone")), 10000))
            Assert.assertTrue(device.hasObject(By.text(context.getString(R.string.stop))))
            // Synthetic UI capture uses the same screenshot-enabled window as everyday use.
            SystemClock.sleep(700)
            Assert.assertTrue(
                device.takeScreenshot(java.io.File(context.filesDir, "qa-ui-dark-large.png"))
            )
            activity.finish()
        } finally {
            device.executeShellCommand("settings put system font_scale 1.0")
            device.executeShellCommand("cmd uimode night no")
        }
    }
}
