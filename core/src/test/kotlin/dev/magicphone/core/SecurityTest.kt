// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import java.security.*
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*

class SecurityTest {
    @Test
    fun auditDoesNotStoreRawArguments() {
        val action =
            Action(
                Op.TEXT,
                "test.app",
                text = "password=secret-token-value",
                arguments = obj("secret" to j("raw-otp")),
            )
        val stored =
            JsonCodec.encodeToString(Audit.serializer(), Sanitizer.event(action, "dispatched"))
        assertFalse(stored.contains("secret-token"))
        assertFalse(stored.contains("raw-otp"))
        assertFalse(stored.contains("arguments"))
    }

    @Test
    fun nonDeviceArgumentsCannotHideInAuditAppField() {
        val action = Action(Op.PLAN, app = "password=secret-value")
        val stored =
            JsonCodec.encodeToString(Audit.serializer(), Sanitizer.event(action, "updated"))
        assertFalse(stored.contains("secret-value"))
        assertFalse(stored.contains("password"))
    }

    @Test
    fun echoedSecretsSanitized() {
        val s =
            Sanitizer.text(
                "password=hunter2 OTP=123456 api_key=abcdef Bearer abc.def ghi sk-abcdefghij"
            )
        listOf("hunter2", "123456", "abcdef", "abc.def", "sk-abcdefghij").forEach {
            assertFalse(s.contains(it), s)
        }
    }

    @Test
    fun encryptedBackupRoundTripAndNoCredentials() {
        val a =
            Archive(
                conversations =
                    listOf(
                        Conversation(
                            title = "Test",
                            messages = listOf(Message(role = "user", text = "password=secret")),
                        )
                    )
            )
        val b = BackupCrypto.encrypt(a, "a long passphrase".toCharArray())
        assertFalse(b.decodeToString().contains("password"))
        val read = BackupCrypto.decrypt(b, "a long passphrase".toCharArray())
        assertEquals("Test", read.conversations.single().title)
        assertFalse(read.conversations.single().messages.single().text.contains("secret"))
    }

    @Test
    fun tamperedBackupFailsAuthentication() {
        val b = BackupCrypto.encrypt(Archive(), "a long passphrase".toCharArray())
        b[b.lastIndex] = (b.last().toInt() xor 1).toByte()
        assertFails { BackupCrypto.decrypt(b, "a long passphrase".toCharArray()) }
    }

    @Test
    fun wrongPassphraseFails() {
        val b = BackupCrypto.encrypt(Archive(), "a long passphrase".toCharArray())
        assertFails { BackupCrypto.decrypt(b, "another passphrase".toCharArray()) }
    }

    @Test
    fun endpointChangeAndPermissionEscalationRejected() {
        for (field in listOf("profiles", "credentials", "policy", "servers")) assertFails {
            Archives.read(
                """{"schema":2,"$field":{"endpoint":"https://evil.example","token":"old","allow":true}}"""
                    .toByteArray()
            )
        }
    }

    @Test
    fun traversalAndAbsoluteIdsRejected() {
        for (value in listOf("../escape", "/tmp/file", "a/b", "a\\b", "..")) {
            val a = Archive(conversations = listOf(Conversation(id = value, title = "bad")))
            assertFails {
                Archives.read(JsonCodec.encodeToString(Archive.serializer(), a).toByteArray())
            }
        }
    }

    @Test
    fun oversizedAndMalformedArchivesRejected() {
        assertFails { Archives.read(ByteArray(Archives.MAX + 1)) }
        assertFails { Archives.read("[]".toByteArray()) }
        assertFails { Archives.read("{\"schema\":99}".toByteArray()) }
    }

    @Test
    fun importsCannotActivateKnowledgeOrScripts() {
        val a =
            Archive(
                knowledge =
                    listOf(
                        Knowledge(app = "test.app", title = "t", content = "do", reviewed = true)
                    ),
                scripts =
                    listOf(
                        Script(
                            name = "test",
                            steps = listOf(ScriptStep(Action(Op.WAIT))),
                            enabled = true,
                        )
                    ),
            )
        val imported =
            Archives.previewImport(JsonCodec.encodeToString(Archive.serializer(), a).toByteArray())
        assertFalse(imported.knowledge.single().reviewed)
        assertFalse(imported.scripts.single().enabled)
    }

    @Test
    fun interruptedRunsNeedExplicitResume() {
        RunState.entries
            .filter {
                it in
                    setOf(
                        RunState.ACTING,
                        RunState.PLANNING,
                        RunState.WAITING_APPROVAL,
                        RunState.PAUSED,
                    )
            }
            .forEach { state ->
                assertEquals(
                    RunState.INTERRUPTED,
                    Archives.interrupted(
                            Archive(
                                conversations = listOf(Conversation(title = "x", state = state))
                            )
                        )
                        .conversations
                        .single()
                        .state,
                )
            }
    }

    @Test
    fun migrationOneToTwo() {
        assertEquals(3, Archives.read("{\"schema\":1}".toByteArray()).schema)
    }

    @Test
    fun cleartextAndUrlConfusionRejected() {
        for (u in
            listOf(
                "http://example.org/",
                "http://127.0.0.1.evil.test/",
                "https://user:secret@example.org/",
                "https://example.org/#fragment",
                "https://example.org/?token=x",
            )) assertFails { Destinations.url(u, true) }
        assertEquals("127.0.0.1", Destinations.url("http://127.0.0.1:8080/", true).host)
        assertFails { Destinations.url("http://127.0.0.1/") }
    }

    @Test
    fun actualLocalAddressesChecked() {
        assertFails { Destinations.addresses("127.0.0.1", false) }
        assertFails { Destinations.addresses("::1", false) }
        assertTrue(Destinations.addresses("127.0.0.1", true).single().isLoopbackAddress)
    }

    @Test
    fun chatGptCannotPointAtCustomEndpoint() {
        assertFails {
            Destinations.profile(
                Profile(
                    name = "evil",
                    kind = ProviderKind.CHATGPT,
                    endpoint = "https://evil.example/v1/",
                )
            )
        }
    }

    @Test
    fun redirectNeverForwardsCredential() = runTest {
        val server = MockWebServer()
        val other = MockWebServer()
        server.start()
        other.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", other.url("/"))
            )
            val endpoint = server.url("/").toString().replace("localhost", "127.0.0.1")
            val http = HttpTransport(endpoint, true)
            assertEquals(
                "redirect_rejected",
                assertFailsWith<SafeFailure> {
                        http.json(
                            secret =
                                BoundSecret(
                                    Destinations.origin(Destinations.url(endpoint, true)),
                                    "secret",
                                )
                        )
                    }
                    .code,
            )
            assertEquals(0, other.requestCount)
        } finally {
            server.shutdown()
            other.shutdown()
        }
    }

    @Test
    fun wrongOriginRejectedBeforeRequest() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val endpoint = server.url("/").toString().replace("localhost", "127.0.0.1")
            assertFailsWith<IllegalArgumentException> {
                HttpTransport(endpoint, true)
                    .json(secret = BoundSecret("https://other.example:443", "secret"))
            }
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun oauthStateAndCallbackReplay() {
        val p = PendingAuth(redirect = "http://127.0.0.1:1234/auth/callback")
        assertFails { p.callback("state=wrong&code=x&client_id=oaiapp_test") }
        assertFalse(p.consumed)
        assertEquals(
            "oaiapp_test",
            p.callback("state=${p.state}&code=x&client_id=oaiapp_test").second,
        )
        assertFails { p.callback("state=${p.state}&code=x&client_id=oaiapp_test") }
    }

    @Test
    fun oauthReturningClientMustMatch() {
        val p =
            PendingAuth(redirect = "http://127.0.0.1:1234/auth/callback", client = "oaiapp_good")
        assertFails { p.callback("state=${p.state}&code=x&client_id=oaiapp_bad") }
    }

    @Test
    fun oauthDenialValidatedAndConsumesAttempt() {
        val p = PendingAuth(redirect = "http://127.0.0.1:1234/auth/callback")
        assertEquals(
            "consent_denied",
            assertFailsWith<SafeFailure> { p.callback("state=${p.state}&error=access_denied") }
                .code,
        )
        assertTrue(p.consumed)
    }

    @Test
    fun oauthDuplicateAndExpiredCallbacksRejected() {
        val p = PendingAuth(redirect = "http://127.0.0.1:1234/auth/callback", started = 0)
        assertFails { p.callback("state=${p.state}&code=x&client_id=oaiapp_test", 180001) }
        val q = PendingAuth(redirect = "http://127.0.0.1:1234/auth/callback")
        assertFails { q.callback("state=${q.state}&state=${q.state}&code=x&client_id=oaiapp_test") }
    }

    @Test
    fun ownIdentityPkceAndLoopbackUsed() {
        val p = PendingAuth(redirect = "http://127.0.0.1:1234/auth/callback")
        val url = p.authorize("urn:uuid:123")
        assertTrue(url.contains("agent_name_hint=MagicPhone"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertFalse(url.contains(p.verifier))
        assertTrue(url.contains("dynamic_agent_client"))
    }

    private val pair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun token(nonce: String = "n", aud: String = "oaiapp_test", exp: Long = 2000): String {
        val h = b64("{\"alg\":\"RS256\",\"kid\":\"k\"}".toByteArray())
        val payload =
            b64(
                """{"iss":"https://auth.openai.com","sub":"subject","aud":"$aud","exp":$exp,"iat":900,"nonce":"$nonce"}"""
                    .toByteArray()
            )
        val sig =
            Signature.getInstance("SHA256withRSA")
                .apply {
                    initSign(pair.private)
                    update("$h.$payload".toByteArray())
                }
                .sign()
        return "$h.$payload.${b64(sig)}"
    }

    private fun keys(): JsonObject {
        val key = pair.public as RSAPublicKey
        return obj(
            "keys" to
                JsonArray(
                    listOf(
                        obj(
                            "kty" to j("RSA"),
                            "kid" to j("k"),
                            "n" to j(b64(key.modulus.toByteArray())),
                            "e" to j(b64(key.publicExponent.toByteArray())),
                        )
                    )
                )
        )
    }

    @Test
    fun identitySignatureNonceAudienceExpiryAndSubject() {
        assertEquals(
            "subject",
            IdentityVerifier.verify(token(), keys(), "oaiapp_test", "n", now = 1000).str("sub"),
        )
        assertFails {
            IdentityVerifier.verify(token("wrong"), keys(), "oaiapp_test", "n", now = 1000)
        }
        assertFails {
            IdentityVerifier.verify(token(aud = "other"), keys(), "oaiapp_test", "n", now = 1000)
        }
        assertFails {
            IdentityVerifier.verify(token(exp = 999), keys(), "oaiapp_test", "n", now = 1000)
        }
        assertFails { IdentityVerifier.verify(token(), keys(), "oaiapp_test", "n", "other", 1000) }
        assertFails {
            IdentityVerifier.verify(
                token().dropLast(8) + "AAAAAAAA",
                keys(),
                "oaiapp_test",
                "n",
                now = 1000,
            )
        }
    }

    @Test
    fun scriptsHaveHardFiniteBudgetsAndNoAmbientCapabilities() {
        assertFails {
            ScriptRunner.validate(
                Script(name = "x", steps = List(41) { ScriptStep(Action(Op.WAIT)) })
            )
        }
        assertFails {
            ScriptRunner.validate(
                Script(
                    name = "x",
                    steps = listOf(ScriptStep(Action(Op.MCP, server = "s", tool = "t"))),
                )
            )
        }
        assertFails {
            JsonCodec.decodeFromString(
                Script.serializer(),
                """{"id":"bad","name":"bad","steps":[],"shell":"rm"}""",
            )
        }
    }

    @Test
    fun scriptsCannotBypassPolicy() = runTest {
        var mutations = 0
        val gate =
            Gateway(
                Policy("own.app"),
                { PolicyConfig(apps = mapOf("test.app" to AppRule(true, false))) },
                object : DevicePort {
                    override suspend fun inspect(app: String) = Screen(app = app, locked = false)

                    override suspend fun execute(action: Action, screen: Screen): ToolResult {
                        mutations++
                        return ToolResult("x")
                    }
                },
                object : ApprovalPort {
                    override suspend fun request(approval: Approval) = true
                },
            )
        gate.start()
        val s =
            Script(
                name = "x",
                steps = listOf(ScriptStep(Action(Op.OPEN, "test.app"))),
                enabled = true,
            )
        assertFailsWith<SafeFailure> { ScriptRunner(gate).run(s, emptyMap()) }
        assertEquals(0, mutations)
    }

    @Test
    fun toolSchemaRejectsUnknownOperationsAndFields() {
        assertFails { ToolSchema.calls("perform", "{\"op\":\"SHELL\"}") }
        assertFails { ToolSchema.calls("perform", "{\"op\":\"PLAN\",\"grant\":true}") }
        assertFails { ToolSchema.calls("hidden_mutation", "{}") }
    }
}
