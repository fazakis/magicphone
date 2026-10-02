// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenRecoveryTest {
    private val pkg = "test.fixture"
    private fun screen(version: String) = Screen(
        id = version, app = pkg, width = 200, height = 400, focused = true,
        locked = false, mixed = false,
        nodes = listOf(Node("$version:0", "Add one", Rect(0, 0, 100, 100), true, true, false)),
    )
    private fun tap(version: String) = Action(Op.TAP, pkg, version, "$version:0")
    private fun reply(vararg actions: Action) = Reply("", listOf(Call(id(), actions.toList())), emptyList())
    private fun provider(next: (List<JsonElement>) -> Reply) = object : ModelProvider {
        override val supportsImages = false
        override suspend fun models() = emptyList<ModelChoice>()
        override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit) = next(input)
    }
    private class Device(var screen: Screen) : DevicePort {
        val executed = mutableListOf<Op>()
        var failure = ""
        override suspend fun inspect(app: String) = screen
        override suspend fun execute(action: Action, screen: Screen): ToolResult {
            executed += action.op
            if (failure.isNotEmpty()) throw SafeFailure(failure)
            return if (action.op == Op.OBSERVE)
                ToolResult("observed", JsonCodec.encodeToString(Screen.serializer(), screen))
            else ToolResult("dispatched")
        }
    }
    private fun gateway(device: Device, automatic: Boolean = true, approve: suspend () -> Boolean = { error("No approval expected") }) = Gateway(
        Policy("own.app"), { PolicyConfig(apps = mapOf(pkg to AppRule(true, true)), allowAllApps = automatic) },
        device, object : ApprovalPort { override suspend fun request(approval: Approval) = approve() },
    )

    @Test
    fun changedScreenCancelsBatchAndRequiresObservationBeforeNewAction() = runTest {
        val device = Device(screen("old"))
        val agent = Agent(gateway(device), { _, _ -> })
        var round = 0
        val p = provider { input ->
            when (round++) {
                0 -> reply(Action(Op.OBSERVE, pkg))
                1 -> {
                    device.screen = screen("fresh")
                    Reply("", listOf(
                        Call("stale-batch", listOf(tap("old"), tap("old").copy(op = Op.TEXT, text = "must not type"))),
                        Call("pending-open", listOf(Action(Op.OPEN, pkg))),
                        Call("pending-complete", listOf(Action(Op.COMPLETE))),
                    ), emptyList())
                }
                2 -> {
                    val outputs = input.filterIsInstance<JsonObject>().filter { it.str("type") == "function_call_output" }
                    assertTrue(outputs.single { it.str("call_id") == "stale-batch" }.str("output").contains("not_dispatched_stale_target"))
                    for (id in listOf("pending-open", "pending-complete"))
                        assertTrue(outputs.single { it.str("call_id") == id }.str("output").contains("cancelled_screen_changed"))
                    reply(tap("fresh")) // Even a guessed correct reference must wait for OBSERVE.
                }
                3 -> {
                    assertTrue(input.last().toString().contains("fresh_observation_required"))
                    reply(Action(Op.OBSERVE, pkg))
                }
                4 -> reply(tap("fresh"))
                5 -> reply(Action(Op.OBSERVE, pkg))
                else -> reply(Action(Op.COMPLETE))
            }
        }
        agent.start(backgroundScope, p, "Tap once")
        runCurrent()
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals(listOf(Op.OBSERVE, Op.OBSERVE, Op.TAP, Op.OBSERVE, Op.COMPLETE), device.executed)
        assertEquals("", agent.error.value)
        assertEquals(2, agent.actions.value.count { it.status.startsWith("not_dispatched_") })
    }

    @Test
    fun changedApprovalRequiresNewObservationAndNewApproval() = runTest {
        val device = Device(screen("old"))
        var approvals = 0
        val agent = Agent(gateway(device, false) {
            if (++approvals == 1) device.screen = screen("fresh")
            true
        }, { _, _ -> })
        var round = 0
        agent.start(backgroundScope, provider {
            when (round++) {
                0 -> reply(tap("old"))
                1 -> reply(Action(Op.OBSERVE, pkg))
                2 -> reply(tap("fresh"))
                3 -> reply(Action(Op.OBSERVE, pkg))
                else -> reply(Action(Op.COMPLETE))
            }
        }, "Tap once")
        runCurrent()
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals(2, approvals)
        assertEquals(1, device.executed.count { it == Op.TAP })
        assertTrue(agent.actions.value.any { it.status == "not_dispatched_stale_approval" })
    }

    @Test
    fun unreadableScreenUsesNormalRunBudgetInsteadOfFailingAfterThreeReads() = runTest {
        val device = Device(screen("now").copy(mixed = true))
        val agent = Agent(gateway(device), { _, _ -> })
        var requests = 0
        agent.start(backgroundScope, provider { requests++; reply(Action(Op.OBSERVE, pkg)) }, "Read")
        runCurrent()
        assertEquals(RunState.FAILED, agent.state.value)
        assertEquals("run_budget", agent.error.value)
        assertEquals(60, requests)
        assertTrue(device.executed.isEmpty())
    }

    @Test
    fun repeatedReadFailuresRecoverWithoutDelayOrUserIntervention() = runTest {
        val device = Device(screen("now").copy(mixed = true))
        val persisted = mutableListOf<RunState>()
        val agent = Agent(gateway(device), { _, state -> persisted += state })
        var notices = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.screenReadNotices.collect { notices++ } }
        var requests = 0
        agent.start(backgroundScope, provider { input ->
            val round = requests++
            if (round in 1..5) assertTrue(input.last().toString().contains("observation_unavailable"))
            when {
                round < 5 -> reply(Action(Op.OBSERVE, pkg))
                round == 5 -> { device.screen = screen("fresh"); reply(Action(Op.OBSERVE, pkg)) }
                else -> reply(Action(Op.COMPLETE, text = "Read the visible counter"))
            }
        }, "Read")
        runCurrent()
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals("", agent.error.value)
        assertEquals(1, notices)
        assertEquals(0, testScheduler.currentTime) // No added wait, backoff or notice delay.
        assertEquals(listOf(RunState.COMPLETED), persisted)
        assertEquals(listOf(Op.OBSERVE, Op.COMPLETE), device.executed)
    }

    @Test
    fun modelScreenshotFailureKeepsReadableObservationAndDoesNotRequireUser() = runTest {
        val device = Device(screen("now"))
        val agent = Agent(gateway(device), { _, _ -> })
        var rounds = 0
        agent.start(backgroundScope, provider { input ->
            when (rounds++) {
                0 -> reply(Action(Op.OBSERVE, pkg))
                1 -> { device.failure = "capture_uncertain"; reply(Action(Op.SCREENSHOT, pkg, "now")) }
                else -> {
                    assertTrue(input.last().toString().contains("observation_unavailable"))
                    device.failure = ""
                    reply(Action(Op.COMPLETE, text = "Read visible content"))
                }
            }
        }, "Read")
        runCurrent()
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals("", agent.error.value)
    }

    @Test
    fun ambiguousDispatchAndDeniedPermissionsAreNeverRetried() = runTest {
        for (failure in listOf("action_uncertain", "manual_secret", "app_not_allowed", "approval_denied")) {
            val device = Device(screen("now")).apply { this.failure = failure }
            val agent = Agent(gateway(device), { _, _ -> })
            var requests = 0
            agent.start(backgroundScope, provider { requests++; reply(tap("now")) }, "Tap")
            runCurrent()
            assertEquals(RunState.FAILED, agent.state.value)
            assertEquals(failure, agent.error.value)
            assertEquals(1, requests)
            assertEquals(listOf(Op.TAP), device.executed)
        }
    }
}
