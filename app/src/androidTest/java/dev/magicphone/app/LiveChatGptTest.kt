// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.magicphone.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith

/** Explicit, account-owner-authorized live check. Never run against a physical phone. */
@RunWith(AndroidJUnit4::class)
class LiveChatGptTest {
    @Test
    fun benchmarkOpenTapType() {
        val repeats = InstrumentationRegistry.getArguments().getString("repeats", "3").toInt()
        require(repeats in 1..5)
        repeat(repeats) { runFixtureTask(true, true) }
    }

    @Test
    fun chatGptOpensInstalledFixtureFromTaskScreen() = runFixtureTask(false)

    @Test
    fun chatGptTapsInsideFixtureFromTaskScreen() = runFixtureTask(true)

    @Test
    fun automaticAccessOpensTapsAndTypesWithoutApprovals() = runFixtureTask(true, true)

    @Test
    fun automaticAccessRecoversAfterScreenChanges() = runFixtureTask(true, true, true)

    private fun runFixtureTask(inAppAction: Boolean, automatic: Boolean = false, changeScreen: Boolean = false) {
        Assume.assumeTrue(
            "Requires explicit liveChatGpt=true and account-owner authorization",
            InstrumentationRegistry.getArguments().getString("liveChatGpt") == "true",
        )
        Assert.assertTrue(android.os.Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val r = context.runtime
        val target = "dev.magicphone.fixture"
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        androidx.test.uiautomator.Configurator.getInstance().setUiAutomationFlags(
            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        )
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val service = "${context.packageName}/${PhoneService::class.java.name}"
        val enabledServices = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_accessibility_services"
        ).orEmpty().split(':').filter { it.isNotBlank() }
        Assert.assertTrue("Accessibility must already be enabled", service in enabledServices)
        Assert.assertTrue(enabledServices.all { it.matches(Regex("[A-Za-z0-9_./]+")) })
        val selected = r.settings.value.profiles.single { it.id == r.settings.value.selected }
        Assert.assertEquals(ProviderKind.CHATGPT, selected.kind)
        Assert.assertTrue(selected.model.isNotBlank())
        Assert.assertTrue(r.settings.value.activeAccount.isNotBlank())
        context.packageManager.getPackageInfo(target, 0)
        val originalPolicy = r.settings.value.policy
        main {
            r.stop()
            r.saveSettings(r.settings.value.copy(
                floating = inAppAction,
                policy = r.settings.value.policy.copy(
                    allowAllApps = false,
                    apps = if (automatic) r.settings.value.policy.apps - target
                        else r.settings.value.policy.apps + (target to AppRule(true, true)),
                ),
            ))
            r.newConversation()
        }
        try {
            device.executeShellCommand("am force-stop dev.magicphone.fixture")
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            // Instrumentation restarts the app process. Rebind only its already-enabled service.
            // This harness is emulator-only; never run or grant Accessibility on a real phone.
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabledServices.filter { it != service }.joinToString(":").ifEmpty { "null" }}")
            SystemClock.sleep(400)
            device.executeShellCommand("settings put secure enabled_accessibility_services ${enabledServices.joinToString(":")}")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
            val connectedDeadline = SystemClock.elapsedRealtime() + 20000
            while (!r.connected.value && SystemClock.elapsedRealtime() < connectedDeadline) {
                SystemClock.sleep(100)
            }
            Assert.assertTrue("Accessibility must already be enabled", r.connected.value)
            if (automatic) {
                device.wait(Until.findObject(By.text(context.getString(R.string.settings))), 10000).click()
                device.wait(Until.findObject(By.text(context.getString(R.string.access_section))), 10000).click()
                device.wait(Until.findObject(By.text(context.getString(R.string.all_apps_enable))), 10000).click()
                device.wait(Until.hasObject(By.text(context.getString(R.string.all_apps_disable))), 5000)
                Assert.assertTrue("Actual Settings button enables override", r.settings.value.policy.allowAllApps)
                Assert.assertNull("No individual permission for fixture", r.settings.value.policy.apps[target])
                val saved = JsonCodec.decodeFromString(Settings.serializer(), r.vault.read("settings")!!)
                Assert.assertTrue("Override persists in encrypted settings", saved.policy.allowAllApps)
                device.findObject(By.text(context.getString(R.string.task))).click()
            }
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)
            Assert.assertNotNull("Task text field", field)
            field.click()
            field.text = if (automatic)
                "Open MagicPhone Practice, tap its Add one button exactly once, enter MagicPhone ready in its Ordinary text field, and verify the counter is 1 and the text is present. Do not open the manual-secret field. Then finish."
            else if (inAppAction)
                "Open MagicPhone Practice, tap its Add one button exactly once, and verify that the counter is 1. Then finish."
            else "Open MagicPhone Practice. Verify that its screen is visible, then finish."
            device.pressBack()
            val start = device.wait(Until.findObject(By.text(context.getString(R.string.start))), 10000)
            Assert.assertNotNull("Start task button", start)
            start.click()
            val approvedOperations = mutableSetOf<Op>()
            var approvedId: String? = null
            val deadline = SystemClock.elapsedRealtime() + 180000
            var changed = false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (changeScreen && !changed && r.agent.actions.value.any { it.operation == Op.OBSERVE }) {
                    device.findObject(By.clazz("android.widget.EditText").pkg(target)).text = "Background update"
                    changed = true
                }
                val approval = r.approval.value
                if (automatic) Assert.assertNull("Automatic access must never request approval", approval)
                if (approval != null && approval.id != approvedId) {
                    Assert.assertEquals("Only the requested fixture may be approved", target, approval.action.app)
                    Assert.assertTrue(approval.action.op == Op.OPEN ||
                        (inAppAction && approval.action.op == Op.TAP && approval.action.node.isNotBlank()))
                    Assert.assertFalse("The operation must not be repeated", approval.action.op in approvedOperations)
                    val button = device.wait(Until.findObject(By.text(context.getString(R.string.approve_once))), 5000)
                    Assert.assertNotNull("Visible local approval", button)
                    button.click()
                    approvedOperations += approval.action.op
                    approvedId = approval.id
                }
                if (r.agent.state.value in setOf(RunState.COMPLETED, RunState.FAILED, RunState.WAITING_USER)) break
                SystemClock.sleep(150)
            }
            val summary = "state=${r.agent.state.value};error=${r.agent.error.value};" +
                "approved=$approvedOperations;${r.agent.diagnostics.value};" +
                "inspection=${r.phone?.inspectionDiagnostics};" +
                "actions=${r.agent.actions.value.map { "${it.operation}:${it.status}" }}"
            println("MagicPhone live protocol counters: $summary")
            println("MagicPhone performance: model=${selected.model};" +
                JsonCodec.encodeToString(RunMetrics.serializer(), r.agent.metrics.value))
            Assert.assertEquals(summary, RunState.COMPLETED, r.agent.state.value)
            if (changeScreen) {
                Assert.assertTrue("A real screen update was injected", changed)
                Assert.assertTrue("Stale action rejected then replanned", r.agent.actions.value.any {
                    it.status == "not_dispatched_stale_target"
                })
            }
            Assert.assertTrue("OPEN dispatched through gateway", r.agent.actions.value.any {
                it.operation == Op.OPEN && it.app == target && it.status == "dispatched"
            })
            Assert.assertTrue("Post-launch screen observed", r.agent.actions.value.any {
                it.operation == Op.OBSERVE && it.app == target && it.status == "observed"
            })
            Assert.assertTrue("Actual fixture is foreground", device.wait(Until.hasObject(By.pkg(target)), 5000))
            if (inAppAction) {
                Assert.assertTrue("Semantic TAP dispatched through gateway", r.agent.actions.value.any {
                    it.operation == Op.TAP && it.app == target && it.status == "dispatched"
                })
                Assert.assertTrue("Exactly one actual increment", device.wait(Until.hasObject(By.text("Counter: 1")), 5000))
                if (automatic) {
                    Assert.assertTrue("Ordinary TEXT dispatched through gateway", r.agent.actions.value.any {
                        it.operation == Op.TEXT && it.app == target && it.status == "dispatched"
                    })
                    Assert.assertTrue("Actual text is present", device.wait(Until.hasObject(By.text("MagicPhone ready")), 5000))
                    Assert.assertTrue("No approvals were clicked", approvedOperations.isEmpty())
                }
                if (changeScreen) {
                    // The retired Pause/Stop overlay must not obstruct coordinate dispatch.
                    main {
                        r.gateway.start()
                        r.agent.state.value = RunState.ACTING
                        r.phone!!.updateControls()
                    }
                    try {
                        val observed = runBlocking {
                            r.gateway.run(Action(Op.OBSERVE, target))
                        }
                        val snapshot = JsonCodec.decodeFromString(Screen.serializer(), observed.content)
                        val button = snapshot.nodes.single { it.label == "Add one · Προσθήκη" }
                        main { Assert.assertTrue(r.phone!!.inspect(target).protectedRects.isEmpty()) }
                        val result = runBlocking {
                            r.gateway.run(Action(Op.TAP, target, snapshot.id,
                                x = (button.bounds.left + button.bounds.right) / 2,
                                y = (button.bounds.top + button.bounds.bottom) / 2))
                        }
                        Assert.assertEquals("Coordinate action accepted", "dispatched", result.status)
                        Assert.assertNull("No coordinate approval", r.approval.value)
                        Assert.assertTrue("Coordinate tap actually increments", device.wait(Until.hasObject(By.text("Counter: 2")), 5000))
                        println("MagicPhone coordinate safety: tap dispatched without approval or retired control overlay")
                    } finally {
                        main { r.agent.state.value = RunState.COMPLETED; r.gateway.stop(); r.phone!!.updateControls() }
                    }
                }
                // A window created through the service but not registered as one of its controls
                // must still block observation. Package-name equality is not proof of ownership.
                val phone = r.phone!!
                val manager = phone.getSystemService(android.view.WindowManager::class.java)
                val untracked = android.widget.TextView(phone).apply { text = "Untracked overlay fixture" }
                main {
                    manager.addView(untracked, android.view.WindowManager.LayoutParams(
                        400, 120, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        android.graphics.PixelFormat.TRANSLUCENT,
                    ))
                    r.gateway.start()
                }
                try {
                    Assert.assertTrue(device.wait(Until.hasObject(By.text("Untracked overlay fixture")), 5000))
                    val failure = runBlocking {
                        withContext(Dispatchers.Main) {
                            runCatching { r.gateway.run(Action(Op.OBSERVE, target)) }.exceptionOrNull()
                        }
                    }
                    Assert.assertEquals("Untracked overlay must remain blocked", "screen_uncertain", (failure as? SafeFailure)?.code)
                    println("MagicPhone overlay safety: untracked service window blocked")
                } finally {
                    main { manager.removeViewImmediate(untracked); r.gateway.stop() }
                }
            }
            Assert.assertEquals(selected.id, r.settings.value.selected)
            if (automatic) {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                Assert.assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.task))), 10000))
                if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) {
                    device.pressBack()
                    device.wait(Until.gone(By.pkg("com.google.android.inputmethod.latin")), 5000)
                }
                device.waitForIdle(5000)
                // The composer stays fixed while chat contents are now a lazy list.
                androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true))
                    .scrollToBeginning(20)
                device.wait(Until.findObject(By.text(context.getString(R.string.automatic_badge))), 10000).click()
                device.wait(Until.gone(By.text(context.getString(R.string.automatic_badge))), 5000)
                Assert.assertFalse("Task screen button revokes automatic access", r.settings.value.policy.allowAllApps)
                main { r.gateway.start() }
                val revoked = runBlocking {
                    runCatching { r.gateway.run(Action(Op.OBSERVE, target)) }.exceptionOrNull()
                }
                Assert.assertEquals("Unlisted app is blocked again", "app_not_allowed", (revoked as? SafeFailure)?.code)
                println("MagicPhone automatic access: UI enabled, persisted, zero approvals, UI revoked")
            }
        } finally {
            main {
                r.stop()
                if (automatic) r.saveSettings(r.settings.value.copy(policy = originalPolicy.copy(allowAllApps = false)))
            }
        }
    }
}
