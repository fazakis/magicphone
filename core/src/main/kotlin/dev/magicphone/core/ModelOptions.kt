// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ModelChoice(
    val id: String,
    val name: String,
    // null means absent metadata; an explicit empty list means no selectable effort.
    val reasoningEfforts: List<String>? = null,
    val serviceTiers: List<String> = emptyList(),
)

/** Account catalog metadata wins. Public API fallbacks never add account model availability. */
object ModelOptions {
    val efforts = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
    private val tiers = setOf("default", "priority", "fast", "ultrafast")
    private val deep = listOf("low", "medium", "high", "xhigh", "max")

    // Verified against official model documentation on 2026-10-02. Unknown IDs use default.
    fun documentedEfforts(model: String): List<String> = when (model) {
        "gpt-6-astra", "gpt-6.1-sol" -> deep
        "gpt-6-sol", "gpt-6-luna", "gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna" -> listOf("none") + deep
        "gpt-5.5", "gpt-5.4" -> listOf("none", "low", "medium", "high", "xhigh")
        else -> emptyList()
    }

    fun reasoning(profile: Profile): List<String> {
        if (profile.kind !in setOf(ProviderKind.CHATGPT, ProviderKind.OPENAI)) return emptyList()
        val metadata = profile.modelChoice?.takeIf { it.id == profile.model }?.reasoningEfforts
        return (metadata ?: documentedEfforts(profile.model)).filter { it in efforts }.distinct()
    }

    fun speeds(profile: Profile): List<String> {
        if (profile.kind !in setOf(ProviderKind.CHATGPT, ProviderKind.OPENAI)) return emptyList()
        val catalog = profile.modelChoice?.takeIf { it.id == profile.model }?.serviceTiers.orEmpty()
        val available = if (profile.kind == ProviderKind.OPENAI) when (profile.model) {
            "gpt-6-astra" -> listOf("fast", "ultrafast")
            "gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna", "gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna" -> listOf("fast")
            else -> catalog
        } else catalog
        return available.filter { it in tiers }.distinctBy { if (it == "priority") "fast" else it }
    }

    // Suggestions for explicit manual selection, not an assertion of account availability.
    val manualModels = listOf(
        "gpt-6.1-sol" to "GPT-6.1 Sol", "gpt-6-astra" to "GPT-6 Astra",
        "gpt-6-sol" to "GPT-6 Sol", "gpt-6-luna" to "GPT-6 Luna",
        "gpt-5.6-sol" to "GPT-5.6 Sol", "gpt-5.6-terra" to "GPT-5.6 Terra",
        "gpt-5.6-luna" to "GPT-5.6 Luna", "gpt-5.5" to "GPT-5.5", "gpt-5.4" to "GPT-5.4",
    ).map { (id, name) -> ModelChoice(id, name) }

    fun canOverride(profile: Profile) = profile.kind in setOf(ProviderKind.CHATGPT, ProviderKind.OPENAI)
    fun reasoningChoices(profile: Profile) = if (profile.manualModelOptions && canOverride(profile)) efforts else reasoning(profile)
    fun speedChoices(profile: Profile) = if (profile.manualModelOptions && canOverride(profile))
        listOf("default", "fast", "ultrafast") else speeds(profile)

    private fun normalize(profile: Profile): Profile {
        val options = speedChoices(profile)
        val tier = if (profile.serviceTier in setOf("priority", "fast")) options.firstOrNull { it in setOf("priority", "fast") }
            else profile.serviceTier
        return profile.copy(
            reasoningEffort = profile.reasoningEffort?.takeIf { it in reasoningChoices(profile) },
            serviceTier = tier?.takeIf { it in speedChoices(profile) },
        )
    }
    fun withManualOptions(profile: Profile, enabled: Boolean) = normalize(profile.copy(manualModelOptions = enabled && canOverride(profile)))
    fun selected(profile: Profile, choice: ModelChoice) = normalize(profile.copy(model = choice.id, modelChoice = choice))

    fun validate(profile: Profile) {
        if (profile.reasoningEffort != null && profile.reasoningEffort !in reasoningChoices(profile))
            throw SafeFailure("unsupported_model_setting")
        if (profile.serviceTier != null && profile.serviceTier !in speedChoices(profile))
            throw SafeFailure("unsupported_model_setting")
    }

    fun parse(raw: JsonObject, chatGpt: Boolean): ModelChoice {
        val id = raw.str(if (chatGpt) "slug" else "id").take(200)
        val name = raw.str("display_name").ifBlank { id }.take(200)
        val levels = raw["supported_reasoning_levels"] as? JsonArray
        val speed = raw["service_tiers"] as? JsonArray
        return ModelChoice(id, name,
            levels?.mapNotNull { value ->
                ((value as? JsonObject)?.str("effort") ?: (value as? JsonPrimitive)?.contentOrNull)
                    ?.takeIf { it in efforts }
            }?.distinct(),
            speed.orEmpty().mapNotNull { value ->
                ((value as? JsonObject)?.str("id") ?: (value as? JsonPrimitive)?.contentOrNull)
                    ?.takeIf { it in tiers }
            }.distinct(),
        )
    }
}

/** Request preferences and final server-reported metadata; no prompt or response text. */
data class ModelRunInfo(
    val requestedModel: String,
    val requestedEffort: String?,
    val requestedTier: String?,
    val reportedModel: String? = null,
    val reportedEffort: String? = null,
    val reportedTier: String? = null,
) {
    val speedConfirmed: Boolean get() = requestedTier != null && reportedTier != null &&
        canonicalTier(requestedTier) == canonicalTier(reportedTier)

    companion object {
        private fun canonicalTier(tier: String) = if (tier == "priority") "fast" else tier
        fun from(profile: Profile, response: JsonObject) = ModelRunInfo(
            profile.model, profile.reasoningEffort, profile.serviceTier,
            (response["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z0-9._:/-]{1,200}")) },
            ((response["reasoning"] as? JsonObject)?.get("effort") as? JsonPrimitive)?.contentOrNull?.takeIf { it in ModelOptions.efforts },
            (response["service_tier"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("default", "priority", "fast", "ultrafast", "flex", "auto") },
        )
    }
}
