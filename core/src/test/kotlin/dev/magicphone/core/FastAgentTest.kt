// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class FastAgentTest {
    private val pkg = "test.fixture"
    private fun screen(version: Int = 0) = Screen(id = "s$version", app = pkg, focused = true, locked = false, mixed = false,
        nodes = listOf(Node("s$version:0", "Counter: $version", Rect(0, 0, 90, 90), true, true, false)))
    private class Device(val pkg: String) : DevicePort {
        var foreground = "own.app"
        var version = 0
        var inspectCount = 0
        var failReadAfterAction = false
        val executed = mutableListOf<Op>()
        override suspend fun foregroundPackage() = foreground
        override suspend fun inspect(app: String): Screen {
            inspectCount++
            if (failReadAfterAction && version > 0) throw SafeFailure("screen_uncertain")
            return Screen(id = "s$version", app = app, focused = true, locked = false, mixed = false,
                nodes = listOf(Node("s$version:0", "Counter: $version", Rect(0, 0, 90, 90), true, true, false)))
        }
        override suspend fun execute(action: Action, screen: Screen): ToolResult {
            executed += action.op
            delay(10)
            return when (action.op) {
                Op.APPS -> ToolResult("apps", "$pkg: Practice")
                Op.OBSERVE -> ToolResult("observed", JsonCodec.encodeToString(Screen.serializer(), screen))
                Op.TAP -> { version++; ToolResult("dispatched") }
                Op.COMPLETE -> ToolResult("reported")
                else -> ToolResult("updated")
            }
        }
    }
    private fun gate(device: Device, config: () -> PolicyConfig = {
        PolicyConfig(apps = mapOf(pkg to AppRule(true, true)), allowAllApps = true)
    }) = Gateway(Policy("own.app"), config, device, object : ApprovalPort {
        override suspend fun request(approval: Approval): Boolean = error("Unexpected approval")
    })
    private fun provider(next: (List<JsonElement>) -> Action) = object : ModelProvider {
        override val supportsImages = false
        override suspend fun models() = emptyList<ModelChoice>()
        override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
            delay(100)
            return Reply("", listOf(Call(id(), listOf(next(input)))), emptyList())
        }
    }

    @Test
    fun suppliedContextAndPostActionScreenRemoveObservationRoundTrips() = runTest {
        val device = Device(pkg).apply { foreground = pkg }
        val agent = Agent(gate(device), { _, _ -> }, clock = { testScheduler.currentTime })
        var rounds = 0
        agent.start(this, provider { input ->
            if (rounds++ == 0) {
                assertTrue(input.last().toString().contains("Practice"))
                assertTrue(input.last().toString().contains("s0:0"))
                Action(Op.TAP, pkg, "s0", "s0:0")
            } else {
                val result = input.last().jsonObject.str("output")
                assertTrue(result.contains("dispatched"))
                assertTrue(result.contains("observed"))
                assertTrue(result.contains("s1:0"))
                Action(Op.COMPLETE, text = "Counter verified")
            }
        }, "Tap once", optimize = true)
        advanceUntilIdle()
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals(listOf(Op.APPS, Op.OBSERVE, Op.TAP, Op.OBSERVE, Op.COMPLETE), device.executed)
        assertEquals(2, agent.metrics.value.modelCalls)
        assertEquals(200, agent.metrics.value.modelMs)
        assertEquals(50, agent.metrics.value.toolMs)
        assertEquals(250, agent.metrics.value.elapsedMs)
        val metrics = JsonCodec.encodeToString(RunMetrics.serializer(), agent.metrics.value)
        assertFalse(metrics.contains("Counter"))
        assertFalse(metrics.contains(pkg))
    }

    @Test
    fun blockedForegroundIsNeverInspectedDuringPrefill() = runTest {
        val device = Device(pkg).apply { foreground = "blocked.app" }
        val agent = Agent(gate(device) {
            PolicyConfig(apps = mapOf("blocked.app" to AppRule(true, true, true)), allowAllApps = true)
        }, { _, _ -> })
        agent.start(this, provider { Action(Op.COMPLETE) }, "Plan", optimize = true)
        advanceUntilIdle()
        assertEquals(0, device.inspectCount)
        assertEquals(RunState.COMPLETED, agent.state.value)
    }

    @Test
    fun failedPostActionReadNeverReplaysMutationOrPermitsUnverifiedCompletion() = runTest {
        val device = Device(pkg).apply { foreground = pkg; failReadAfterAction = true }
        val agent = Agent(gate(device), { _, _ -> })
        var rounds = 0
        agent.start(this, provider { input ->
            if (rounds++ == 0) Action(Op.TAP, pkg, "s0", "s0:0") else {
                val result = input.last().jsonObject.str("output")
                assertTrue(result.contains("observation_unavailable"))
                assertTrue(result.contains("dispatched"))
                assertFalse(result.contains("not_dispatched"))
                Action(Op.COMPLETE)
            }
        }, "Tap", optimize = true)
        advanceUntilIdle()
        assertEquals(1, device.version)
        assertEquals("verification_required", agent.error.value)
        assertEquals(RunState.FAILED, agent.state.value)
    }

    @Test
    fun automaticObservationStillHonorsRevokedReadPermission() = runTest {
        var allowed = true
        val device = Device(pkg).apply { foreground = pkg }
        val port = object : DevicePort by device {
            override suspend fun execute(action: Action, screen: Screen): ToolResult {
                val result = device.execute(action, screen)
                if (action.op == Op.TAP) allowed = false
                return result
            }
        }
        val gateway = Gateway(Policy("own.app"), {
            PolicyConfig(apps = mapOf(pkg to AppRule(allowed, allowed)),
                grants = listOf(Grant(pkg, setOf(Op.TAP), Long.MAX_VALUE)))
        }, port, object : ApprovalPort { override suspend fun request(approval: Approval) = true })
        val agent = Agent(gateway, { _, _ -> })
        var rounds = 0
        agent.start(this, provider { input ->
            if (rounds++ == 0) Action(Op.TAP, pkg, "s0", "s0:0") else {
                assertTrue(input.last().toString().contains("app_not_allowed"))
                Action(Op.COMPLETE)
            }
        }, "Tap", optimize = true)
        advanceUntilIdle()
        assertEquals(2, device.inspectCount) // Startup read + pre-tap inspection, no revoked read.
        assertEquals(1, device.version)
        assertEquals("verification_required", agent.error.value)
    }

    @Test
    fun scriptTimingIncludesExecutionWithoutModelRequests() = runTest {
        val device = Device(pkg)
        val agent = Agent(gate(device), { _, _ -> }, clock = { testScheduler.currentTime })
        val script = Script(name = "Practice", enabled = true,
            steps = listOf(ScriptStep(Action(Op.APPS)), ScriptStep(Action(Op.COMPLETE))))
        agent.start(this, provider { error("A script must not call the model") }, "",
            script = script to emptyMap())
        advanceUntilIdle()
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals(0, agent.metrics.value.modelCalls)
        assertEquals(20, agent.metrics.value.toolMs)
        assertEquals(20, agent.metrics.value.elapsedMs)
    }

    @Test
    fun readinessRequiresStableReadableGeometryAndResetsOnUnsafeScreens() {
        val ready = ScreenStability(80)
        assertFalse(ready.ready(screen(), 0))
        assertFalse(ready.ready(screen(), 79))
        assertTrue(ready.ready(screen(), 80))
        assertFalse(ready.ready(screen(1), 81))
        assertFalse(ready.ready(screen(1).copy(mixed = true), 160))
        assertFalse(ready.ready(screen(1), 200))
        assertTrue(ready.ready(screen(1), 280))
        assertFalse(ready.ready(screen(1).copy(width = 999), 281))
        assertFalse(ready.ready(screen(1).copy(sensitive = true), 999))
        assertFalse(ready.ready(screen(1).copy(locked = true), 1000))
        assertFalse(ready.ready(null, 1001))
    }
}
