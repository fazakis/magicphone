// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val JsonCodec = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
    isLenient = false
}

fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

fun id(): String = UUID.randomUUID().toString()

fun JsonObject.str(key: String, fallback: String = ""): String =
    (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback

fun JsonObject.int(key: String, fallback: Int = 0): Int =
    (get(key) as? JsonPrimitive)?.intOrNull ?: fallback

fun obj(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(values.toMap())

fun j(value: String) = JsonPrimitive(value)

@Serializable
enum class Op(val mutates: Boolean = false) {
    OBSERVE,
    SCREENSHOT,
    APPS,
    OPEN(true),
    TAP(true),
    LONG_PRESS(true),
    SCROLL(true),
    SWIPE(true),
    TEXT(true),
    BACK(true),
    HOME(true),
    RECENTS(true),
    NOTIFICATIONS(true),
    WAIT,
    WAIT_FOR,
    PLAN,
    CHECKLIST,
    ASK,
    COMPLETE,
    MCP(true),
    MCP_CATALOG,
    MEMORY_PROPOSAL,
}

@Serializable
data class Action(
    val op: Op,
    val app: String = "",
    val snapshot: String = "",
    val node: String = "",
    val text: String = "",
    val x: Int = -1,
    val y: Int = -1,
    val x2: Int = -1,
    val y2: Int = -1,
    val millis: Int = 500,
    val server: String = "",
    val tool: String = "",
    val arguments: JsonObject = JsonObject(emptyMap()),
) {
    val isDevice: Boolean
        get() =
            op in
                setOf(
                    Op.OBSERVE,
                    Op.SCREENSHOT,
                    Op.OPEN,
                    Op.TAP,
                    Op.LONG_PRESS,
                    Op.SCROLL,
                    Op.SWIPE,
                    Op.TEXT,
                    Op.BACK,
                    Op.HOME,
                    Op.RECENTS,
                    Op.NOTIFICATIONS,
                    Op.WAIT_FOR,
                )

    val coordinate: Boolean
        get() = op == Op.SWIPE || (op in setOf(Op.TAP, Op.LONG_PRESS) && node.isEmpty())

    fun validate() {
        require(app.length <= 200 && app.none { it.isWhitespace() } && text.length <= 8000)
        require(snapshot.length <= 100 && node.length <= 150 && millis in 0..10_000)
        require(arguments.toString().length <= 16_384 && server.length <= 80 && tool.length <= 128)
        if (isDevice) require(app.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")))
        if (
            op in
                setOf(
                    Op.TAP,
                    Op.LONG_PRESS,
                    Op.SCROLL,
                    Op.SWIPE,
                    Op.TEXT,
                    Op.BACK,
                    Op.HOME,
                    Op.RECENTS,
                    Op.NOTIFICATIONS,
                    Op.SCREENSHOT,
                )
        )
            require(snapshot.isNotBlank())
        if (coordinate) require(x in 0..10000 && y in 0..10000)
        if (op == Op.SWIPE) require(x2 in 0..10000 && y2 in 0..10000 && millis in 50..2000)
        if (op == Op.TEXT || op == Op.SCROLL) require(node.isNotBlank())
        if (op == Op.SCROLL) require(text in setOf("forward", "backward"))
        if (op == Op.WAIT_FOR) require(text.isNotBlank() && millis in 100..10_000)
        if (op in setOf(Op.MCP, Op.MCP_CATALOG)) require(server.isNotBlank() && tool.isNotBlank())
    }

    fun fingerprint(context: Screen): String =
        digest(JsonCodec.encodeToString(serializer(), this) + context.binding)
}

@Serializable
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int) = x >= left && y >= top && x < right && y < bottom
}

@Serializable
data class Node(
    val ref: String,
    val label: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val sensitive: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
)

@Serializable
data class Screen(
    val id: String = "",
    val app: String = "",
    val window: Int = -1,
    val revision: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val focused: Boolean = false,
    val locked: Boolean = true,
    val mixed: Boolean = true,
    val sensitive: Boolean = false,
    val nodes: List<Node> = emptyList(),
    val protectedRects: List<Rect> = emptyList(),
    val captureBounds: Rect = Rect(0, 0, 0, 0),
    // Device-filtered, visible nodes from the permitted app; never foreign overlay content.
    // Partial does not relax focus, mixed-window or action checks.
    val partial: Boolean = false,
) {
    val binding: String
        get() =
            "$id|$app|$window|$revision|$width|$height|$rotation|$focused|$locked|$mixed|$sensitive|$captureBounds|$partial|$protectedRects"
}

@Serializable
data class ToolResult(val status: String, val content: String = "", val image: String? = null)

@Serializable
data class AppRule(
    val observe: Boolean = false,
    val mutate: Boolean = false,
    val deny: Boolean = false,
)

@Serializable data class Grant(val app: String, val ops: Set<Op>, val expiresAt: Long)

@Serializable
data class PolicyConfig(
    val apps: Map<String, AppRule> = emptyMap(),
    val grants: List<Grant> = emptyList(),
    val mcp: Map<String, Set<String>> = emptyMap(),
    val allowAllApps: Boolean = false,
)

@Serializable
enum class RunState {
    IDLE,
    PLANNING,
    WAITING_APPROVAL,
    ACTING,
    WAITING_USER,
    PAUSED,
    FAILED,
    COMPLETED,
    STOPPED,
    INTERRUPTED,
}

class SafeFailure(val code: String) : Exception(code)
