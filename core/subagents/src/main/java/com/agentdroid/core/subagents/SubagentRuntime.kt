package com.agentdroid.core.subagents

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SubagentToolCall(val id: String, val name: String, val input: JsonObject)

@Serializable
data class SubagentToolResult(val success: Boolean, val summary: String, val output: JsonObject = JsonObject(emptyMap()))

@Serializable
data class SubagentModelRequest(
    val role: SubagentRole,
    val instructions: String,
    val objective: String,
    val context: SubagentContext,
    val allowedTools: Set<String>,
    val tokenLimit: Int,
    val priorToolResults: List<SubagentToolResult> = emptyList()
)

@Serializable
data class SubagentModelResponse(
    val finalSummary: String? = null,
    val output: JsonObject = JsonObject(emptyMap()),
    val toolCalls: List<SubagentToolCall> = emptyList(),
    val artifactReferences: List<String> = emptyList(),
    val sourceReferences: List<String> = emptyList()
)

/** Adapter point for AgentModelClient; implementations must not expose hidden reasoning. */
fun interface SubagentModelGateway {
    suspend fun complete(request: SubagentModelRequest): SubagentModelResponse
}

/** Adapter point for ToolRegistry + PermissionEngine. */
fun interface SubagentToolGateway {
    suspend fun execute(toolName: String, input: JsonObject, task: SubagentTask): SubagentToolResult
}

interface Subagent {
    val role: SubagentRole
    suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload
}

@Serializable
data class SubagentResultPayload(
    val summary: String,
    val output: JsonObject = JsonObject(emptyMap()),
    val artifactReferences: List<String> = emptyList(),
    val sourceReferences: List<String> = emptyList()
)

fun interface SubagentFactory {
    fun create(profile: SubagentProfile): Subagent
}

interface SubagentExecutionScope {
    val profile: SubagentProfile
    val task: SubagentTask
    suspend fun executeTool(name: String, input: JsonObject): SubagentToolResult
    suspend fun delegate(role: SubagentRole, objective: String, context: SubagentContext = task.context): SubagentResult
}

/** A bounded model/tool loop reusable by every role. */
class ModelBackedSubagent(
    override val role: SubagentRole,
    private val model: SubagentModelGateway
) : Subagent {
    override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload {
        val results = mutableListOf<SubagentToolResult>()
        while (true) {
            val response = model.complete(
                SubagentModelRequest(
                    role = role,
                    instructions = scope.profile.instructions,
                    objective = task.objective,
                    context = task.context,
                    allowedTools = scope.profile.allowedTools,
                    tokenLimit = scope.profile.tokenLimit,
                    priorToolResults = results.toList()
                )
            )
            if (response.toolCalls.isEmpty()) {
                return SubagentResultPayload(
                    summary = response.finalSummary ?: "Subagent completed",
                    output = response.output,
                    artifactReferences = response.artifactReferences,
                    sourceReferences = response.sourceReferences
                )
            }
            response.toolCalls.forEach { results += scope.executeTool(it.name, it.input) }
        }
    }
}
