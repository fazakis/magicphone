// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CurrentScreenTest {
    private val target = "test.reader"
    private fun run(allowed: Boolean = true, foreground: String = target, images: Boolean = true,
        screenshotFailure: String = "", observationFailure: String = "", partial: Boolean = false, mutation: Boolean = false, check: (Agent, List<Op>, List<JsonElement>) -> Unit) = runTest {
        val operations = mutableListOf<Op>()
        var modelInput = emptyList<JsonElement>()
        val gateway = Gateway(Policy("own.app"), { PolicyConfig(apps = mapOf(target to AppRule(observe = allowed, mutate = mutation))) },
            object : DevicePort {
                override suspend fun foregroundPackage() = foreground
                override suspend fun inspect(app: String): Screen {
                    if (observationFailure.isNotBlank()) throw SafeFailure(observationFailure)
                    return Screen(id = "s1", app = app, locked = false, mixed = false, focused = true, partial = partial)
                }
                override suspend fun execute(action: Action, screen: Screen): ToolResult {
                    operations += action.op
                    return when (action.op) {
                        Op.OPEN -> ToolResult("dispatched")
                        Op.SCREENSHOT -> { if (screenshotFailure.isNotBlank()) throw SafeFailure(screenshotFailure); ToolResult("captured", image = "data:image/jpeg;base64,dGVzdA==") }
                        Op.OBSERVE -> ToolResult("observed", JsonCodec.encodeToString(Screen.serializer(), screen.copy(nodes = listOf(Node("s1:0", "Visible sample abstract", Rect(0, 0, 100, 100), false, false, false)))))
                        else -> ToolResult("reported")
                    }
                }
            }, object : ApprovalPort { override suspend fun request(approval: Approval): Boolean = mutation })
        val provider = object : ModelProvider {
            private var rounds = 0
            override val supportsImages = images
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                modelInput = input
                if (mutation && rounds++ == 0) return Reply("", listOf(Call(id(), listOf(Action(Op.OPEN, target)))), emptyList())
                return Reply("Ελληνική μετάφραση του παραδείγματος.", emptyList(), emptyList())
            }
        }
        val agent = Agent(gateway, { _, _ -> })
        agent.start(this, provider, "Translate this screen", screenContext = target)
        advanceUntilIdle()
        check(agent, operations, modelInput)
    }
    @Test fun currentScreenSuppliesTextAndImageAndCompletesTextAnswer() = run { agent, ops, input ->
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals(listOf(Op.APPS, Op.OBSERVE, Op.SCREENSHOT), ops)
        assertTrue(input.toString().contains("Visible sample abstract"))
        assertTrue(input.toString().contains("input_image"))
        assertTrue(input.toString().contains("test.reader"))
    }
    @Test fun textOnlyProviderStillUsesCurrentScreen() = run(images = false) { agent, ops, input ->
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals(listOf(Op.APPS, Op.OBSERVE), ops)
        assertFalse(input.toString().contains("input_image"))
    }
    @Test fun blockedAppNeverInspectedOrSentToModel() = run(allowed = false) { agent, ops, input ->
        assertEquals(RunState.FAILED, agent.state.value)
        assertEquals(listOf(Op.APPS), ops); assertTrue(input.isEmpty())
    }
    @Test fun changedForegroundNeverReadsOldScreen() = run(foreground = "another.app") { agent, ops, input ->
        assertEquals("screen_context_changed", agent.error.value)
        assertEquals(listOf(Op.APPS), ops); assertTrue(input.isEmpty())
    }
    @Test fun textAnswerCannotClaimUnverifiedMutationCompleted() = run(mutation = true) { agent, _, _ ->
        assertEquals(RunState.FAILED, agent.state.value)
        assertEquals("verification_required", agent.error.value)
    }
    @Test fun transientScreenshotFailuresFallBackToTextWithoutStopping() {
        for (code in listOf("capture_uncertain", "screen_uncertain", "stale_target", "screenshot_throttled", "screenshot_failed"))
            run(screenshotFailure = code) { agent, _, input ->
                assertEquals(RunState.COMPLETED, agent.state.value, code)
                assertEquals("", agent.error.value)
                assertTrue(input.toString().contains("Visible sample abstract"))
                assertTrue(input.toString().contains("observation_unavailable"))
                assertFalse(input.toString().contains("input_image"))
            }
    }
    @Test fun transientInitialObservationStillReachesModel() = run(observationFailure = "screen_uncertain") { agent, ops, input ->
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals(listOf(Op.APPS), ops)
        assertTrue(input.toString().contains("observation_unavailable"))
        assertFalse(input.toString().contains("input_image"))
    }
    @Test fun partialScreenSuppliesVisibleTextAndSkipsScreenshot() = run(partial = true) { agent, ops, input ->
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals(listOf(Op.APPS, Op.OBSERVE), ops)
        assertTrue(input.toString().contains("Visible sample abstract"))
        assertTrue(input.toString().contains("partial_screen_image_omitted"))
        assertFalse(input.toString().contains("input_image"))
    }
    @Test fun secureScreenshotDoesNotLeakContextToModel() = run(screenshotFailure = "secure_window") { agent, _, input ->
        assertEquals("secure_window", agent.error.value); assertTrue(input.isEmpty())
    }
}
