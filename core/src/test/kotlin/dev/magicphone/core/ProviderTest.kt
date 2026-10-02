// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*

class ProviderTest {
    @Test
    fun selectedReasoningAndSpeedAreSentWithoutChangingModel() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val endpoint = server.url("/").toString().replace("localhost", "127.0.0.1")
            for ((model, effort, tier) in listOf(
                Triple("gpt-6-astra", "low", "ultrafast"),
                Triple("gpt-6-astra", "max", null),
                Triple("custom-unknown-model", null, null),
            )) {
                server.enqueue(MockResponse().setBody("data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n"))
                val p = ResponsesProvider(Profile(name = "test", kind = ProviderKind.OPENAI, model = model, reasoningEffort = effort, serviceTier = tier),
                    HttpTransport(endpoint, true)) {
                    BoundSecret(Destinations.origin(Destinations.url(endpoint, true)), "fixture-token")
                }
                p.respond(listOf(message("user", "test"))) {}
                val body = JsonCodec.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                assertEquals(model, body.str("model"))
                assertEquals(effort, (body["reasoning"] as? JsonObject)?.str("effort"))
                assertEquals(tier, (body["service_tier"] as? JsonPrimitive)?.content)
                assertEquals(JsonPrimitive(false), body["store"])
                assertEquals(JsonPrimitive(true), body["stream"])
            }
        } finally { server.shutdown() }
    }

    @Test
    fun streamedCompletedItemsSurviveAnEmptyTerminalOutputAndRequireSuccessfulCompletion() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val endpoint = server.url("/").toString().replace("localhost", "127.0.0.1")
            val provider = ResponsesProvider(
                Profile(name = "ChatGPT", kind = ProviderKind.CHATGPT, model = "account-model"),
                HttpTransport(endpoint, true),
            ) { BoundSecret(Destinations.origin(Destinations.url(endpoint, true)), "fixture-token") }
            val call = obj("type" to j("function_call"), "name" to j("perform"),
                "namespace" to j("phone"), "call_id" to j("call1"),
                "arguments" to j("{\"op\":\"APPS\"}"))
            val narration = obj("type" to j("message"), "role" to j("assistant"),
                "content" to JsonArray(listOf(obj("type" to j("output_text"), "text" to j("Opening the permitted app.")))))
            fun done(index: Int, item: JsonObject) = "data: ${obj("type" to j("response.output_item.done"), "output_index" to JsonPrimitive(index), "item" to item)}\n\n"
            val completed = "data: ${obj("type" to j("response.completed"), "response" to obj("output" to JsonArray(emptyList())))}\n\n"
            server.enqueue(MockResponse().setBody(done(1, call) + done(0, narration) + completed))
            val reply = provider.respond(listOf(message("user", "Open the test app"))) {}
            assertEquals(Op.APPS, reply.calls.single().actions.single().op)
            assertEquals("Opening the permitted app.", reply.text)
            assertEquals(listOf(narration, call), reply.output)
            assertTrue(reply.diagnostics.contains("finalItems=0,resolvedItems=2"))

            // A fully assembled call is still inert if the stream fails or never completes.
            server.enqueue(MockResponse().setBody(done(0, call)))
            assertEquals("stream_interrupted", assertFailsWith<SafeFailure> {
                provider.respond(listOf(message("user", "test"))) {}
            }.code)
            server.enqueue(MockResponse().setBody(done(0, call) + "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"))
            assertEquals("usage_limit", assertFailsWith<SafeFailure> {
                provider.respond(listOf(message("user", "test"))) {}
            }.code)
            server.enqueue(MockResponse().setBody(done(1, call) + completed))
            assertEquals("invalid_stream", assertFailsWith<SafeFailure> {
                provider.respond(listOf(message("user", "test"))) {}
            }.code)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun completedMessageAndRefusalRemainVisibleWithoutTextDeltas() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val endpoint = server.url("/").toString().replace("localhost", "127.0.0.1")
            val provider = ResponsesProvider(
                Profile(name = "ChatGPT", kind = ProviderKind.CHATGPT, model = "account-model"),
                HttpTransport(endpoint, true),
            ) { BoundSecret(Destinations.origin(Destinations.url(endpoint, true)), "fixture-token") }
            for (kind in listOf("output_text", "refusal")) {
                val content = obj("type" to j(kind),
                    (if (kind == "refusal") "refusal" else "text") to j("Visible fixture reply"))
                val output = JsonArray(listOf(obj("type" to j("message"),
                    "role" to j("assistant"), "content" to JsonArray(listOf(content)))))
                val terminal = obj("type" to j("response.completed"),
                    "response" to obj("output" to output))
                server.enqueue(MockResponse().setBody("data: $terminal\n\n"))
                val reply = provider.respond(listOf(message("user", "fixture task"))) {}
                assertEquals("Visible fixture reply", reply.text)
                assertTrue(reply.calls.isEmpty())
                assertEquals(output, reply.output)
                assertFalse(reply.diagnostics.contains("Visible fixture reply"))
                assertFalse(reply.diagnostics.contains("fixture-token"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun chatGptUsesNamespaceConstraintsAndCompleteToolResults() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val endpoint = server.url("/").toString().replace("localhost", "127.0.0.1")
            val provider =
                ResponsesProvider(
                    Profile(
                        name = "ChatGPT",
                        kind = ProviderKind.CHATGPT,
                        model = "account-model",
                        images = true,
                    ),
                    HttpTransport(endpoint, true),
                ) {
                    BoundSecret(
                        Destinations.origin(Destinations.url(endpoint, true)),
                        "fixture-token",
                    )
                }
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"models":[{"slug":"hidden","display_name":"Hidden","visibility":"hidden"},{"slug":"account-model","display_name":"Available","visibility":"list"}]}"""
                    )
            )
            assertEquals(listOf(ModelChoice("account-model", "Available")), provider.models())
            server.takeRequest()
            val output =
                obj(
                    "type" to j("function_call"),
                    "name" to j("perform"),
                    "namespace" to j("phone"),
                    "call_id" to j("c1"),
                    "arguments" to j("{\"op\":\"PLAN\",\"text\":\"Read first\"}"),
                )
            val terminal =
                obj(
                    "type" to j("response.completed"),
                    "response" to
                        obj(
                            "output" to JsonArray(listOf(output)),
                            "usage" to
                                obj(
                                    "input_tokens" to JsonPrimitive(12),
                                    "output_tokens" to JsonPrimitive(3),
                                ),
                        ),
                )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\ndata: $terminal\n\n"
                    )
            )
            var streamed = ""
            val reply = provider.respond(listOf(message("user", "test"))) { streamed += it }
            assertEquals("Hello", streamed)
            assertEquals(Op.PLAN, reply.calls.single().actions.single().op)
            assertEquals("12 in · 3 out", reply.usage)
            val request = server.takeRequest()
            assertEquals("/responses", request.path)
            val body = JsonCodec.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive(false), body["store"])
            assertEquals(JsonPrimitive(true), body["stream"])
            assertEquals("namespace", body["tools"]!!.jsonArray.single().jsonObject.str("type"))
            for (field in
                listOf(
                    "previous_response_id",
                    "conversation",
                    "temperature",
                    "max_output_tokens",
                    "background",
                    "metadata",
                )) assertFalse(body.containsKey(field))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun streamedUsageFailureCannotReturnToolCalls() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val endpoint = server.url("/").toString().replace("localhost", "127.0.0.1")
            val provider =
                ResponsesProvider(
                    Profile(name = "ChatGPT", kind = ProviderKind.CHATGPT, model = "account-model"),
                    HttpTransport(endpoint, true),
                ) {
                    BoundSecret(
                        Destinations.origin(Destinations.url(endpoint, true)),
                        "fixture-token",
                    )
                }
            server.enqueue(
                MockResponse()
                    .setBody(
                        "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"
                    )
            )
            assertEquals(
                "usage_limit",
                assertFailsWith<SafeFailure> {
                        provider.respond(listOf(message("user", "test"))) {}
                    }
                    .code,
            )
            server.enqueue(
                MockResponse()
                    .setBody(
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
                    )
            )
            assertEquals(
                "stream_interrupted",
                assertFailsWith<SafeFailure> {
                        provider.respond(listOf(message("user", "test"))) {}
                    }
                    .code,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun compatibleToolArgumentDeltasAreCombinedOnlyAtCompletion() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val endpoint = server.url("/").toString().replace("localhost", "127.0.0.1")
            val provider =
                CompatibleProvider(
                    Profile(
                        name = "local",
                        kind = ProviderKind.COMPATIBLE,
                        endpoint = endpoint,
                        localOptIn = true,
                        model = "fixture",
                    )
                ) {
                    null
                }
            val first =
                obj(
                    "choices" to
                        JsonArray(
                            listOf(
                                obj(
                                    "delta" to
                                        obj(
                                            "tool_calls" to
                                                JsonArray(
                                                    listOf(
                                                        obj(
                                                            "index" to JsonPrimitive(0),
                                                            "id" to j("call"),
                                                            "function" to
                                                                obj(
                                                                    "name" to j("perform"),
                                                                    "arguments" to j("{\"op\":"),
                                                                ),
                                                        )
                                                    )
                                                )
                                        ),
                                    "finish_reason" to JsonNull,
                                )
                            )
                        )
                )
            val second =
                obj(
                    "choices" to
                        JsonArray(
                            listOf(
                                obj(
                                    "delta" to
                                        obj(
                                            "tool_calls" to
                                                JsonArray(
                                                    listOf(
                                                        obj(
                                                            "index" to JsonPrimitive(0),
                                                            "function" to
                                                                obj("arguments" to j("\"PLAN\"}")),
                                                        )
                                                    )
                                                )
                                        ),
                                    "finish_reason" to j("tool_calls"),
                                )
                            )
                        )
                )
            server.enqueue(
                MockResponse().setBody("data: $first\n\ndata: $second\n\ndata: [DONE]\n\n")
            )
            assertEquals(
                Op.PLAN,
                provider
                    .respond(listOf(message("user", "plan"))) {}
                    .calls
                    .single()
                    .actions
                    .single()
                    .op,
            )
        } finally {
            server.shutdown()
        }
    }
}
