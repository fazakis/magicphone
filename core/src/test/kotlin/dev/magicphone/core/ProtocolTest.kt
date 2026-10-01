// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.*

class ProtocolTest {
    private fun response(body: String) =
        Response.Builder()
            .request(Request.Builder().url("https://example.org").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("text/event-stream".toMediaType()))
            .build()

    @Test
    fun sseHandlesCommentsCrLfAndMultiline() {
        val events = mutableListOf<JsonObject>()
        response(": hi\r\ndata: {\r\ndata: \"type\":\"ok\"}\r\n\r\ndata: [DONE]\n\n").use {
            Sse.read(it, events::add)
        }
        assertEquals("ok", events.single().str("type"))
    }

    @Test
    fun sseDoesNotAcceptTruncatedEvent() {
        val events = mutableListOf<JsonObject>()
        response("data: {\"type\":\"function_call\"}\n").use { Sse.read(it, events::add) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun sseRejectsOversizedPayload() {
        response("data: " + "x".repeat(1048577) + "\n\n").use { assertFails { Sse.read(it) {} } }
    }

    @Test
    fun refreshRotatesAndSerializesTokens() = runTest {
        val s = MockWebServer()
        s.start()
        try {
            s.enqueue(
                MockResponse()
                    .setBody(
                        """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"scope":"chatgpt.tokens.use.direct"}"""
                    )
            )
            val auth =
                ChatGptAuth(
                    HttpTransport(s.url("/").toString().replace("localhost", "127.0.0.1"), true)
                )
            var account =
                Account(
                    "subject",
                    "email",
                    "oaiapp_test",
                    refresh = "old-refresh",
                    scopes = "chatgpt.tokens.use.direct",
                )
            coroutineScope { repeat(2) { launch { auth.fresh({ account }, { account = it }) } } }
            assertEquals("new-refresh", account.refresh)
            assertEquals(1, s.requestCount)
            val body = s.takeRequest().body.readUtf8()
            assertTrue(body.contains("grant_type=refresh_token"))
            assertTrue(body.contains("client_id=oaiapp_test"))
            assertFalse(body.contains("scope="))
            assertFalse(body.contains("dynamic_agent_client"))
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun logoutUsesDiscoveryAndRefreshToken() = runTest {
        val s = MockWebServer()
        s.start()
        try {
            s.enqueue(
                MockResponse()
                    .setBody(
                        """{"revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke"}"""
                    )
            )
            s.enqueue(MockResponse().setResponseCode(200))
            val auth =
                ChatGptAuth(
                    HttpTransport(s.url("/").toString().replace("localhost", "127.0.0.1"), true)
                )
            assertTrue(auth.logout(Account("sub", "e", "oaiapp_test", refresh = "r")))
            assertEquals("/.well-known/openid-configuration", s.takeRequest().path)
            val request = s.takeRequest()
            assertEquals("/api/accounts/oauth/revoke", request.path)
            assertTrue(request.body.readUtf8().contains("token_type_hint=refresh_token"))
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun refreshMissingConsentDoesNotOverwriteAccount() = runTest {
        val s = MockWebServer()
        s.start()
        try {
            s.enqueue(MockResponse().setBody("""{"access_token":"x","scope":"openid"}"""))
            var saved = false
            val auth =
                ChatGptAuth(
                    HttpTransport(s.url("/").toString().replace("localhost", "127.0.0.1"), true)
                )
            assertFails {
                auth.fresh({ Account("s", "e", "oaiapp_test", refresh = "r") }, { saved = true })
            }
            assertFalse(saved)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun changedMcpSchemaCannotExecute() = runTest {
        val s = MockWebServer()
        s.start()
        try {
            s.enqueue(
                MockResponse()
                    .setBody(
                        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}"""
                    )
            )
            s.enqueue(MockResponse().setResponseCode(202))
            s.enqueue(
                MockResponse()
                    .setBody(
                        """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"echo","description":"Changed!","inputSchema":{"type":"object","properties":{}}}]}}"""
                    )
            )
            val server =
                McpServer(
                    name = "local",
                    endpoint = s.url("/").toString().replace("localhost", "127.0.0.1"),
                    localOptIn = true,
                    enabled = true,
                    reviewed = mapOf("echo" to "old-hash"),
                )
            assertEquals(
                "mcp_capabilities_changed",
                assertFailsWith<SafeFailure> {
                        McpClient(server) { null }
                            .call(Action(Op.MCP, server = server.id, tool = "echo"))
                    }
                    .code,
            )
            assertEquals(3, s.requestCount)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun mcpCarriesNegotiatedSessionAndVersion() = runTest {
        val s = MockWebServer()
        s.start()
        try {
            s.enqueue(
                MockResponse()
                    .addHeader("MCP-Session-Id", "session-1")
                    .setBody(
                        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}"""
                    )
            )
            s.enqueue(MockResponse().setResponseCode(202))
            s.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}"""))
            val server =
                McpServer(
                    name = "local",
                    endpoint = s.url("/").toString().replace("localhost", "127.0.0.1"),
                    localOptIn = true,
                    enabled = true,
                )
            assertTrue(McpClient(server) { null }.discover().isEmpty())
            s.takeRequest()
            val initialized = s.takeRequest()
            assertEquals("session-1", initialized.getHeader("MCP-Session-Id"))
            assertEquals("2025-11-25", initialized.getHeader("MCP-Protocol-Version"))
            assertTrue(s.takeRequest().getHeader("Accept")!!.contains("text/event-stream"))
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun pausedGatewayCannotDispatchAfterApproval() = runTest {
        val answer = CompletableDeferred<Boolean>()
        var executed = false
        val screen = Screen(app = "test.app", locked = false)
        val gate =
            Gateway(
                Policy("own.app"),
                { PolicyConfig(apps = mapOf("test.app" to AppRule(true, true)), planOnly = false) },
                object : DevicePort {
                    override suspend fun inspect(app: String) = screen

                    override suspend fun execute(action: Action, screen: Screen): ToolResult {
                        executed = true
                        return ToolResult("dispatched")
                    }
                },
                object : ApprovalPort {
                    override suspend fun request(approval: Approval) = answer.await()
                },
            )
        gate.start()
        val job = launch { gate.run(Action(Op.OPEN, "test.app")) }
        runCurrent()
        gate.pause()
        answer.complete(true)
        runCurrent()
        assertFalse(executed)
        gate.resume()
        advanceUntilIdle()
        job.join()
        assertTrue(executed)
    }

    @Test
    fun cancelledScriptCannotContinue() = runTest {
        var n = 0
        val gate =
            Gateway(
                Policy("own.app"),
                { PolicyConfig(planOnly = false) },
                object : DevicePort {
                    override suspend fun inspect(app: String) = Screen()

                    override suspend fun execute(action: Action, screen: Screen): ToolResult {
                        n++
                        return ToolResult("updated")
                    }
                },
                object : ApprovalPort {
                    override suspend fun request(approval: Approval) = true
                },
            )
        gate.start()
        val job = launch {
            ScriptRunner(gate)
                .run(
                    Script(
                        name = "wait",
                        steps =
                            listOf(
                                ScriptStep(Action(Op.WAIT, millis = 10000)),
                                ScriptStep(Action(Op.PLAN)),
                            ),
                        enabled = true,
                    ),
                    emptyMap(),
                )
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(0, n)
    }
}
