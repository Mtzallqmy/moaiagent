package com.agentdroid.core.subagents

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.AgentTool
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCategory
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolDefinition
import com.agentdroid.core.agent.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DelegateTaskTool(
    private val coordinator: SubagentCoordinator
) : AgentTool {
    override val definition = ToolDefinition(
        name = "delegate_task",
        description = "Delegate a bounded task to a specialized CODING, RESEARCH, BROWSER, or REVIEW subagent.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add(JsonPrimitive("role")); add(JsonPrimitive("task")) })
            put("properties", buildJsonObject {
                put("role", buildJsonObject { put("type", "string") })
                put("task", buildJsonObject { put("type", "string") })
                put("context", buildJsonObject {})
                put("parentTaskId", buildJsonObject { put("type", "string") })
                put("delegationDepth", buildJsonObject { put("type", "integer") })
            })
        },
        riskLevel = RiskLevel.MODIFY,
        category = ToolCategory.SENSITIVE
    )

    override suspend fun effectiveRisk(input: JsonObject, context: ToolContext): RiskLevel = when (parseRole(input)) {
        SubagentRole.REVIEW -> RiskLevel.SAFE
        SubagentRole.RESEARCH -> RiskLevel.EXTERNAL
        SubagentRole.BROWSER -> RiskLevel.EXTERNAL
        SubagentRole.CODING -> RiskLevel.MODIFY
    }

    override fun availableInMode(mode: AgentMode): Boolean = mode != AgentMode.CHAT

    override fun auditInputSummary(input: JsonObject, context: ToolContext): String = buildJsonObject {
        input["role"]?.let { put("role", it) }
        input["task"]?.jsonPrimitive?.contentOrNull?.let { put("task", it.take(240)) }
        input["parentTaskId"]?.let { put("parentTaskId", it) }
        input["delegationDepth"]?.let { put("delegationDepth", it) }
    }.toString()

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val role = runCatching { parseRole(input) }.getOrElse { return ToolResult.failure(AgentError.validation(it.message ?: "Invalid role")) }
        val objective = input["task"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.failure(AgentError.validation("task must not be blank"))
        val depth = input["delegationDepth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val sections = parseContext(input["context"])
        val task = SubagentTask(
            id = context.toolCallId ?: "delegate-${System.currentTimeMillis()}",
            role = role,
            objective = objective,
            context = SubagentContext(context.workspaceId, context.conversationId, sections),
            parentTaskId = input["parentTaskId"]?.jsonPrimitive?.contentOrNull,
            delegationDepth = depth
        )
        return try {
            val result = coordinator.delegate(task)
            if (result.status == SubagentStatus.COMPLETED) {
                ToolResult.success(result.summary, result.toToolOutput())
            } else {
                ToolResult.failure(
                    AgentError.internal(result.failure?.summary ?: "Subagent failed"),
                    result.toToolOutput()
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (depthFailure: DelegationDepthExceeded) {
            ToolResult.failure(AgentError.validation(depthFailure.message ?: "Delegation depth exceeded"))
        } catch (limit: SubagentLimitReached) {
            ToolResult.failure(AgentError.internal(limit.message ?: "Subagent limit reached"))
        }
    }

    private fun parseRole(input: JsonObject): SubagentRole {
        val value = input["role"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing role")
        return runCatching { SubagentRole.valueOf(value.uppercase()) }
            .getOrElse { throw IllegalArgumentException("Unsupported subagent role: $value") }
    }

    private fun parseContext(value: kotlinx.serialization.json.JsonElement?): Map<ContextSection, String> = when (value) {
        null -> emptyMap()
        is JsonPrimitive -> mapOf(ContextSection.TASK_SUMMARY to value.content)
        is JsonObject -> value.mapNotNull { (key, item) ->
            val section = runCatching { ContextSection.valueOf(key.uppercase()) }.getOrNull() ?: return@mapNotNull null
            val text = (item as? JsonPrimitive)?.contentOrNull ?: item.toString()
            section to text
        }.toMap()
        else -> mapOf(ContextSection.TASK_SUMMARY to value.toString())
    }

    private fun SubagentResult.toToolOutput() = buildJsonObject {
        put("subagentId", subagentId)
        put("taskId", taskId)
        put("role", role.name)
        put("status", status.name)
        put("summary", summary)
        put("attempts", attempts)
        put("artifacts", JsonArray(artifactReferences.map(::JsonPrimitive)))
        put("sources", JsonArray(sourceReferences.map(::JsonPrimitive)))
        put("result", output)
        failure?.let {
            put("failure", buildJsonObject {
                put("code", it.code.name)
                put("summary", it.summary)
                put("recoverable", it.recoverable)
            })
        }
    }
}
