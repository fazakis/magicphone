// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class PopupActionPolicyTest {
    private val pkg = "test.fixture"
    private val screen = Screen(id = "s", app = pkg, width = 200, height = 300, locked = false, mixed = false, focused = true,
        nodes = listOf(Node("s:0", "field", Rect(5, 5, 100, 100), true, true, false)))
    @Test fun localSensitiveSwitchDefaultsOnAndOnlyRelaxesSensitiveChecks() {
        val policy = Policy("own.app")
        val enabled = JsonCodec.decodeFromString<PolicyConfig>("{}")
        assertTrue(enabled.checkSensitiveContent)
        val conf = PolicyConfig(allowAllApps = true, checkSensitiveContent = false)
        val sensitive = screen.copy(sensitive = true, nodes = screen.nodes.map { it.copy(sensitive = true) })
        for (action in listOf(Action(Op.OBSERVE, pkg), Action(Op.SCREENSHOT, pkg, "s"), Action(Op.TEXT, pkg, "s", "s:0", "fixture"))) {
            assertEquals(Decision.Allow, policy.decide(action, sensitive, conf, 0))
            assertEquals(Decision.Deny("manual_secret"), policy.decide(action, sensitive, conf.copy(checkSensitiveContent = true), 0))
        }
        val tap = Action(Op.TAP, pkg, "s", x = 20, y = 20)
        for (s in listOf(sensitive.copy(locked = true), sensitive.copy(mixed = true), sensitive.copy(focused = false),
            sensitive.copy(protectedRects = listOf(Rect(0, 0, 100, 100)))))
            assertIs<Decision.Deny>(policy.decide(tap, s, conf, 0))
        assertIs<Decision.Deny>(policy.decide(tap.copy(snapshot = "old"), sensitive, conf, 0))
        assertIs<Decision.Deny>(policy.decide(tap, sensitive, conf.copy(apps = mapOf(pkg to AppRule(deny = true))), 0))
        assertIs<Decision.Deny>(policy.decide(Action(Op.OPEN, "com.android.settings"), sensitive, conf, 0))
        assertFalse(JsonCodec.decodeFromString<PolicyConfig>(JsonCodec.encodeToString(PolicyConfig.serializer(), conf)).checkSensitiveContent)
    }
    @Test fun unobstructedScopeCoversInspectionApprovalAndDispatchAndAlwaysRestores() = runTest {
        var hidden = false; var restores = 0; var dispatches = 0
        val device = object : DevicePort {
            override suspend fun <T> withUnobstructedScreen(block: suspend () -> T): T {
                hidden = true
                try { return block() } finally { hidden = false; restores++ }
            }
            override suspend fun inspect(app: String): Screen { assertTrue(hidden); return screen }
            override suspend fun execute(action: Action, screen: Screen): ToolResult { assertTrue(hidden); dispatches++; return ToolResult("dispatched") }
        }
        val gateway = Gateway(Policy("own.app"), { PolicyConfig(apps = mapOf(pkg to AppRule(true, true))) }, device,
            object : ApprovalPort { override suspend fun request(approval: Approval): Boolean { assertTrue(hidden); return true } })
        gateway.start(); gateway.run(Action(Op.TAP, pkg, "s", "s:0"))
        assertFalse(hidden); assertEquals(1, dispatches); assertEquals(1, restores)
        assertFailsWith<SafeFailure> { gateway.run(Action(Op.TAP, pkg, "old", "s:0")) }
        assertFalse(hidden); assertEquals(2, restores); assertEquals(1, dispatches)
        gateway.stop(); assertFailsWith<CancellationException> { gateway.run(Action(Op.OBSERVE, pkg)) }
        assertFalse(hidden); assertEquals(3, restores)
    }
    @Test fun popupCanOpenAnotherPermittedAppAndUsesOnlyLatestScreenImage() = runTest {
        var foreground = pkg; var version = 0; var shots = 0; var reads = 0
        val device = object : DevicePort {
            override suspend fun foregroundPackage() = foreground
            override suspend fun inspect(app: String) = screen.copy(app = app)
            override suspend fun execute(action: Action, screen: Screen): ToolResult = when(action.op) {
                Op.OPEN -> { foreground = action.app; version++; ToolResult("dispatched") }
                Op.OBSERVE -> { reads++; ToolResult("observed", JsonCodec.encodeToString(Screen.serializer(), screen)) }
                Op.SCREENSHOT -> { shots++; ToolResult("captured", image = "data:image/jpeg;base64,screen$version") }
                else -> ToolResult("reported")
            }
        }
        val g = Gateway(Policy("own.app"), { PolicyConfig(allowAllApps = true) }, device,
            object : ApprovalPort { override suspend fun request(approval: Approval): Boolean = error("Automatic mode") })
        var rounds = 0
        val provider = object : ModelProvider {
            override val supportsImages = true
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                val images = input.flatMap { ((it as? JsonObject)?.get("content") as? JsonArray).orEmpty() }
                    .filterIsInstance<JsonObject>().filter { it.str("type") == "input_image" }
                assertEquals(1, images.size)
                assertTrue(images.single().str("image_url").endsWith("screen$version"))
                return Reply("", listOf(Call(id(), listOf(if (rounds++ == 0) Action(Op.OPEN, "test.second") else Action(Op.COMPLETE, text = "Verified")))), emptyList())
            }
        }
        val a = Agent(g, { _, _ -> }); a.start(this, provider, "Open the other app", screenContext = pkg, captureScreen = true)
        advanceUntilIdle(); assertEquals(RunState.COMPLETED, a.state.value)
        assertEquals("test.second", foreground); assertEquals(2, shots); assertEquals(2, reads)
    }
    @Test fun popupDefersPrematureCompletionThenVerifiesWithoutRepeatingMutation() = runTest {
        var taps = 0; var failedRead = false; var rounds = 0
        val device = object : DevicePort {
            override suspend fun foregroundPackage() = pkg
            override suspend fun inspect(app: String) = screen
            override suspend fun execute(action: Action, screen: Screen): ToolResult = when (action.op) {
                Op.TAP -> { taps++; ToolResult("dispatched") }
                Op.OBSERVE -> {
                    if (taps > 0 && !failedRead) { failedRead = true; throw SafeFailure("screen_uncertain") }
                    ToolResult("observed", JsonCodec.encodeToString(Screen.serializer(), screen))
                }
                Op.SCREENSHOT -> ToolResult("captured", image = "data:image/jpeg;base64,fixture")
                else -> ToolResult("reported")
            }
        }
        val gateway = Gateway(Policy("own.app"), { PolicyConfig(allowAllApps = true) }, device,
            object : ApprovalPort { override suspend fun request(approval: Approval) = true })
        val p = object : ModelProvider {
            override val supportsImages = true
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                val action = when (rounds++) {
                    0 -> Action(Op.TAP, pkg, "s", "s:0")
                    1 -> Action(Op.COMPLETE, text = "Too soon")
                    2 -> {
                        assertTrue(input.toString().contains("verification_required"))
                        Action(Op.OBSERVE, pkg)
                    }
                    else -> Action(Op.COMPLETE, text = "Verified")
                }
                return Reply("", listOf(Call(id(), listOf(action))), emptyList())
            }
        }
        val a = Agent(gateway, { _, _ -> }); a.start(this, p, "tap", screenContext = pkg, captureScreen = true)
        advanceUntilIdle(); assertEquals(RunState.COMPLETED, a.state.value)
        assertEquals(1, taps); assertEquals(4, rounds); assertTrue(failedRead)
    }

    @Test fun malformedModelCallsAreReturnedForCorrectionWithoutDispatchingValidSiblings() = runTest {
        var dispatched = 0; var rounds = 0
        val device = object : DevicePort {
            override suspend fun inspect(app: String) = screen
            override suspend fun execute(action: Action, screen: Screen): ToolResult {
                if (action.op == Op.OPEN) dispatched++
                return if (action.op == Op.OBSERVE) ToolResult("observed", JsonCodec.encodeToString(Screen.serializer(), screen)) else ToolResult("reported")
            }
        }
        val gateway = Gateway(Policy("own.app"), { PolicyConfig(allowAllApps = true) }, device,
            object : ApprovalPort { override suspend fun request(approval: Approval) = true })
        val provider = object : ModelProvider {
            override val supportsImages = false
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                return when (rounds++) {
                    0 -> Reply("", listOf(Call("valid", listOf(Action(Op.OPEN, pkg))), ToolSchema.decodeCall("bad", "perform", "{broken")), emptyList())
                    else -> {
                        assertEquals(0, dispatched)
                        assertTrue(input.toString().contains("invalid_model_action"))
                        assertTrue(input.toString().contains("cancelled_invalid_response"))
                        Reply("", listOf(Call("done", listOf(Action(Op.COMPLETE, text = "Corrected")))), emptyList())
                    }
                }
            }
        }
        val a = Agent(gateway, { _, _ -> }); a.start(this, provider, "task")
        advanceUntilIdle(); assertEquals(RunState.COMPLETED, a.state.value); assertEquals(2, rounds); assertEquals(0, dispatched)
    }
    @Test fun persistentMalformedRequestsAreBoundedAndNeverLeakArguments() = runTest {
        val invalid = listOf(ToolSchema.decodeCall("a", "perform", "{broken private-payload"),
            ToolSchema.decodeCall("b", "perform", "{\"op\":\"TAP\",\"app\":\"test.fixture\",\"snapshot\":\"s\",\"x\":-1,\"y\":20}"),
            ToolSchema.decodeCall("c", "unknown", "{}"), ToolSchema.decodeCall("d", "perform", "{\"op\":\"COMPLETE\"}", "other"))
        assertTrue(invalid.all { it.validationError == "invalid_model_action" && it.actions.isEmpty() })
        assertFalse(invalid.toString().contains("private-payload"))
        var rounds = 0
        val gateway = Gateway(Policy("own.app"), { PolicyConfig() }, object : DevicePort {
            override suspend fun inspect(app: String): Screen = error("Never inspect")
            override suspend fun execute(action: Action, screen: Screen): ToolResult = error("Never dispatch")
        }, object : ApprovalPort { override suspend fun request(approval: Approval) = false })
        val p = object : ModelProvider {
            override val supportsImages = false
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                rounds++; return Reply("", listOf(invalid.first()), emptyList())
            }
        }
        val a = Agent(gateway, { _, _ -> }); a.start(this, p, "task")
        advanceUntilIdle(); assertEquals("invalid_model_action", a.error.value); assertEquals(3, rounds)
    }
    @Test fun stopAndRestartDuringOverlayRemovalCannotDispatchOldRun() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var dispatched = false
        val gateway = Gateway(Policy("own.app"), { PolicyConfig(allowAllApps = true) }, object : DevicePort {
            override suspend fun <T> withUnobstructedScreen(block: suspend () -> T): T {
                entered.complete(Unit); release.await(); return block()
            }
            override suspend fun inspect(app: String) = screen
            override suspend fun execute(action: Action, screen: Screen): ToolResult { dispatched = true; return ToolResult("dispatched") }
        }, object : ApprovalPort { override suspend fun request(approval: Approval) = true })
        gateway.start()
        val old = async { gateway.run(Action(Op.TAP, pkg, "s", "s:0")) }
        entered.await(); gateway.stop(); gateway.start(); release.complete(Unit)
        assertFailsWith<CancellationException> { old.await() }; assertFalse(dispatched)
    }

    @Test fun largeScreenshotKeepsLatestToolObservationAndMatchingCall() = runTest {
        var version = 0; var rounds = 0
        val device = object : DevicePort {
            override suspend fun foregroundPackage() = pkg
            override suspend fun inspect(app: String) = screen.copy(id = "screen$version",
                nodes = listOf(screen.nodes.single().copy(ref = "screen$version:0", label = "Counter $version")))
            override suspend fun execute(action: Action, screen: Screen): ToolResult = when(action.op) {
                Op.TAP -> { version++; ToolResult("dispatched") }
                Op.OBSERVE -> ToolResult("observed", JsonCodec.encodeToString(Screen.serializer(), screen))
                Op.SCREENSHOT -> ToolResult("captured", image = "data:image/jpeg;base64," + "A".repeat(250000))
                else -> ToolResult("reported")
            }
        }
        val g = Gateway(Policy("own.app"), { PolicyConfig(allowAllApps = true) }, device,
            object : ApprovalPort { override suspend fun request(approval: Approval) = true })
        val p = object : ModelProvider {
            override val supportsImages = true
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                if (rounds++ > 0) {
                    assertTrue(input.any { (it as? JsonObject)?.str("type") == "function_call_output" && it.toString().contains("Counter 1") }, "Large screenshot discarded the newest verified screen")
                    assertTrue(input.any { (it as? JsonObject)?.str("call_id") == "tap" && it.jsonObject.str("type") == "function_call" })
                    return Reply("", listOf(Call("done", listOf(Action(Op.COMPLETE, text = "Verified")))), emptyList())
                }
                return Reply("", listOf(Call("tap", listOf(Action(Op.TAP, pkg, "screen0", "screen0:0")))),
                    listOf(obj("type" to j("function_call"), "call_id" to j("tap"), "name" to j("perform"), "arguments" to j("{}"))))
            }
        }
        val a = Agent(g, { _, _ -> }); a.start(this, p, "Tap once", screenContext = pkg, captureScreen = true)
        advanceUntilIdle(); assertEquals(RunState.COMPLETED, a.state.value); assertEquals(1, version)
    }

    @Test fun textCompactionPreservesEntireLatestToolRoundWhenScreenImageIsReplaced() = runTest {
        var version = 0; var rounds = 0
        val device = object : DevicePort {
            override suspend fun foregroundPackage() = pkg
            override suspend fun inspect(app: String) = screen.copy(id = "screen$version",
                nodes = listOf(screen.nodes.single().copy(ref = "screen$version:0", label = "Counter $version")))
            override suspend fun execute(action: Action, screen: Screen): ToolResult = when(action.op) {
                Op.TAP -> { version++; ToolResult("dispatched") }
                Op.OBSERVE -> ToolResult("observed", JsonCodec.encodeToString(Screen.serializer(), screen))
                Op.SCREENSHOT -> ToolResult("captured", image = "data:image/jpeg;base64,screen$version")
                else -> ToolResult("reported")
            }
        }
        val g = Gateway(Policy("own.app"), { PolicyConfig(allowAllApps = true) }, device,
            object : ApprovalPort { override suspend fun request(approval: Approval) = true })
        val p = object : ModelProvider {
            override val supportsImages = true
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                if (rounds++ > 0) {
                    assertTrue(input.toString().contains("Local context compaction."))
                    for (type in listOf("function_call", "function_call_output"))
                        assertEquals(listOf("tap", "observe"), input.filter { (it as? JsonObject)?.str("type") == type }.map { it.jsonObject.str("call_id") })
                    assertTrue(input.any { (it as? JsonObject)?.str("type") == "function_call_output" && it.toString().contains("Counter 1") })
                    assertFalse(input.toString().contains("base64,screen0"))
                    assertEquals(1, Regex("base64,screen1").findAll(input.toString()).count())
                    return Reply("", listOf(Call("done", listOf(Action(Op.COMPLETE, text = "Verified")))), emptyList())
                }
                return Reply("", listOf(Call("tap", listOf(Action(Op.TAP, pkg, "screen0", "screen0:0"))), Call("observe", listOf(Action(Op.OBSERVE, pkg)))),
                    listOf(message("assistant", "Large prior output " + "x".repeat(170000))) + listOf("tap", "observe").map {
                        obj("type" to j("function_call"), "call_id" to j(it), "name" to j("perform"), "arguments" to j("{}")) })
            }
        }
        val a = Agent(g, { _, _ -> }); a.start(this, p, "Tap once", screenContext = pkg, captureScreen = true)
        advanceUntilIdle(); assertEquals(RunState.COMPLETED, a.state.value); assertEquals(1, version)
    }

}
