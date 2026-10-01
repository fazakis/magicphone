// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable

@Serializable
data class Parameter(val name: String, val kind: String = "text", val maxLength: Int = 500)

@Serializable
data class ScriptStep(
    val action: Action,
    val textParameter: String? = null,
    val findLabel: String? = null,
)

@Serializable
data class Script(
    val id: String = id(),
    val name: String,
    val parameters: List<Parameter> = emptyList(),
    val steps: List<ScriptStep>,
    val enabled: Boolean = false,
)

class ScriptRunner(private val gateway: Gateway) {
    companion object {
        fun validate(script: Script) {
            require(
                script.name.length in 1..100 &&
                    script.steps.size in 1..40 &&
                    script.parameters.size <= 12
            )
            require(script.parameters.map { it.name }.distinct().size == script.parameters.size)
            script.parameters.forEach {
                require(
                    it.name.matches(Regex("[a-z][a-z0-9_]{0,30}")) &&
                        it.kind in setOf("text", "integer") &&
                        it.maxLength in 1..2000
                )
            }
            script.steps.forEach {
                require(it.action.op !in setOf(Op.MCP, Op.MCP_CATALOG, Op.MEMORY_PROPOSAL))
                require(it.action.text.length <= 8000 && (it.findLabel?.length ?: 0) <= 200)
                if (it.textParameter != null)
                    require(script.parameters.any { p -> p.name == it.textParameter })
            }
            require(JsonCodec.encodeToString(Script.serializer(), script).length <= 64_000)
        }
    }

    suspend fun run(script: Script, values: Map<String, String>) =
        withTimeout(120_000) {
            validate(script)
            require(script.enabled)
            require(values.keys == script.parameters.map { it.name }.toSet())
            script.parameters.forEach { p ->
                val value = values.getValue(p.name)
                require(value.length <= p.maxLength)
                if (p.kind == "integer") require(value.toIntOrNull() != null)
            }
            val results = mutableListOf<ToolResult>()
            for (step in script.steps) {
                currentCoroutineContext().ensureActive()
                var action =
                    step.action.copy(
                        text = step.textParameter?.let(values::getValue) ?: step.action.text
                    )
                if (action.isDevice && action.op != Op.OPEN && action.op != Op.OBSERVE) {
                    val observation = gateway.run(Action(Op.OBSERVE, action.app))
                    val screen =
                        JsonCodec.decodeFromString(Screen.serializer(), observation.content)
                    action =
                        action.copy(
                            snapshot = screen.id,
                            node =
                                step.findLabel?.let { label ->
                                    screen.nodes.singleOrNull { it.label == label }?.ref
                                        ?: throw SafeFailure("missing_node")
                                } ?: action.node,
                        )
                }
                results += gateway.run(action)
            }
            results
        }
}
