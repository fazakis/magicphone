// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExplanationTest {
    private val app = "test.reader"
    private val plan = Explanation(listOf(ExplanationSection("Compare the two accuracy values.",
        listOf(ExplanationRegion(100, 200, 450, 300), ExplanationRegion(500, 200, 900, 300, "pointer")))))
    private val screen = Screen(id = "fresh", app = app, width = 1200, height = 1800,
        focused = true, locked = false, mixed = false, captureBounds = Rect(100, 200, 1100, 1700))
    private val action = Action(Op.EXPLAIN, app, screen.id, explanation = plan)
    @Test fun mapsRegionsRelativeToCroppedImageIncludingOffsetAndResize() {
        val region = plan.sections[0].regions[0]
        assertEquals(Rect(200, 500, 550, 650), region.onScreen(screen.captureBounds))
        assertEquals(Rect(110, 120, 460, 170), region.onScreen(Rect(10, 20, 1010, 520)))
    }
    @Test fun supportsTextMathAndMultipleLinesInOneSpokenSection() {
        val styles = listOf("highlight", "underline", "ellipse", "rectangle", "pointer")
        val value = Explanation(listOf(ExplanationSection("E equals m c squared.",
            List(8) { ExplanationRegion(50, 30 + it * 60, 900, 70 + it * 60, styles[it % styles.size]) })))
        value.validate()
        assertEquals(value, ToolSchema.calls("perform", JsonCodec.encodeToString(Action.serializer(), action.copy(explanation = value))).single().explanation)
    }
    @Test fun rejectsMalformedUnboundedAndUnknownRegionStyles() {
        for (bad in listOf(ExplanationRegion(-1, 0, 10, 10), ExplanationRegion(0, 0, 1001, 10),
            ExplanationRegion(10, 0, 10, 10), ExplanationRegion(0, 0, 10, 10, "tap")))
            assertFailsWith<IllegalArgumentException> { bad.validate() }
        assertFailsWith<IllegalArgumentException> { Explanation(emptyList()).validate() }
        assertFailsWith<IllegalArgumentException> { Explanation(List(25) { ExplanationSection("x") }).validate() }
        assertFailsWith<IllegalArgumentException> { Explanation(listOf(ExplanationSection("x".repeat(1201)))).validate() }
        assertFailsWith<IllegalArgumentException> { Explanation(List(8) { ExplanationSection("x".repeat(1100)) }).validate() }
        assertFailsWith<IllegalArgumentException> { Explanation(listOf(ExplanationSection("x", List(9) { plan.sections[0].regions[0] }))).validate() }
    }
    @Test fun explanationIsTypedAndBoundToPermittedFreshScreen() {
        val policy = Policy("own.app")
        val allowed = PolicyConfig(apps = mapOf(app to AppRule(observe = true)))
        assertEquals(Decision.Allow, policy.decide(action, screen, allowed, 0))
        assertEquals(Decision.Deny("stale_target"), policy.decide(action.copy(snapshot = "old"), screen, allowed, 0))
        assertEquals(Decision.Deny("app_not_allowed"), policy.decide(action, screen, PolicyConfig(), 0))
        assertEquals(Decision.Deny("manual_secret"), policy.decide(action, screen.copy(sensitive = true), allowed, 0))
        assertEquals(Decision.Deny("device_locked"), policy.decide(action, screen.copy(locked = true), allowed, 0))
        assertEquals(Decision.Deny("screen_uncertain"), policy.decide(action, screen.copy(displayId = 2), allowed, 0))
        assertFailsWith<IllegalArgumentException> { action.copy(snapshot = "").validate() }
        assertFailsWith<IllegalArgumentException> { action.copy(explanation = null).validate() }
        assertFailsWith<IllegalArgumentException> { action.copy(op = Op.COMPLETE).validate() }
    }
    @Test fun toolDecoderRejectsExtraFieldsAndRoundTripsExplanation() {
        val encoded = JsonCodec.encodeToString(Action.serializer(), action)
        assertEquals(action, ToolSchema.calls("perform", encoded).single())
        val modified = encoded.dropLast(1) + ",\"grant\":true}"
        assertEquals("invalid_model_action", ToolSchema.decodeCall("call", "perform", modified).validationError)
    }
    private fun agentCase(enabled: Boolean, proposal: Action, test: (Agent, List<Op>, List<String>, List<JsonElement>) -> Unit) = runTest {
        val dispatched = mutableListOf<Op>(); val saved = mutableListOf<String>(); var received = emptyList<JsonElement>()
        val gateway = Gateway(Policy("own.app"), { PolicyConfig(apps = mapOf(app to AppRule(true, true))) },
            object : DevicePort {
                override suspend fun foregroundPackage() = app
                override suspend fun inspect(app: String) = screen
                override suspend fun execute(action: Action, screen: Screen): ToolResult {
                    dispatched += action.op
                    return if (action.op == Op.OBSERVE) ToolResult("observed", JsonCodec.encodeToString(Screen.serializer(), screen))
                    else ToolResult("explaining")
                }
            }, object : ApprovalPort { override suspend fun request(approval: Approval) = true })
        val provider = object : ModelProvider {
            override val supportsImages = false
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                received = input.toList()
                return Reply("", listOf(Call(id(), listOf(proposal))), emptyList())
            }
        }
        val agent = Agent(gateway, { text, _ -> saved += text })
        agent.start(this, provider, "Explain the table", screenContext = app, explainScreen = enabled)
        advanceUntilIdle()
        test(agent, dispatched, saved, received)
    }
    @Test fun oneModelCallStartsExplanationAndStoresCompleteTranscript() = agentCase(true, action) { agent, ops, saved, input ->
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals(plan.transcript, saved.single()); assertEquals(1, agent.metrics.value.modelCalls)
        assertEquals(listOf(Op.APPS, Op.OBSERVE, Op.EXPLAIN), ops)
        assertTrue(input.toString().contains("Explain aloud is enabled"))
    }
    @Test fun modelCannotEnableSpokenOverlayWithoutLocalOptIn() = agentCase(false, action) { agent, ops, _, _ ->
        assertEquals("explanation_not_enabled", agent.error.value); assertFalse(Op.EXPLAIN in ops)
    }
    @Test fun explanationCannotTapOrNavigateTheApp() = agentCase(true, Action(Op.OPEN, app)) { agent, ops, _, _ ->
        assertEquals("explanation_read_only", agent.error.value); assertFalse(Op.OPEN in ops)
    }
    @Test fun stoppedGatewayCannotDispatchExplanation() = runTest {
        var calls = 0
        val gateway = Gateway(Policy("own.app"), { PolicyConfig(allowAllApps = true) }, object : DevicePort {
            override suspend fun inspect(app: String) = screen
            override suspend fun execute(action: Action, screen: Screen): ToolResult { calls++; return ToolResult("explaining") }
        }, object : ApprovalPort { override suspend fun request(approval: Approval) = true })
        gateway.start(); gateway.stop()
        assertFailsWith<kotlinx.coroutines.CancellationException> { gateway.run(action) }
        assertEquals(0, calls)
    }
}
