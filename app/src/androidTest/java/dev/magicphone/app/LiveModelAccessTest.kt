// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import dev.magicphone.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Explicit owner-authorized capability probes. No tools, device actions, settings changes or token export. */
@RunWith(AndroidJUnit4::class)
class LiveModelAccessTest {
    @Test fun probeSolAndUltrafastThroughExistingChatGptAccount() = runBlocking {
        Assume.assumeTrue("Requires account-owner authorization and liveModelAccess=true",
            InstrumentationRegistry.getArguments().getString("liveModelAccess") == "true")
        Assert.assertTrue("Dedicated emulator only", android.os.Build.MODEL.contains("sdk"))
        Configurator.getInstance().setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = instrumentation.targetContext
        val r = context.runtime
        val originalSettings = r.settings.value
        val originalArchive = r.archive.value
        val selected = originalSettings.profiles.single { it.id == originalSettings.selected }
        Assert.assertEquals(ProviderKind.CHATGPT, selected.kind)
        Assert.assertTrue(originalSettings.activeAccount.isNotBlank())
        Assert.assertFalse(r.agent.state.value in setOf(RunState.PLANNING, RunState.ACTING, RunState.WAITING_APPROVAL))
        val accountKey = "account-${digest(originalSettings.activeAccount).take(40)}"
        val account = ChatGptAuth().fresh(
            { JsonCodec.decodeFromString(Account.serializer(), r.vault.read(accountKey) ?: error("missing_account")) },
            { r.vault.write(accountKey, JsonCodec.encodeToString(Account.serializer(), it)) },
        )
        val http = HttpTransport("https://api.openai.com/v1/")
        val secret = BoundSecret("https://api.openai.com:443", account.access)
        val results = mutableListOf<JsonElement>()
        fun safe(value: String) = value.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,120}")) }.orEmpty()
        val followup = InstrumentationRegistry.getArguments().getString("capabilityFollowup") == "true"
        val toolOnly = InstrumentationRegistry.getArguments().getString("toolOnly") == "true"
        val probes = if (toolOnly) emptyList() else if (followup) listOf("gpt-6-astra" to "priority") else listOf(
            "gpt-6-astra" to null,
            "gpt-6.1-sol" to null,
            "gpt-6-astra" to "ultrafast",
            "gpt-6.1-sol" to "ultrafast",
        )
        for ((model, tier) in probes) {
            // Deliberately probe the server rather than substituting account-catalog metadata.
            // The production model picker and its capability validation stay unchanged.
            val request = buildJsonObject {
                put("model", model)
                put("instructions", "Reply with exactly OK. Do not use tools.")
                put("input", JsonArray(listOf(message("user", "Return exactly OK."))))
                put("stream", true)
                put("store", false)
                putJsonObject("reasoning") { put("effort", "low") }
                tier?.let { put("service_tier", it) }
            }
            var httpStatus = 0
            var completed = false
            var returnedModel = ""
            var returnedTier = ""
            var failure = ""
            val output = StringBuilder()
            val start = SystemClock.elapsedRealtime()
            var firstTextMs: Long? = null
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "\nProbe started: model=$model, requestedTier=${tier ?: "default"}\n")
            })
            try {
                withTimeout(130_000) {
                    http.request("responses", request.toString(), secret, accept = "text/event-stream") { response ->
                        httpStatus = response.code
                        Sse.read(response) { event ->
                            val result = event["response"] as? JsonObject
                            result?.str("model")?.takeIf { it.isNotEmpty() }?.let { returnedModel = safe(it) }
                            result?.str("service_tier")?.takeIf { it.isNotEmpty() }?.let { returnedTier = safe(it) }
                            when (event.str("type")) {
                                "response.output_text.delta" -> {
                                    if (firstTextMs == null) firstTextMs = SystemClock.elapsedRealtime() - start
                                    if (output.length < 1000) output.append(event.str("delta").take(1000))
                                }
                                "response.completed" -> completed = true
                                "response.failed", "response.incomplete", "error" -> {
                                    val error = result?.get("error") as? JsonObject ?: event["error"] as? JsonObject
                                    failure = safe(error?.str("code") ?: event.str("code")).ifEmpty { safe(event.str("type")) }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                failure = if (e is SafeFailure) safe(e.code) else safe(e.javaClass.simpleName)
                httpStatus = when (failure) {
                    "session_expired" -> 401
                    "consent_or_eligibility" -> 403
                    "usage_limit" -> 429
                    else -> failure.removePrefix("network_").toIntOrNull() ?: httpStatus
                }
            }
            val report = buildJsonObject {
                put("requestedModel", model)
                put("requestedTier", tier ?: "default")
                put("httpStatus", httpStatus)
                put("completed", completed)
                put("exactMarkerReceived", output.toString().trim() == "OK")
                put("returnedModel", returnedModel)
                put("returnedTier", returnedTier)
                put("errorCode", failure)
                put("elapsedMs", SystemClock.elapsedRealtime() - start)
                firstTextMs?.let { put("firstTextMs", it) }
            }
            results += report
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "Probe result: $report\n") })
        }
        if (followup) {
            val profile = selected.copy(model = "gpt-6.1-sol", reasoningEffort = "low", serviceTier = null, modelChoice = null)
            val start = SystemClock.elapsedRealtime()
            val reply = r.provider(profile).respond(listOf(message("user",
                "This is a connection test. Call phone.perform exactly once with op COMPLETE and text Connection test complete. Do not inspect or interact with any app."))) {}
            val calls = reply.calls.flatMap { it.actions }
            val valid = calls.size == 1 && calls.single().op == Op.COMPLETE
            val report = buildJsonObject {
                put("requestedModel", "gpt-6.1-sol")
                put("probe", "production_namespace_tools")
                put("validCompleteCall", valid)
                put("parsedCallCount", reply.calls.size)
                put("parsedOperations", JsonArray(calls.map { JsonPrimitive(it.op.name) }))
                put("exactArgumentMarker", calls.singleOrNull()?.text == "Connection test complete")
                put("textReplyCharacters", reply.text.length)
                put("deviceActionsExecuted", 0)
                put("elapsedMs", SystemClock.elapsedRealtime() - start)
            }
            results += report
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "Probe result: $report\n") })
        }
        val reportName = if (followup) "model-access-followup.json" else "model-access-probe.json"
        File(context.getExternalFilesDir(null), reportName).writeText(JsonArray(results).toString())
        Assert.assertEquals("Selected profile/settings unchanged", originalSettings, r.settings.value)
        Assert.assertEquals("Conversation history unchanged", originalArchive, r.archive.value)
        if (probes.isNotEmpty()) Assert.assertTrue("Known available model must confirm working credentials",
            results.first().jsonObject["completed"]!!.jsonPrimitive.boolean)
        if (followup) Assert.assertTrue("Sol must return the production namespace function call",
            results.last().jsonObject["validCompleteCall"]!!.jsonPrimitive.boolean)
    }
}
