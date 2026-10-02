// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
enum class ProviderKind {
    CHATGPT,
    OPENAI,
    COMPATIBLE,
    MOCK,
}

@Serializable
data class Profile(
    val id: String = id(),
    val name: String,
    val kind: ProviderKind,
    val endpoint: String = "https://api.openai.com/v1/",
    val model: String = "",
    val localOptIn: Boolean = false,
    val images: Boolean = false,
    val functions: Boolean = true,
    val reasoningEffort: String? = null,
    val serviceTier: String? = null,
    val modelChoice: ModelChoice? = null,
    val manualModelOptions: Boolean = false,
)

data class BoundSecret(val origin: String, val value: String)

object Destinations {
    fun url(raw: String, local: Boolean = false): HttpUrl {
        require(raw.length <= 2048)
        val u = raw.toHttpUrl()
        require(
            u.username.isEmpty() && u.password.isEmpty() && u.fragment == null && u.query == null
        )
        require(u.scheme == "https" || (local && u.scheme == "http" && u.host == "127.0.0.1")) {
            "https_required"
        }
        return u
    }

    fun origin(u: HttpUrl) = "${u.scheme}://${u.host}:${u.port}"

    fun addresses(host: String, local: Boolean): List<InetAddress> =
        InetAddress.getAllByName(host).toList().also { values ->
            require(values.isNotEmpty())
            require(values.none { it.isAnyLocalAddress || it.isMulticastAddress })
            if (!local)
                require(
                    values.none {
                        it.isLoopbackAddress ||
                            it.isSiteLocalAddress ||
                            it.isLinkLocalAddress ||
                            (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc)
                    }
                )
        }

    fun profile(p: Profile) {
        if (p.kind == ProviderKind.MOCK) return
        val u = url(p.endpoint, p.localOptIn)
        if (p.kind in setOf(ProviderKind.CHATGPT, ProviderKind.OPENAI))
            require(u.toString() == "https://api.openai.com/v1/")
    }
}

/** Per-origin client. No redirects, automatic retries, cookies or payload logs. */
class HttpTransport(val base: String, private val local: Boolean = false) {
    private val endpoint = Destinations.url(base, local)
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String) = Destinations.addresses(hostname, local)
                }
            )
            .build()

    suspend fun request(
        path: String = "",
        body: String? = null,
        secret: BoundSecret? = null,
        headers: Map<String, String> = emptyMap(),
        form: Boolean = false,
        accept: String = "application/json",
        consume: (Response) -> Unit,
    ) {
        val target =
            if (path.isEmpty()) endpoint
            else endpoint.resolve(path) ?: throw SafeFailure("invalid_destination")
        require(
            Destinations.origin(target) == Destinations.origin(endpoint) &&
                target.encodedPath.startsWith(endpoint.encodedPath)
        )
        if (secret != null)
            require(secret.origin == Destinations.origin(target)) { "credential_origin" }
        val builder = Request.Builder().url(target).header("Accept", accept)
        headers.forEach { (key, value) ->
            require(key in setOf("MCP-Protocol-Version", "MCP-Session-Id"))
            builder.header(key, value)
        }
        secret?.let { builder.header("Authorization", "Bearer ${it.value}") }
        if (body != null)
            builder.post(
                body.toRequestBody(
                    (if (form) "application/x-www-form-urlencoded" else "application/json")
                        .toMediaType()
                )
            )
        val call = client.newCall(builder.build())
        coroutineScope {
            val cancellation =
                launch(Dispatchers.IO) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
            try {
                withContext(Dispatchers.IO) {
                    call.execute().use { response ->
                        if (!response.isSuccessful)
                            throw SafeFailure(
                                when (response.code) {
                                    401 -> "session_expired"
                                    403 -> "consent_or_eligibility"
                                    429 -> "usage_limit"
                                    in 300..399 -> "redirect_rejected"
                                    else -> "network_${response.code}"
                                }
                            )
                        consume(response)
                    }
                }
            } finally {
                cancellation.cancelAndJoin()
            }
        }
    }

    suspend fun json(
        path: String = "",
        body: String? = null,
        secret: BoundSecret? = null,
        form: Boolean = false,
    ): JsonObject {
        var result = JsonObject(emptyMap())
        request(path, body, secret, form = form) { response ->
            val bytes = response.body!!.byteStream().readBounded(1_048_577)
            require(bytes.size <= 1_048_576)
            result =
                if (bytes.isEmpty()) JsonObject(emptyMap())
                else JsonCodec.parseToJsonElement(bytes.decodeToString()).jsonObject
        }
        return result
    }
}

/**
 * Bounded SSE parser, including multiline data, comments and CRLF. Never dispatch partial calls.
 */
object Sse {
    fun read(response: Response, event: (JsonObject) -> Unit) {
        val source = response.body!!.source()
        val data = StringBuilder()
        var total = 0
        while (!source.exhausted()) {
            val line = source.readUtf8LineStrict(1_048_576)
            total += line.length
            require(total <= 8_388_608) { "response_budget" }
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    val s = data.toString().trimEnd('\n')
                    if (s != "[DONE]") event(JsonCodec.parseToJsonElement(s).jsonObject)
                    data.setLength(0)
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).removePrefix(" ")).append('\n')
                require(data.length <= 1_048_576)
            }
        }
        // A truncated event is intentionally not accepted.
    }
}

fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    require(limit in 1..(9 * 1024 * 1024))
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
