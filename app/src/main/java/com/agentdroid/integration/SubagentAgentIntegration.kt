package com.agentdroid.integration

import com.agentdroid.core.agent.AgentMessage
import com.agentdroid.core.agent.AgentMessageRole
import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.AgentModelClient
import com.agentdroid.core.agent.AgentModelRequest
import com.agentdroid.core.agent.AuditEntry
import com.agentdroid.core.agent.AuditSink
import com.agentdroid.core.agent.PermissionDecision
import com.agentdroid.core.agent.PermissionGateway
import com.agentdroid.core.agent.PermissionRequest
import com.agentdroid.core.agent.ToolCall
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolRegistry
import com.agentdroid.core.subagents.DefaultSubagentCoordinator
import com.agentdroid.core.subagents.DelegateTaskTool
import com.agentdroid.core.subagents.ModelBackedSubagent
import com.agentdroid.core.subagents.SubagentFactory
import com.agentdroid.core.subagents.SubagentModelGateway
import com.agentdroid.core.subagents.SubagentModelResponse
import com.agentdroid.core.subagents.SubagentTask
import com.agentdroid.core.subagents.SubagentToolCall
import com.agentdroid.core.subagents.SubagentToolGateway
import com.agentdroid.core.subagents.SubagentToolResult
import com.agentdroid.core.subagents.SubagentTimelineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

object SubagentTimelineHub {
    private val mutableItems = MutableStateFlow<List<SubagentTimelineItem>>(emptyList())
    val items: StateFlow<List<SubagentTimelineItem>> = mutableItems.asStateFlow()
    internal fun publish(value: List<SubagentTimelineItem>) { mutableItems.value = value }
}

/** Connects bounded subagents to the existing model, ToolRegistry and PermissionEngine. */
fun toolRegistryWithSubagents(
    base: ToolRegistry,
    model: AgentModelClient,
    modelId: String,
    permissions: PermissionGateway,
    audit: AuditSink,
    onTimeline: suspend (List<SubagentTimelineItem>) -> Unit = {}
): ToolRegistry {
    val modelGateway = SubagentModelGateway { request ->
        val available = request.allowedTools.mapNotNull(base::get).map { it.definition }
        val context = request.context.sections.entries.joinToString("\n") { (section, value) -> "${section.name}: $value" }
        val response = model.complete(
            AgentModelRequest(
                systemPrompt = request.instructions + "\nReturn only a concise result or bounded tool calls. Never expose hidden reasoning.",
                messages = listOf(AgentMessage(AgentMessageRole.USER, "${request.objective}\n\n$context")),
                tools = available,
                modelId = modelId
            )
        ).getOrThrow()
        SubagentModelResponse(
            finalSummary = response.text.takeIf(String::isNotBlank),
            toolCalls = response.toolCalls.map { SubagentToolCall(it.id, it.name, it.input) }
        )
    }
    val toolGateway = SubagentToolGateway { name, input, task ->
        executeSubagentTool(base, permissions, audit, name, input, task)
    }
    val coordinator = DefaultSubagentCoordinator(
        factory = SubagentFactory { profile -> ModelBackedSubagent(profile.role, modelGateway) },
        toolGateway = toolGateway
    )
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        coordinator.timeline.collect { items ->
            SubagentTimelineHub.publish(items)
            onTimeline(items)
        }
    }
    return ToolRegistry().also { registry ->
        base.list().forEach { definition -> base.get(definition.name)?.let(registry::register) }
        registry.register(DelegateTaskTool(coordinator))
    }
}

private suspend fun executeSubagentTool(
    registry: ToolRegistry,
    permissions: PermissionGateway,
    audit: AuditSink,
    name: String,
    input: kotlinx.serialization.json.JsonObject,
    task: SubagentTask
): SubagentToolResult {
    val id = "subagent-${UUID.randomUUID()}"
    val call = ToolCall(id, name, input)
    val context = ToolContext(
        workspaceId = task.context.workspaceId,
        conversationId = task.context.conversationId,
        sessionId = task.rootTaskId ?: task.id,
        mode = AgentMode.AGENT,
        attributes = mapOf("subagentRole" to task.role.name, "delegationDepth" to task.delegationDepth.toString()),
        toolCallId = id
    )
    val startedAt = System.currentTimeMillis()
    val tool = registry.get(name) ?: return SubagentToolResult(false, "Tool is unavailable")
    val risk = registry.effectiveRisk(call, context).getOrElse { return SubagentToolResult(false, it.message ?: "Tool classification failed") }
    val preview = registry.preview(call, context).getOrNull()
    val outcome = permissions.authorize(
        PermissionRequest(
            requestId = UUID.randomUUID().toString(),
            toolCall = call,
            definition = tool.definition.copy(riskLevel = risk),
            workspaceId = context.workspaceId,
            conversationId = context.conversationId,
            sessionId = context.sessionId,
            reason = "Delegated ${task.role.name.lowercase()} task",
            preview = preview,
            ruleKey = registry.permissionKey(call, context)
        )
    )
    if (outcome.decision != PermissionDecision.ALLOW) {
        audit.record(
            AuditEntry(id, name, registry.auditInputSummary(call, context) ?: "{}", "Denied", 0, "DENIED", outcome.decision,
                System.currentTimeMillis(), context.workspaceId, context.conversationId,
                mapOf("subagentRole" to task.role.name, "taskId" to task.id))
        )
        return SubagentToolResult(false, "Permission denied")
    }
    val result = registry.execute(call, context)
    val duration = System.currentTimeMillis() - startedAt
    audit.record(
        AuditEntry(id, name, registry.auditInputSummary(call, context) ?: "{}", result.summary.take(500), duration,
            if (result.success) "SUCCEEDED" else "FAILED", outcome.decision, System.currentTimeMillis(),
            context.workspaceId, context.conversationId, mapOf("subagentRole" to task.role.name, "taskId" to task.id))
    )
    return SubagentToolResult(result.success, result.summary, buildJsonObject {
        put("tool", name)
        put("result", result.output)
    })
}
