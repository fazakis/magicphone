// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationContextTest {
    private val photo = Attachment("a".repeat(64))
    private val image = "data:image/jpeg;base64,c3ludGhldGlj"
    private val bill = Message(role = "user", text = "Synthetic bill, customer 00001234567890123", attachments = listOf(photo))

    private fun gate(history: List<Message> = listOf(bill)) = Gateway(Policy("own.app"), { PolicyConfig() },
        object : DevicePort {
            override suspend fun inspect(app: String) = error("Conversation retrieval must not inspect Android")
            override suspend fun execute(action: Action, screen: Screen) =
                if (action.op == Op.RECALL) ConversationContext.recall(history, action) { image } else ToolResult("reported")
        }, object : ApprovalPort { override suspend fun request(approval: Approval) = error("Read-only conversation retrieval") })

    private fun provider(next: suspend (List<JsonElement>) -> Action) = object : ModelProvider {
        override val supportsImages = true
        override suspend fun models() = emptyList<ModelChoice>()
        override suspend fun respond(input: List<JsonElement>, delta: (String) -> Unit) =
            Reply("", listOf(Call(id(), listOf(next(input)))), emptyList())
    }

    @Test fun completedTaskFollowupGetsOldPhotoBeyondTwentyMessages() = runTest {
        val history = listOf(bill) + List(40) { Message(role = "assistant", text = "Earlier step $it") }
        val saved = mutableListOf<String>()
        val agent = Agent(gate(history), { text, _ -> saved += text })
        agent.start(backgroundScope, provider { input ->
            assertTrue(input.toString().contains(image))
            assertTrue(input.toString().contains("00001234567890123"))
            Action(Op.COMPLETE, text = "Customer 00001234567890123")
        }, "Use the earlier bill", history)
        runCurrent()
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertEquals("Customer 00001234567890123", saved.last())
    }

    @Test fun photoInAnswerToAskReachesNextModelRequest() = runTest {
        var calls = 0
        val agent = Agent(gate(), { _, _ -> })
        agent.start(backgroundScope, provider { input ->
            if (calls++ == 0) Action(Op.ASK, text = "Attach the synthetic bill") else {
                assertTrue(input.toString().contains(image))
                assertTrue(input.toString().contains("Here it is"))
                Action(Op.COMPLETE, text = "Read")
            }
        }, "Read a bill")
        runCurrent()
        assertEquals(RunState.WAITING_USER, agent.state.value)
        assertTrue(agent.correct("Here it is", listOf(image)))
        runCurrent()
        assertEquals(RunState.COMPLETED, agent.state.value)
    }

    @Test fun photoCorrectionCancelsPendingActionAndPausedReplyResumes() = runTest {
        val ready = CompletableDeferred<Unit>()
        var calls = 0
        val agent = Agent(gate(), { _, _ -> })
        agent.start(backgroundScope, provider { input ->
            if (calls++ == 0) { ready.await(); Action(Op.ASK, text = "Old proposal") } else {
                assertTrue(input.toString().contains(image))
                Action(Op.COMPLETE, text = "New photo used")
            }
        }, "Task")
        runCurrent()
        agent.pause()
        assertTrue(agent.correct("Use this instead", listOf(image)))
        ready.complete(Unit)
        runCurrent()
        assertEquals(RunState.COMPLETED, agent.state.value)
        assertFalse(agent.actions.value.any { it.operation == Op.ASK })
    }

    @Test fun olderTextRemainsRetrievableAfterBudgetCompaction() = runTest {
        val history = listOf(bill) + List(100) { Message(role = "assistant", text = "Unrelated explanation ".repeat(100)) }
        val context = ConversationContext.build(history, "Enter customer details")
        assertTrue(context.toString().contains("00001234567890123"))
        assertTrue(context.toString().contains(bill.id))
        assertTrue(context.toString().length < 110_000)
        val result = ConversationContext.recall(history, Action(Op.RECALL, text = "customer")) { error("Text search loads no images") }
        assertTrue(result.content.contains("00001234567890123"))
    }

    @Test fun recallCannotEscapeConversationOrAccessImportedPhoto() = runTest {
        var loads = 0
        val other = Action(Op.RECALL, node = "b".repeat(64))
        assertNull(ConversationContext.recall(listOf(bill), other) { loads++; image }.image)
        val imported = Archives.previewImport(JsonCodec.encodeToString(Archive.serializer(),
            Archive(conversations = listOf(Conversation(title = "Bill", messages = listOf(bill))))).toByteArray())
        val messages = imported.conversations.single().messages
        assertFalse(messages.single().attachments.single().available)
        assertNull(ConversationContext.recall(messages, Action(Op.RECALL, node = photo.id)) { loads++; image }.image)
        assertEquals(0, loads)
    }

    @Test fun repeatedPhotoReferencesKeepCatalogBounded() {
        val history = List(1000) { bill.copy(id = id(), text = "Bill reply $it") }
        val input = ConversationContext.build(history, "Read the bill").toString()
        assertEquals(1, Regex("Photo ${photo.id}").findAll(input).count())
        assertTrue(input.length < 120000)
    }

    @Test fun schemasMigrateAndPhotoReferencesValidateWithoutImagePayloads() {
        for (schema in 1..2) assertEquals(3, Archives.read("{\"schema\":$schema}".toByteArray()).schema)
        val a = Archive(conversations = listOf(Conversation(title = "Bill", messages = listOf(bill))))
        val encoded = JsonCodec.encodeToString(Archive.serializer(), a)
        assertEquals(photo, Archives.read(encoded.toByteArray()).conversations.single().messages.single().attachments.single())
        assertFalse(encoded.contains("base64"))
        val invalid = a.copy(conversations = listOf(a.conversations.single().copy(messages = listOf(bill.copy(attachments = listOf(Attachment("../secret")))))))
        assertFails { Archives.read(JsonCodec.encodeToString(Archive.serializer(), invalid).toByteArray()) }
    }

    @Test fun retainDocumentReferencesWhileRedactingExplicitCredentials() {
        val original = "Receipt: 00001234567890123\nΚωδικός καταναλωτή: 0000012345678\npassword=hunter2\nOTP=123456\ncard: 4111111111111111"
        val safe = Sanitizer.conversation(original)
        assertTrue(safe.contains("00001234567890123"))
        assertTrue(safe.contains("0000012345678"))
        for (secret in listOf("hunter2", "123456\n", "4111111111111111")) assertFalse(safe.contains(secret))
        assertFalse(Sanitizer.text(original).contains("00001234567890123"))
    }
}
