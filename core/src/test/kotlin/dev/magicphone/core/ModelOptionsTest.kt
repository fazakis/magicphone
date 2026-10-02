// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*

class ModelOptionsTest {
    @Test fun explicitManualChoicesSurviveRefreshAndModelSwitchButRemainBounded() {
        val base = Profile(name = "test", kind = ProviderKind.CHATGPT, model = "gpt-6-astra",
            modelChoice = ModelChoice("gpt-6-astra", "Astra", listOf("low"), listOf("priority")))
        val manual = ModelOptions.withManualOptions(base, true).copy(reasoningEffort = "ultra", serviceTier = "ultrafast")
        ModelOptions.validate(manual)
        assertEquals(ModelOptions.efforts, ModelOptions.reasoningChoices(manual))
        assertEquals(listOf("default", "fast", "ultrafast"), ModelOptions.speedChoices(manual))
        assertEquals(manual, ModelOptions.selected(manual, base.modelChoice!!))
        val sol = ModelOptions.selected(manual, ModelOptions.manualModels.single { it.id == "gpt-6.1-sol" })
        assertEquals("ultra", sol.reasoningEffort)
        assertEquals("ultrafast", sol.serviceTier)
        val dynamic = ModelOptions.withManualOptions(sol, false)
        assertNull(dynamic.reasoningEffort)
        assertNull(dynamic.serviceTier)
        assertFalse(dynamic.manualModelOptions)
        assertFailsWith<SafeFailure> { ModelOptions.validate(manual.copy(reasoningEffort = "invented")) }
        assertFailsWith<SafeFailure> { ModelOptions.validate(manual.copy(serviceTier = "invented")) }
        assertFalse(ModelOptions.withManualOptions(base.copy(kind = ProviderKind.COMPATIBLE), true).manualModelOptions)
        val fast = ModelOptions.withManualOptions(base.copy(serviceTier = "priority"), true)
        assertEquals("fast", fast.serviceTier)
        assertEquals("priority", ModelOptions.withManualOptions(fast, false).serviceTier)
    }

    @Test fun reportedTierIsSeparateFromRequestedAndUnreportedIsNeverConfirmed() {
        val p = Profile(name = "test", kind = ProviderKind.CHATGPT, model = "gpt-6.1-sol", reasoningEffort = "low", serviceTier = "ultrafast")
        val info = ModelRunInfo.from(p, obj("model" to j("gpt-6.1-sol"), "service_tier" to j("default")))
        assertEquals("ultrafast", info.requestedTier)
        assertEquals("default", info.reportedTier)
        assertFalse(info.speedConfirmed)
        assertFalse(ModelRunInfo.from(p, JsonObject(emptyMap())).speedConfirmed)
        assertNull(ModelRunInfo.from(p, obj("service_tier" to j("sensitive invalid text"))).reportedTier)
        assertTrue(ModelRunInfo.from(p.copy(serviceTier = "fast"), obj("service_tier" to j("priority"))).speedConfirmed)
    }

    @Test fun manualUnadvertisedSettingsReachServerAndReturnedMetadataReflectsFallback() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val endpoint = server.url("/").toString().replace("localhost", "127.0.0.1")
            server.enqueue(MockResponse().setBody("data: {\"type\":\"response.completed\",\"response\":{\"model\":\"gpt-6.1-sol\",\"service_tier\":\"default\",\"reasoning\":{\"effort\":\"low\"},\"output\":[]}}\n\n"))
            val p = Profile(name = "test", kind = ProviderKind.CHATGPT, model = "gpt-6.1-sol", reasoningEffort = "low", serviceTier = "ultrafast", manualModelOptions = true)
            val provider = ResponsesProvider(p, HttpTransport(endpoint, true)) {
                BoundSecret(Destinations.origin(Destinations.url(endpoint, true)), "fixture")
            }
            val reply = provider.respond(listOf(message("user", "fixture"))) {}
            val sent = JsonCodec.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("gpt-6.1-sol", sent.str("model"))
            assertEquals("ultrafast", sent.str("service_tier"))
            assertEquals("low", sent["reasoning"]!!.jsonObject.str("effort"))
            assertEquals("default", reply.modelInfo!!.reportedTier)
            assertEquals("low", reply.modelInfo!!.reportedEffort)
            assertFalse(reply.modelInfo!!.speedConfirmed)
        } finally { server.shutdown() }
    }

    @Test fun catalogCapabilitiesOverrideFallbackAndDoNotGrantUpsellTiers() {
        val choice = ModelOptions.parse(JsonCodec.parseToJsonElement("""{
            "slug":"gpt-6-astra","display_name":"GPT-6 Astra",
            "supported_reasoning_levels":[{"effort":"low"},{"effort":"ultra"},{"effort":"unknown"}],
            "service_tiers":[{"id":"priority"},{"id":"fast"},{"id":"bogus"}],
            "additional_speed_tiers":[{"id":"ultrafast"}]
        }""").jsonObject, true)
        val p = Profile(name = "test", kind = ProviderKind.CHATGPT, model = choice.id, modelChoice = choice)
        assertEquals(listOf("low", "ultra"), ModelOptions.reasoning(p))
        assertEquals(listOf("priority"), ModelOptions.speeds(p))
        assertEquals(emptyList(), ModelOptions.reasoning(p.copy(modelChoice = choice.copy(reasoningEfforts = emptyList()))))
        assertEquals(emptyList(), ModelOptions.speeds(p.copy(modelChoice = null)))
        assertFalse(ModelOptions.reasoning(p.copy(modelChoice = null)).contains("none"))
    }

    @Test fun switchingModelsClearsUnsupportedSelectionsAndNeverEnablesPaidSpeed() {
        val p = Profile(name = "test", kind = ProviderKind.OPENAI, model = "gpt-6-astra", reasoningEffort = "max", serviceTier = "ultrafast")
        val next = ModelOptions.selected(p, ModelChoice("gpt-5.5", "GPT-5.5"))
        assertNull(next.reasoningEffort)
        assertNull(next.serviceTier)
        val astra = ModelOptions.selected(next, ModelChoice("gpt-6-astra", "GPT-6 Astra"))
        assertNull(astra.reasoningEffort)
        assertNull(astra.serviceTier)
        assertEquals(listOf("fast", "ultrafast"), ModelOptions.speeds(astra))
        assertEquals(listOf("none", "low", "medium", "high", "xhigh"), ModelOptions.reasoning(next))
        val unknown = astra.copy(model = "unannounced-model")
        assertTrue(ModelOptions.reasoning(unknown).isEmpty())
        assertTrue(ModelOptions.speeds(unknown).isEmpty())
        assertTrue(ModelOptions.reasoning(astra.copy(kind = ProviderKind.COMPATIBLE)).isEmpty())
    }

    @Test fun invalidSettingsFailBeforeAnyNetworkRequest() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val endpoint = server.url("/").toString().replace("localhost", "127.0.0.1")
            val p = Profile(name = "test", kind = ProviderKind.CHATGPT, model = "gpt-6-astra")
            for (invalid in listOf(p.copy(reasoningEffort = "none"), p.copy(serviceTier = "ultrafast"))) {
                val provider = ResponsesProvider(invalid, HttpTransport(endpoint, true)) { throw AssertionError("Invalid settings must fail before requesting credentials") }
                assertEquals("unsupported_model_setting", assertFailsWith<SafeFailure> {
                    provider.respond(listOf(message("user", "fixture"))) {}
                }.code)
            }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun chatGptCatalogRetainsOrderAndCapabilitiesWithoutInventingModels() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val endpoint = server.url("/").toString().replace("localhost", "127.0.0.1")
            server.enqueue(MockResponse().setBody("""{"models":[
                {"slug":"first","display_name":"First","visibility":"list","supported_reasoning_levels":["low","high"],"service_tiers":["ultrafast"]},
                {"slug":"hidden","visibility":"hide"},
                {"slug":"second","display_name":"Second","visibility":"list"}
            ]}"""))
            val provider = ResponsesProvider(Profile(name = "test", kind = ProviderKind.CHATGPT), HttpTransport(endpoint, true)) {
                BoundSecret(Destinations.origin(Destinations.url(endpoint, true)), "fixture")
            }
            val models = provider.models()
            assertEquals(listOf("first", "second"), models.map { it.id })
            assertEquals(listOf("low", "high"), models.first().reasoningEfforts)
            assertEquals(listOf("ultrafast"), models.first().serviceTiers)
            assertNull(models.last().reasoningEfforts)
            assertEquals("/models", server.takeRequest().path)
        } finally { server.shutdown() }
    }
}
