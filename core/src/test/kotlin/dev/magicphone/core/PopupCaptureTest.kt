// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class PopupCaptureTest {
    private val pkg = "test.fixture"
    private class Device : DevicePort {
        var shots = 0
        var failShots = 0
        var failObservation = false
        var hardFailure = ""
        override suspend fun foregroundPackage() = "test.fixture"
        override suspend fun inspect(app: String) = Screen(id = "s", app = app, locked = false, mixed = false, focused = true)
        override suspend fun execute(action: Action, screen: Screen): ToolResult = when(action.op) {
            Op.OBSERVE -> if (failObservation) throw SafeFailure("screen_uncertain") else ToolResult("observed", JsonCodec.encodeToString(Screen.serializer(), screen))
            Op.SCREENSHOT -> {
                shots++
                if (hardFailure.isNotEmpty()) throw SafeFailure(hardFailure)
                if (shots <= failShots) throw SafeFailure("screenshot_throttled")
                ToolResult("captured", image = "data:image/jpeg;base64,dGVzdA==")
            }
            else -> ToolResult("reported")
        }
    }
    private fun agent(d: Device) = Agent(Gateway(Policy("own.app"), { PolicyConfig(apps = mapOf(pkg to AppRule(true, true))) }, d,
        object : ApprovalPort { override suspend fun request(approval: Approval) = true }), { _, _ -> })
    private fun provider(images: Boolean = true, respond: suspend (List<JsonElement>) -> Reply = { Reply("Read it", emptyList(), emptyList()) }) = object : ModelProvider {
        override val supportsImages = images
        override suspend fun models() = emptyList<ModelChoice>()
        override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit) = respond(input)
    }
    @Test fun screenshotPrecedesFirstModelCallWithoutDelayOnSuccess() = runTest {
        val d = Device(); val a = agent(d)
        a.start(this, provider { input ->
            assertEquals(1, d.shots); assertEquals(ScreenCaptureState.CAPTURED, a.screenCapture.value)
            assertTrue(input.toString().contains("input_image")); assertEquals(0, testScheduler.currentTime)
            Reply("Read it", emptyList(), emptyList())
        }, "this screen", screenContext = pkg, captureScreen = true)
        advanceUntilIdle(); assertEquals(RunState.COMPLETED, a.state.value)
    }
    @Test fun explicitPopupCaptureStillRunsForTextOnlyModelsWithoutSendingUnsupportedImages() = runTest {
        val d = Device(); val a = agent(d)
        a.start(this, provider(false) { input ->
            assertEquals(1, d.shots); assertFalse(input.toString().contains("input_image"))
            assertTrue(input.toString().contains("text_only_model"))
            Reply("Text read", emptyList(), emptyList())
        }, "read", screenContext = pkg, captureScreen = true)
        advanceUntilIdle(); assertEquals(ScreenCaptureState.CAPTURED, a.screenCapture.value)
    }
    @Test fun missingTextAndThrottledCaptureRecoverBeforeModelWithoutUserReply() = runTest {
        val d = Device().apply { failObservation = true; failShots = 2 }; val a = agent(d)
        var calls = 0
        a.start(this, provider { input ->
            calls++; assertEquals(3, d.shots); assertTrue(input.toString().contains("input_image"))
            Reply("Read visible pixels", emptyList(), emptyList())
        }, "read", screenContext = pkg, captureScreen = true)
        advanceUntilIdle(); assertEquals(1, calls); assertEquals(1050, testScheduler.currentTime)
        assertEquals(RunState.COMPLETED, a.state.value)
    }
    @Test fun exhaustedCaptureFallsBackAndSecureScreensNeverReachModel() = runTest {
        for (secure in listOf(false, true)) {
            val d = Device().apply { failShots = 9; if (secure) hardFailure = "secure_window" }; val a = agent(d)
            var calls = 0
            a.start(this, provider { calls++; Reply("Available text", emptyList(), emptyList()) }, "read", screenContext = pkg, captureScreen = true)
            advanceUntilIdle()
            assertEquals(if (secure) 1 else 3, d.shots)
            assertEquals(if (secure) 0 else 1, calls)
            assertEquals(if (secure) RunState.FAILED else RunState.COMPLETED, a.state.value)
            if (!secure) assertEquals(ScreenCaptureState.UNAVAILABLE, a.screenCapture.value)
        }
    }
    @Test fun stopDuringCaptureRecoveryPreventsModelCallAndClearsProgress() = runTest {
        val d = Device().apply { failShots = 9 }; val a = agent(d); var calls = 0
        a.start(backgroundScope, provider { calls++; error("Stopped run must not call model") }, "read", screenContext = pkg, captureScreen = true)
        runCurrent(); assertEquals(1, d.shots)
        a.stop(); advanceUntilIdle()
        assertEquals(0, calls); assertEquals(1, d.shots); assertEquals(ScreenCaptureState.NONE, a.screenCapture.value)
        assertEquals(RunState.STOPPED, a.state.value)
    }
    @Test fun supersededRunCannotPublishLateFailureOverNewPopupTask() = runTest {
        val d = Device(); val a = agent(d); val late = CompletableDeferred<Unit>()
        a.start(backgroundScope, provider { withContext(NonCancellable) { late.await() }; throw SafeFailure("old_error") }, "old")
        runCurrent()
        a.start(backgroundScope, provider { Reply("Fresh answer", emptyList(), emptyList()) }, "new", screenContext = pkg, captureScreen = true)
        runCurrent(); assertEquals(RunState.COMPLETED, a.state.value)
        late.complete(Unit); runCurrent()
        assertEquals(RunState.COMPLETED, a.state.value); assertEquals("", a.error.value)
    }
}
