// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class McpServer(
    val id: String = id(),
    val name: String,
    val endpoint: String,
    val localOptIn: Boolean = false,
    val enabled: Boolean = false,
    val reviewed: Map<String, String> = emptyMap(),
    val schemas: Map<String, JsonObject> = emptyMap(),
)

data class McpTool(val name: String, val description: String, val schema: JsonObject) {
    val fingerprint
        get() = digest("$name|$description|$schema")
}

class McpClient(private val server: McpServer, private val secret: () -> BoundSecret?) {
    private val http = HttpTransport(server.endpoint, server.localOptIn)
    private var session: String? = null
    private var initialized = false
    private var sequence = 0
    private val tools = mutableMapOf<String, McpTool>()

    private suspend fun rpc(
        method: String,
        params: JsonObject,
        notification: Boolean = false,
    ): JsonObject {
        val callId = ++sequence
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            if (!notification) put("id", callId)
            put("method", method)
            put("params", params)
        }
        var reply: JsonObject? = null
        val headers = mutableMapOf<String, String>()
        if (initialized) headers["MCP-Protocol-Version"] = "2025-11-25"
        session?.let { headers["MCP-Session-Id"] = it }
        http.request(
            body = body.toString(),
            secret = secret(),
            headers = headers,
            accept = "application/json, text/event-stream",
        ) { response ->
            response.header("MCP-Session-Id")?.let {
                require(it.length in 1..256 && it.all { c -> c.code in 33..126 })
                session = it
            }
            fun accept(value: JsonObject) {
                if (value.str("method") == "notifications/tools/list_changed") {
                    tools.clear()
                    throw SafeFailure("mcp_capabilities_changed")
                }
                if (value["id"]?.jsonPrimitive?.intOrNull == callId) {
                    require(value.str("jsonrpc") == "2.0")
                    if (value.containsKey("error")) throw SafeFailure("mcp_error")
                    reply = value["result"]!!.jsonObject
                }
            }
            if (!notification) {
                if (response.header("Content-Type").orEmpty().startsWith("text/event-stream"))
                    Sse.read(response, ::accept)
                else {
                    val b = response.body!!.byteStream().readBounded(262145)
                    require(b.size <= 262144)
                    accept(JsonCodec.parseToJsonElement(b.decodeToString()).jsonObject)
                }
            }
        }
        return if (notification) JsonObject(emptyMap())
        else reply ?: throw SafeFailure("mcp_interrupted")
    }

    /** Only invoked by Gateway after local server consent. */
    suspend fun discover(): List<McpTool> {
        require(server.enabled)
        if (!initialized) {
            val r =
                rpc(
                    "initialize",
                    obj(
                        "protocolVersion" to j("2025-11-25"),
                        "capabilities" to JsonObject(emptyMap()),
                        "clientInfo" to obj("name" to j("MagicPhone"), "version" to j("0.1.0")),
                    ),
                )
            require(r.str("protocolVersion") == "2025-11-25")
            initialized = true
            rpc("notifications/initialized", JsonObject(emptyMap()), true)
        }
        val result = mutableListOf<McpTool>()
        var cursor: String? = null
        repeat(5) {
            val r =
                rpc("tools/list", cursor?.let { obj("cursor" to j(it)) } ?: JsonObject(emptyMap()))
            r["tools"]!!.jsonArray.forEach { raw ->
                val t = raw.jsonObject
                require(t.str("name").matches(Regex("[A-Za-z0-9_.-]{1,128}")))
                result +=
                    McpTool(
                        t.str("name"),
                        t.str("description").take(4000),
                        t["inputSchema"]!!.jsonObject,
                    )
            }
            require(result.size <= 100)
            cursor = r.str("nextCursor").ifEmpty { null }
            if (cursor == null) {
                tools.clear()
                result.forEach { tools[it.name] = it }
                return result
            }
        }
        throw SafeFailure("mcp_catalog_budget")
    }

    suspend fun call(action: Action): ToolResult {
        require(server.enabled)
        if (action.tool == "__catalog__")
            return ToolResult(
                "catalog",
                discover().joinToString("\n") {
                    "${it.name}\n${it.description}\n${it.schema}\n${it.fingerprint}"
                },
            )
        // Re-discover before each call to detect changes, never silently renew consent.
        val tool =
            discover().singleOrNull { it.name == action.tool }
                ?: throw SafeFailure("mcp_unknown_tool")
        if (server.reviewed[tool.name] != tool.fingerprint)
            throw SafeFailure("mcp_capabilities_changed")
        Schema.validate(tool.schema, action.arguments)
        val r = rpc("tools/call", obj("name" to j(action.tool), "arguments" to action.arguments))
        return ToolResult(
            if (r["isError"]?.jsonPrimitive?.booleanOrNull == true) "failed" else "remote_result",
            r.toString().take(64_000),
        )
    }
}

object Schema {
    fun validate(schema: JsonObject, value: JsonElement, depth: Int = 0) {
        require(depth <= 8 && schema["\$ref"] == null)
        schema["enum"]?.let { require(value in it.jsonArray) }
        when (schema.str("type")) {
            "object" -> {
                val v = value.jsonObject
                val p = (schema["properties"] as? JsonObject).orEmpty()
                require(v.size <= 50)
                (schema["required"] as? JsonArray).orEmpty().forEach {
                    require(v.containsKey(it.jsonPrimitive.content))
                }
                if (schema["additionalProperties"] == JsonPrimitive(false))
                    require(v.keys.all { it in p })
                v.forEach { (k, x) -> (p[k] as? JsonObject)?.let { validate(it, x, depth + 1) } }
            }
            "array" -> {
                val v = value.jsonArray
                require(v.size <= schema.int("maxItems", 100).coerceAtMost(100))
                v.forEach { validate(schema["items"]!!.jsonObject, it, depth + 1) }
            }
            "string" -> {
                require(value.jsonPrimitive.isString)
                val s = value.jsonPrimitive.content
                require(s.length <= schema.int("maxLength", 8000).coerceAtMost(8000))
                require(s.length >= schema.int("minLength", 0))
            }
            "integer" -> require(value.jsonPrimitive.longOrNull != null)
            "number" -> require(value.jsonPrimitive.doubleOrNull?.isFinite() == true)
            "boolean" -> require(value.jsonPrimitive.booleanOrNull != null)
            "null" -> require(value == JsonNull)
            else -> throw SafeFailure("mcp_schema_unsupported")
        }
    }
}
