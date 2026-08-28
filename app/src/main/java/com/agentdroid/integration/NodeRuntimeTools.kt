package com.agentdroid.integration

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.AgentTool
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCategory
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolDefinition
import com.agentdroid.core.agent.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

fun createNodeRuntimeTools(runtime: EmbeddedNodeRuntime): List<AgentTool> = listOf(
    object : AgentTool {
        override val definition = ToolDefinition("node_version", "Return the execution-verified embedded Node.js version.", schema(emptyMap()), RiskLevel.SAFE, ToolCategory.RUNTIME)
        override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult = runCatching { runtime.version() }.fold(
            { ToolResult.success("Node.js $it", JsonObject(mapOf("version" to JsonPrimitive(it)))) },
            { ToolResult.failure(AgentError.commandFailed(it.message ?: "Node.js is unavailable")) }
        )
    },
    object : AgentTool {
        override val definition = ToolDefinition("node_run", "Execute bounded JavaScript with embedded Node.js in the current AgentDroid workspace.", schema(mapOf("code" to "string", "timeoutMs" to "integer"), listOf("code")), RiskLevel.SENSITIVE, ToolCategory.RUNTIME)
        override suspend fun permissionKey(input: JsonObject, context: ToolContext): String = "node_run"
        override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
            val code = input["code"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.failure(AgentError.validation("code is required"))
            val timeout = input["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 15_000
            return runCatching { runtime.runCode(context.workspaceId, code, timeout) }.fold(::executionResult) { ToolResult.failure(AgentError.commandFailed(it.message ?: "Node.js execution failed")) }
        }
    },
    object : AgentTool {
        override val definition = ToolDefinition("node_run_script", "Execute a JavaScript file contained in the current AgentDroid workspace.", schema(mapOf("path" to "string", "timeoutMs" to "integer"), listOf("path")), RiskLevel.SENSITIVE, ToolCategory.RUNTIME)
        override suspend fun permissionKey(input: JsonObject, context: ToolContext): String = "node_run_script"
        override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
            val path = input["path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.failure(AgentError.validation("path is required"))
            val timeout = input["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 30_000
            return runCatching { runtime.runScript(context.workspaceId, path, timeoutMs = timeout) }.fold(::executionResult) { ToolResult.failure(AgentError.commandFailed(it.message ?: "Node.js script failed")) }
        }
    }
)

private fun executionResult(result: NodeExecutionResult): ToolResult {
    val output = JsonObject(mapOf(
        "exitCode" to JsonPrimitive(result.exitCode),
        "stdout" to JsonPrimitive(result.stdout),
        "stderr" to JsonPrimitive(result.stderr),
        "timedOut" to JsonPrimitive(result.timedOut),
        "durationMs" to JsonPrimitive(result.durationMs)
    ))
    return if (result.exitCode == 0 && !result.timedOut) ToolResult.success("Node.js execution completed", output)
    else ToolResult.failure(if (result.timedOut) AgentError.commandTimeout(result.durationMs) else AgentError.commandFailed(result.stderr.ifBlank { "Node exited ${result.exitCode}" }), output)
}

private fun schema(properties: Map<String, String>, required: List<String> = emptyList()): JsonObject = JsonObject(buildMap {
    put("type", JsonPrimitive("object"))
    put("properties", JsonObject(properties.mapValues { JsonObject(mapOf("type" to JsonPrimitive(it.value))) }))
    if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
})
