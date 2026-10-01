// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class AgentTest {
    private fun gate(approve: suspend (Approval) -> Boolean = { true }) =
        Gateway(
            Policy("own.app"),
            { PolicyConfig(apps = mapOf("test.app" to AppRule(true, true)), planOnly = false) },
            object : DevicePort {
                override suspend fun inspect(app: String) =
                    Screen(app = app, locked = false, mixed = false)

                override suspend fun execute(action: Action, screen: Screen) = ToolResult("updated")
            },
            object : ApprovalPort {
                override suspend fun request(approval: Approval) = approve(approval)
            },
        )

    private fun provider(next: suspend (List<JsonElement>) -> Action) =
        object : ModelProvider {
            override val supportsImages = false

            override suspend fun models() = emptyList<ModelChoice>()

            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                val action = next(input)
                return Reply("", listOf(Call(id(), listOf(action))), emptyList())
            }
        }

    @Test
    fun repeatedPauseWhileReplyArrivesKeepsSameResumeSignal() = runTest {
        val replyReady = CompletableDeferred<Unit>()
        val agent = Agent(gate(), { _, _ -> })
        agent.start(backgroundScope, provider {
            replyReady.await()
            Action(Op.COMPLETE, text = "done")
        }, "task")
        runCurrent()
        agent.pause()
        replyReady.complete(Unit)
        runCurrent()
        assertEquals(RunState.PAUSED, agent.state.value)
        assertTrue(agent.actions.value.isEmpty())
        agent.pause()
        agent.resume()
        runCurrent()
        assertEquals(RunState.COMPLETED, agent.state.value)
    }

    @Test
    fun textReplyDoesNotReplacePauseWhenOpeningChat() = runTest {
        val replyReady = CompletableDeferred<Unit>()
        val agent = Agent(gate(), { _, _ -> })
        val p = object : ModelProvider {
            override val supportsImages = false
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit): Reply {
                replyReady.await()
                return Reply("Which app?", emptyList(), emptyList())
            }
        }
        agent.start(backgroundScope, p, "task")
        runCurrent()
        agent.pause()
        replyReady.complete(Unit)
        runCurrent()
        assertEquals(RunState.PAUSED, agent.state.value)
        assertEquals("", agent.question.value)
        agent.resume()
        runCurrent()
        assertEquals(RunState.WAITING_USER, agent.state.value)
        assertEquals("Which app?", agent.question.value)
    }

    @Test
    fun emptyReplyFailsVisiblyInsteadOfWaitingForAnInvisibleQuestion() = runTest {
        val saved = mutableListOf<String>()
        val agent = Agent(gate(), { text, _ -> saved += text })
        val empty = object : ModelProvider {
            override val supportsImages = false
            override suspend fun models() = emptyList<ModelChoice>()
            override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit) =
                Reply("", emptyList(), emptyList(), diagnostics = "events=2")
        }
        agent.start(backgroundScope, empty, "Open the test app")
        runCurrent()
        assertEquals(RunState.FAILED, agent.state.value)
        assertEquals("empty_model_response", agent.error.value)
        assertEquals(listOf("empty_model_response"), saved)
        assertEquals("events=2", agent.diagnostics.value)
        assertTrue(agent.actions.value.isEmpty())
    }

    @Test
    fun oldCancelledRunCannotStopNewRun() = runTest {
        val agent = Agent(gate(), { _, _ -> })
        agent.start(
            backgroundScope,
            provider {
                delay(10000)
                Action(Op.COMPLETE)
            },
            "first",
        )
        runCurrent()
        agent.start(backgroundScope, provider { Action(Op.COMPLETE, text = "second") }, "second")
        runCurrent()
        assertEquals(RunState.COMPLETED, agent.state.value)
    }

    @Test
    fun correctionWhileApprovalWaitsReplans() = runTest {
        val answer = CompletableDeferred<Boolean>()
        var rounds = 0
        var sawCorrection = false
        val agent = Agent(gate { answer.await() }, { _, _ -> })
        val p = provider { input ->
            rounds++
            if (rounds == 1) Action(Op.OPEN, "test.app")
            else {
                sawCorrection = input.any { it.toString().contains("new instruction") }
                Action(Op.COMPLETE, text = "corrected")
            }
        }
        agent.start(backgroundScope, p, "initial")
        runCurrent()
        agent.correct("new instruction")
        answer.complete(false)
        runCurrent()
        assertTrue(sawCorrection)
        assertEquals(RunState.COMPLETED, agent.state.value)
    }

    @Test
    fun failedProviderNeverDispatches() = runTest {
        var dispatch = 0
        val g =
            Gateway(
                Policy("own.app"),
                { PolicyConfig() },
                object : DevicePort {
                    override suspend fun inspect(app: String) = Screen()

                    override suspend fun execute(action: Action, screen: Screen): ToolResult {
                        dispatch++
                        return ToolResult("x")
                    }
                },
                object : ApprovalPort {
                    override suspend fun request(approval: Approval) = true
                },
            )
        val agent = Agent(g, { _, _ -> })
        agent.start(backgroundScope, provider { throw SafeFailure("stream_interrupted") }, "task")
        runCurrent()
        assertEquals(0, dispatch)
        assertEquals(RunState.FAILED, agent.state.value)
    }

    @Test
    fun completionRequiresObservationAfterMutation() = runTest {
        val g =
            Gateway(
                Policy("own.app"),
                { PolicyConfig(apps = mapOf("test.app" to AppRule(true, true)), planOnly = false) },
                object : DevicePort {
                    override suspend fun inspect(app: String) = Screen(app = app, locked = false)

                    override suspend fun execute(action: Action, screen: Screen) =
                        ToolResult("dispatched")
                },
                object : ApprovalPort {
                    override suspend fun request(approval: Approval) = true
                },
            )
        var round = 0
        val agent = Agent(g, { _, _ -> })
        agent.start(
            backgroundScope,
            provider {
                if (round++ == 0) Action(Op.OPEN, "test.app")
                else Action(Op.COMPLETE, text = "It worked")
            },
            "task",
        )
        runCurrent()
        assertEquals("verification_required", agent.error.value)
        assertEquals(RunState.FAILED, agent.state.value)
    }
}
