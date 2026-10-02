// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import dev.magicphone.core.*
import kotlinx.serialization.json.*

/** Migrate only known retired settings; keep strict decoding for everything else. */
fun decodeSettings(raw: String): Settings {
    val original = JsonCodec.parseToJsonElement(raw).jsonObject
    val fast = (original["fastDecisions"] as? JsonPrimitive)?.booleanOrNull ?: true
    val migrated = original.toMutableMap().apply {
        remove("fastDecisions")
        (original["policy"] as? JsonObject)?.let { put("policy", JsonObject(it - "planOnly")) }
        (original["profiles"] as? JsonArray)?.let { profiles ->
            put("profiles", JsonArray(profiles.map { value ->
                val p = value.jsonObject
                if ("reasoningEffort" in p) p else {
                    val supported = p.str("kind") in setOf("CHATGPT", "OPENAI") &&
                        "low" in ModelOptions.documentedEfforts(p.str("model"))
                    JsonObject(p + ("reasoningEffort" to if (fast && supported) JsonPrimitive("low") else JsonNull))
                }
            }))
        }
    }
    return JsonCodec.decodeFromJsonElement(Settings.serializer(), JsonObject(migrated))
}
