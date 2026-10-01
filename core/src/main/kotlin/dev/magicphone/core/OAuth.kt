// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.*
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

fun randomUrl(): String =
    Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

fun form(values: Map<String, String>): String =
    values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }

@Serializable
data class Account(
    val subject: String,
    val email: String,
    val client: String,
    val access: String = "",
    val refresh: String = "",
    val identity: String = "",
    val expires: Long = 0,
    val scopes: String = "",
)

data class PendingAuth(
    val state: String = randomUrl(),
    val nonce: String = randomUrl(),
    val verifier: String = randomUrl(),
    val redirect: String,
    val client: String = "dynamic_agent_client",
    val expectedSubject: String? = null,
    val started: Long = System.currentTimeMillis(),
) {
    var consumed: Boolean = false

    fun authorize(host: String, identity: String = ""): String {
        val query =
            linkedMapOf(
                "client_id" to client,
                "ext_agent_host_id" to host,
                "response_type" to "code",
                "redirect_uri" to redirect,
                "scope" to
                    "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                "resource" to "https://api.openai.com/v1",
                "state" to state,
                "nonce" to nonce,
                "code_challenge_method" to "S256",
                "code_challenge" to
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
                        ),
            )
        if (client == "dynamic_agent_client") query["agent_name_hint"] = "MagicPhone"
        else if (identity.isNotEmpty()) query["id_token_hint"] = identity
        return "https://auth.openai.com/api/accounts/authorize?" + form(query)
    }

    @Synchronized
    fun callback(query: String, now: Long = System.currentTimeMillis()): Pair<String, String> {
        require(!consumed && now - started in 0..180_000)
        val pairs =
            query.split('&').map {
                it.split('=', limit = 2).let { p ->
                    URLDecoder.decode(p[0], "UTF-8") to
                        URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
                }
            }
        require(pairs.map { it.first }.distinct().size == pairs.size)
        val map = pairs.toMap()
        require(MessageDigest.isEqual(map["state"].orEmpty().toByteArray(), state.toByteArray())) {
            "oauth_state"
        }
        consumed = true
        if (map.containsKey("error"))
            throw SafeFailure(
                if (map["error"] == "access_denied") "consent_denied" else "oauth_error"
            )
        val issued = map["client_id"] ?: client
        require(issued != "dynamic_agent_client" && issued.matches(Regex("[A-Za-z0-9_-]{4,200}")))
        require(client == "dynamic_agent_client" || issued == client)
        return (map["code"]?.takeIf { it.length in 1..4096 } ?: throw SafeFailure("oauth_code")) to
            issued
    }
}

object IdentityVerifier {
    fun verify(
        token: String,
        keys: JsonObject,
        client: String,
        nonce: String?,
        subject: String? = null,
        now: Long = System.currentTimeMillis() / 1000,
    ): JsonObject {
        require(token.length <= 32_768)
        val parts = token.split('.')
        require(parts.size == 3)
        fun decode(s: String) = Base64.getUrlDecoder().decode(s)
        val header = JsonCodec.parseToJsonElement(decode(parts[0]).decodeToString()).jsonObject
        require(header.str("alg") == "RS256" && header["crit"] == null)
        val key =
            keys["keys"]!!
                .jsonArray
                .map { it.jsonObject }
                .single {
                    it.str("kid") == header.str("kid") &&
                        it.str("kty") == "RSA" &&
                        it.str("use", "sig") == "sig"
                }
        val publicKey =
            KeyFactory.getInstance("RSA")
                .generatePublic(
                    RSAPublicKeySpec(
                        BigInteger(1, decode(key.str("n"))),
                        BigInteger(1, decode(key.str("e"))),
                    )
                )
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update("${parts[0]}.${parts[1]}".toByteArray())
        require(signature.verify(decode(parts[2]))) { "oauth_signature" }
        val claims = JsonCodec.parseToJsonElement(decode(parts[1]).decodeToString()).jsonObject
        require(claims.str("iss") == "https://auth.openai.com")
        val aud = claims["aud"]!!
        val audiences =
            if (aud is JsonArray) aud.map { it.jsonPrimitive.content }
            else listOf(aud.jsonPrimitive.content)
        require(client in audiences && (audiences.size == 1 || claims.str("azp") == client))
        require(
            claims["exp"]!!.jsonPrimitive.long > now &&
                claims["iat"]!!.jsonPrimitive.long <= now + 60
        )
        claims["nbf"]?.let { require(it.jsonPrimitive.long <= now + 60) }
        if (nonce != null)
            require(MessageDigest.isEqual(claims.str("nonce").toByteArray(), nonce.toByteArray())) {
                "oauth_nonce"
            }
        require(claims.str("sub").isNotBlank() && (subject == null || claims.str("sub") == subject))
        return claims
    }
}

/**
 * Listener lifetime is one attempt; bind explicitly to IPv4 loopback only. Process death cancels
 * sign-in.
 */
class LoginAttempt(account: Account? = null) : AutoCloseable {
    private val server =
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 180_000 }
    val pending =
        PendingAuth(
            redirect = "http://127.0.0.1:${server.localPort}/auth/callback",
            client = account?.client ?: "dynamic_agent_client",
            expectedSubject = account?.subject,
        )

    suspend fun awaitCode(): Pair<String, String> = coroutineScope {
        val closer =
            launch(Dispatchers.IO) {
                try {
                    awaitCancellation()
                } finally {
                    close()
                }
            }
        try {
            withContext(Dispatchers.IO) {
                while (true) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        val bytes = ArrayList<Byte>()
                        while (bytes.size < 8192) {
                            val b = input.read()
                            if (b < 0 || b == 10) break
                            bytes.add(b.toByte())
                        }
                        val first = bytes.toByteArray().decodeToString().trim().split(' ')
                        if (first.size != 3 || first[0] != "GET") return@use
                        val uri = URI(first[1])
                        if (uri.path != "/auth/callback") return@use
                        val result = pending.callback(uri.rawQuery ?: "")
                        val html = "Sign-in received. Return to MagicPhone."
                        socket
                            .getOutputStream()
                            .write(
                                "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nContent-Length: ${html.length}\r\n\r\n$html"
                                    .toByteArray()
                            )
                        return@withContext result
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        } finally {
            closer.cancelAndJoin()
            close()
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}

class ChatGptAuth(private val http: HttpTransport = HttpTransport("https://auth.openai.com/")) {
    private val mutex = Mutex()

    suspend fun exchange(pending: PendingAuth, code: String, issued: String): Account {
        require(pending.consumed)
        val token =
            http.json(
                "api/accounts/oauth/token",
                form(
                    mapOf(
                        "grant_type" to "authorization_code",
                        "client_id" to issued,
                        "code" to code,
                        "code_verifier" to pending.verifier,
                        "redirect_uri" to pending.redirect,
                        "resource" to "https://api.openai.com/v1",
                    )
                ),
                form = true,
            )
        val claims =
            IdentityVerifier.verify(
                token.str("id_token"),
                http.json(".well-known/jwks.json"),
                issued,
                pending.nonce,
                pending.expectedSubject,
            )
        require("chatgpt.tokens.use.direct" in token.str("scope").split(' ')) {
            "plan_consent_required"
        }
        require(token.str("access_token").isNotBlank() && token.str("refresh_token").isNotBlank())
        return Account(
            claims.str("sub"),
            claims.str("email"),
            issued,
            token.str("access_token"),
            token.str("refresh_token"),
            token.str("id_token"),
            System.currentTimeMillis() + token.int("expires_in", 3600) * 1000L,
            token.str("scope"),
        )
    }

    suspend fun fresh(load: () -> Account, save: (Account) -> Unit): Account = mutex.withLock {
        val a = load()
        if (a.expires > System.currentTimeMillis() + 60_000) return@withLock a
        require(a.refresh.isNotEmpty())
        val token =
            http.json(
                "api/accounts/oauth/token",
                form(
                    mapOf(
                        "grant_type" to "refresh_token",
                        "client_id" to a.client,
                        "refresh_token" to a.refresh,
                        "resource" to "https://api.openai.com/v1",
                    )
                ),
                form = true,
            )
        val identity = token.str("id_token", a.identity)
        if (token.containsKey("id_token"))
            IdentityVerifier.verify(
                identity,
                http.json(".well-known/jwks.json"),
                a.client,
                null,
                a.subject,
            )
        val scopes = token.str("scope", a.scopes)
        require("chatgpt.tokens.use.direct" in scopes.split(' '))
        a.copy(
                access = token.str("access_token").also { require(it.isNotEmpty()) },
                refresh = token.str("refresh_token", a.refresh),
                identity = identity,
                expires = System.currentTimeMillis() + token.int("expires_in", 3600) * 1000L,
                scopes = scopes,
            )
            .also(save)
    }

    suspend fun logout(account: Account): Boolean {
        if (account.refresh.isBlank()) return true
        repeat(3) { attempt ->
            try {
                val discovery = http.json(".well-known/openid-configuration")
                val endpoint = discovery.str("revocation_endpoint").toHttpUrl()
                require(
                    endpoint.scheme == "https" &&
                        endpoint.host == "auth.openai.com" &&
                        endpoint.port == 443
                )
                http.json(
                    endpoint.encodedPath.removePrefix("/"),
                    form(
                        mapOf(
                            "token" to account.refresh,
                            "token_type_hint" to "refresh_token",
                            "client_id" to account.client,
                        )
                    ),
                    form = true,
                )
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay((attempt + 1) * 500L)
            }
        }
        return false
    }
}
