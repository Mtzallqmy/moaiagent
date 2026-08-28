package com.agentdroid.integration

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.AgentTool
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCategory
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolDefinition
import com.agentdroid.core.agent.ToolResult
import kotlinx.serialization.json.*

fun createPrivilegedRuntimeTools(shizuku: ShizukuCapability, root: RootCapability): List<AgentTool> = listOf(
    object : AgentTool {
        override val definition = ToolDefinition("shizuku_status", "Report optional Shizuku/Sui binder and permission status.", schema(emptyMap()), RiskLevel.SAFE, ToolCategory.RUNTIME)
        override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
            val s = shizuku.status()
            return ToolResult.success("Shizuku ${if (s.permissionGranted) "ready" else if (s.binderAvailable) "permission required" else "unavailable"}", JsonObject(mapOf(
                "binderAvailable" to JsonPrimitive(s.binderAvailable), "permissionGranted" to JsonPrimitive(s.permissionGranted), "serverVersion" to JsonPrimitive(s.serverVersion ?: -1), "serverUid" to JsonPrimitive(s.serverUid ?: -1), "preV11" to JsonPrimitive(s.preV11)
            )))
        }
    },
    object : AgentTool {
        override val definition = ToolDefinition("shizuku_run", "Execute bounded argv in an optional Shizuku UserService shell/root context.", schema(mapOf("argv" to "array", "timeoutMs" to "integer"), listOf("argv")), RiskLevel.SENSITIVE, ToolCategory.RUNTIME)
        override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
        override suspend fun permissionKey(input: JsonObject, context: ToolContext) = "shizuku_run"
        override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
            val argv = input["argv"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return ToolResult.failure(AgentError.validation("argv is required"))
            val timeout = input["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 20_000
            return runCatching { shizuku.execute(argv, timeoutMs = timeout) }.fold(::asToolResult) { ToolResult.failure(AgentError.commandFailed(it.message ?: "Shizuku execution failed")) }
        }
    },
    object : AgentTool {
        override val definition = ToolDefinition("root_status", "Report whether optional root/su is detected; AgentDroid never requires root.", schema(emptyMap()), RiskLevel.SAFE, ToolCategory.RUNTIME)
        override suspend fun execute(input: JsonObject, context: ToolContext) = ToolResult.success("Root ${if (root.available()) "detected" else "not detected"}", JsonObject(mapOf("detected" to JsonPrimitive(root.available()))))
    },
    object : AgentTool {
        override val definition = ToolDefinition("root_run", "Execute a bounded argv command through optional su. Always requires explicit sensitive approval.", schema(mapOf("argv" to "array", "timeoutMs" to "integer"), listOf("argv")), RiskLevel.SENSITIVE, ToolCategory.RUNTIME)
        override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
        override suspend fun permissionKey(input: JsonObject, context: ToolContext) = "root_run"
        override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
            val argv = input["argv"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return ToolResult.failure(AgentError.validation("argv is required"))
            val timeout = input["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 20_000
            return runCatching { root.execute(argv, timeout) }.fold(::asToolResult) { ToolResult.failure(AgentError.commandFailed(it.message ?: "Root execution failed")) }
        }
    }
)

private fun asToolResult(r: PrivilegedExecutionResult): ToolResult {
    val output = JsonObject(mapOf("exitCode" to JsonPrimitive(r.exitCode), "stdout" to JsonPrimitive(r.stdout), "stderr" to JsonPrimitive(r.stderr), "timedOut" to JsonPrimitive(r.timedOut), "durationMs" to JsonPrimitive(r.durationMs)))
    return if (r.exitCode == 0 && !r.timedOut) ToolResult.success("Privileged command completed", output) else ToolResult.failure(if (r.timedOut) AgentError.commandTimeout(r.durationMs) else AgentError.commandFailed(r.stderr.ifBlank { "Command exited ${r.exitCode}" }), output)
}

private fun schema(properties: Map<String, String>, required: List<String> = emptyList()): JsonObject = JsonObject(buildMap {
    put("type", JsonPrimitive("object")); put("properties", JsonObject(properties.mapValues { JsonObject(mapOf("type" to JsonPrimitive(it.value))) })); if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
})
