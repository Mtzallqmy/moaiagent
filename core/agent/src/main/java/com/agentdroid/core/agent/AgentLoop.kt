package com.agentdroid.core.agent

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

class AgentLoop(
    private val toolRegistry: ToolRegistry,
    private val permissionGateway: PermissionGateway,
    private val contextManager: ContextManager,
    private val auditSink: AuditSink = AuditSink.NOOP,
    private val config: AgentConfig = AgentConfig()
) {
    fun run(session: AgentSession, userRequest: String, model: AgentModelClient): Flow<AgentEvent> = flow {
        var state = AgentState(session = session)
        AgentRuntimeControl.begin(session.id, currentCoroutineContext()[kotlinx.coroutines.Job])
        emit(AgentEvent.StateChanged(state))
        if (session.mode != AgentMode.CHAT && !model.supportsToolCalling) {
            val error = AgentError.provider("Provider does not support tool calling; ${session.mode} mode is unavailable")
            emit(AgentEvent.Failed(error)); emit(AgentEvent.Done); return@flow
        }

        try {
            withTimeout(config.maxExecutionTimeMs) {
                val (systemPrompt, transcript) = contextManager.build(session, userRequest)
                val advertisedTools = toolRegistry.toolsForMode(session.mode)
                var consecutiveFailures = 0
                val repeatedFailures = mutableMapOf<String, Int>()
                var totalToolCalls = 0
                var finalText = ""

                for (turnIndex in 1..config.maxTurns) {
                    AgentRuntimeControl.checkpoint()
                    state = state.copy(turn = turnIndex, toolCalls = totalToolCalls, consecutiveFailures = consecutiveFailures)
                    emit(AgentEvent.StateChanged(state))
                    emit(AgentEvent.Timeline(AgentStep("Analyzing request", AgentStepStatus.RUNNING)))

                    val response = model.complete(
                        AgentModelRequest(systemPrompt, transcript.toList(), advertisedTools, session.modelId)
                    ) { event ->
                        when (event) {
                            AgentModelEvent.Started -> Unit
                            is AgentModelEvent.TextDelta -> emit(AgentEvent.TextDelta(event.text))
                            is AgentModelEvent.ToolCallStarted -> emit(AgentEvent.ToolCallStarted(ToolCall(event.id, event.name, buildJsonObject {})))
                            is AgentModelEvent.ToolCallDelta -> emit(AgentEvent.ToolCallArgumentsDelta(event.id, event.argumentsDelta))
                            is AgentModelEvent.ToolCallCompleted -> emit(AgentEvent.ToolCallCompleted(event.call))
                        }
                    }.getOrElse { failure ->
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        val error = AgentError.provider(failure.message ?: failure::class.java.simpleName)
                        emit(AgentEvent.Failed(error)); emit(AgentEvent.Done); return@withTimeout
                    }
                    finalText = response.text
                    AgentRuntimeControl.checkpoint()

                    if (response.toolCalls.isEmpty()) {
                        if (response.text.isNotBlank()) transcript += AgentMessage(AgentMessageRole.ASSISTANT, response.text)
                        state = state.copy(completed = true, toolCalls = totalToolCalls, consecutiveFailures = consecutiveFailures)
                        emit(AgentEvent.StateChanged(state))
                        emit(AgentEvent.Timeline(AgentStep("Done", AgentStepStatus.SUCCEEDED)))
                        emit(AgentEvent.FinalAnswer(response.text)); emit(AgentEvent.Done); return@withTimeout
                    }
                    if (session.mode == AgentMode.CHAT) {
                        val error = AgentError.modeRestriction(response.toolCalls.first().name, AgentMode.CHAT)
                        emit(AgentEvent.Failed(error)); emit(AgentEvent.Done); return@withTimeout
                    }

                    transcript += AgentMessage(AgentMessageRole.ASSISTANT, response.text, toolCalls = response.toolCalls)
                    for (call in response.toolCalls) {
                        AgentRuntimeControl.checkpoint()
                        if (totalToolCalls >= config.maxToolCalls) {
                            val error = AgentError(AgentErrorCode.AGENT_TOOL_CALL_LIMIT_REACHED, "Agent exceeded ${config.maxToolCalls} tool calls", "The agent reached its tool-call limit.", false)
                            emit(AgentEvent.Failed(error)); emit(AgentEvent.Done); return@withTimeout
                        }
                        totalToolCalls++
                        val tool = toolRegistry.get(call.name)
                        if (tool == null) {
                            val result = ToolResult.failure(AgentError.toolNotFound(call.name))
                            transcript += toolMessage(call, result)
                            consecutiveFailures++
                            audit(session, call, result, 0, "NOT_FOUND", PermissionDecision.DENY, null)
                            emit(AgentEvent.ToolFinished(call, result, 0))
                            emit(AgentEvent.Timeline(AgentStep(result.summary, AgentStepStatus.FAILED, call.id)))
                            if (failureLimitReached(call, result, consecutiveFailures, repeatedFailures)) {
                                emitFailureLimit(consecutiveFailures) { emit(it) }; return@withTimeout
                            }
                            continue
                        }

                        emit(AgentEvent.Timeline(AgentStep(stepLabel(tool.definition, call), AgentStepStatus.RUNNING, call.id)))
                        val toolContext = ToolContext(session.workspaceId, session.conversationId, session.id, session.mode)
                        val previewResult = toolRegistry.preview(call, toolContext)
                        val previewFailure = previewResult.exceptionOrNull()
                        if (previewFailure != null) {
                            val error = (previewFailure as? ToolRegistryException)?.agentError ?: AgentError.internal(previewFailure.message ?: "Tool preview failed")
                            val result = ToolResult.failure(error)
                            transcript += toolMessage(call, result)
                            consecutiveFailures++
                            audit(session, call, result, 0, "VALIDATION_FAILED", PermissionDecision.DENY, toolContext)
                            emit(AgentEvent.ToolFinished(call, result, 0))
                            emit(AgentEvent.Timeline(AgentStep(result.summary, AgentStepStatus.FAILED, call.id)))
                            if (failureLimitReached(call, result, consecutiveFailures, repeatedFailures)) {
                                emitFailureLimit(consecutiveFailures) { emit(it) }; return@withTimeout
                            }
                            continue
                        }

                        val riskResult = toolRegistry.effectiveRisk(call, toolContext)
                        val riskFailure = riskResult.exceptionOrNull()
                        if (riskFailure != null) {
                            val error = (riskFailure as? ToolRegistryException)?.agentError ?: AgentError.internal(riskFailure.message ?: "Tool risk classification failed")
                            val result = ToolResult.failure(error)
                            transcript += toolMessage(call, result)
                            consecutiveFailures++
                            audit(session, call, result, 0, "CLASSIFICATION_FAILED", PermissionDecision.DENY, toolContext)
                            emit(AgentEvent.ToolFinished(call, result, 0))
                            continue
                        }
                        val effectiveRisk = riskResult.getOrThrow()
                        val effectiveDefinition = tool.definition.copy(riskLevel = effectiveRisk)
                        val permissionRequest = PermissionRequest(
                            requestId = UUID.randomUUID().toString(),
                            toolCall = call,
                            definition = effectiveDefinition,
                            workspaceId = session.workspaceId,
                            conversationId = session.conversationId,
                            sessionId = session.id,
                            reason = call.input["reason"]?.jsonPrimitive?.contentOrNull,
                            preview = previewResult.getOrNull(),
                            ruleKey = runCatching { toolRegistry.permissionKey(call, toolContext) }.getOrNull()
                        )
                        if (effectiveRisk != RiskLevel.SAFE) {
                            emit(AgentEvent.Timeline(AgentStep("Waiting for approval: ${effectiveDefinition.name}", AgentStepStatus.WAITING_PERMISSION, call.id)))
                            emit(AgentEvent.PermissionRequired(permissionRequest))
                        }
                        AgentRuntimeControl.checkpoint()
                        val permission = permissionGateway.authorize(permissionRequest)
                        if (permission.decision != PermissionDecision.ALLOW) {
                            val result = ToolResult.failure(AgentError.permissionDenied(call.name))
                            transcript += toolMessage(call, result)
                            consecutiveFailures++
                            audit(session, call, result, 0, "DENIED", permission.decision, toolContext)
                            emit(AgentEvent.ToolFinished(call, result, 0))
                            emit(AgentEvent.Timeline(AgentStep(result.summary, AgentStepStatus.FAILED, call.id)))
                            if (failureLimitReached(call, result, consecutiveFailures, repeatedFailures)) {
                                emitFailureLimit(consecutiveFailures) { emit(it) }; return@withTimeout
                            }
                            continue
                        }

                        AgentRuntimeControl.checkpoint()
                        val started = System.nanoTime()
                        val result = toolRegistry.execute(call, toolContext)
                        val durationMs = (System.nanoTime() - started) / 1_000_000
                        audit(session, call, result, durationMs, if (result.success) "SUCCEEDED" else "FAILED", permission.decision, toolContext)
                        emit(AgentEvent.ToolFinished(call, result, durationMs))
                        emit(AgentEvent.Timeline(AgentStep(result.summary, if (result.success) AgentStepStatus.SUCCEEDED else AgentStepStatus.FAILED, call.id)))
                        transcript += toolMessage(call, result)

                        if (result.success) consecutiveFailures = 0
                        else {
                            consecutiveFailures++
                            if (failureLimitReached(call, result, consecutiveFailures, repeatedFailures)) {
                                emitFailureLimit(consecutiveFailures) { emit(it) }; return@withTimeout
                            }
                        }
                    }
                }

                val error = AgentError(AgentErrorCode.AGENT_TURN_LIMIT_REACHED, "Agent exceeded ${config.maxTurns} turns; last text=${finalText.take(160)}", "The agent reached its turn limit before finishing.", false)
                state = state.copy(lastError = error)
                emit(AgentEvent.StateChanged(state)); emit(AgentEvent.Failed(error)); emit(AgentEvent.Done)
            }
        } catch (_: TimeoutCancellationException) {
            val error = AgentError(AgentErrorCode.AGENT_TIMEOUT, "Agent exceeded ${config.maxExecutionTimeMs} ms", "The agent task timed out.", true)
            emit(AgentEvent.Failed(error)); emit(AgentEvent.Done)
        } finally {
            permissionGateway.clearSession(session.id)
            AgentRuntimeControl.finish(session.id)
        }
    }

    private suspend fun audit(
        session: AgentSession,
        call: ToolCall,
        result: ToolResult,
        durationMs: Long,
        status: String,
        permission: PermissionDecision,
        toolContext: ToolContext?
    ) {
        val input = toolContext?.let { toolRegistry.auditInputSummary(call, it) } ?: summarizeInput(call)
        val metadata = buildMap {
            listOf(
                "command", "cwd", "exitCode", "processId", "sessionId", "gitAction", "timedOut",
                "url", "domain", "elementId", "formAction", "query", "researchSessionId", "sourceId",
                "taskId", "taskStepId", "artifactId", "subagentId", "subagentRole"
            ).forEach { key ->
                result.output[key]?.let { value -> put(key, value.toString().trim('"').take(500)) }
            }
        }
        auditSink.record(
            AuditEntry(
                call.id,
                call.name,
                input.take(1_000),
                result.summary.take(500),
                durationMs,
                status,
                permission,
                System.currentTimeMillis(),
                session.workspaceId,
                session.conversationId,
                metadata
            )
        )
    }

    private fun toolMessage(call: ToolCall, result: ToolResult): AgentMessage {
        val outputText = buildString {
            append(if (result.success) "SUCCESS" else "ERROR").append(": ").append(result.summary)
            result.error?.let { append("\ncode=").append(it.code.name).append("\nmessage=").append(it.userMessage) }
            if (result.output.isNotEmpty()) append("\noutput=").append(result.output.toString())
            result.changeSetId?.let { append("\nchangeSetId=").append(it) }
            if (result.truncated) append("\ntruncated=true")
        }.take(config.toolResultCharacterLimit)
        return AgentMessage(AgentMessageRole.TOOL, outputText, toolCallId = call.id, toolName = call.name)
    }

    private fun failureLimitReached(call: ToolCall, result: ToolResult, consecutiveFailures: Int, repeatedFailures: MutableMap<String, Int>): Boolean {
        if (result.success) return false
        val signature = "${call.name}:${call.input}:${result.error?.code}"
        repeatedFailures[signature] = (repeatedFailures[signature] ?: 0) + 1
        return consecutiveFailures >= config.maxConsecutiveFailures || (repeatedFailures[signature] ?: 0) > config.maxRepeatedFailureSignature
    }

    private suspend fun emitFailureLimit(consecutiveFailures: Int, emitEvent: suspend (AgentEvent) -> Unit) {
        val error = AgentError(AgentErrorCode.CONSECUTIVE_FAILURE_LIMIT_REACHED, "Agent stopped after $consecutiveFailures consecutive tool failures", "The agent stopped after repeated tool failures.", false)
        emitEvent(AgentEvent.Failed(error)); emitEvent(AgentEvent.Done)
    }

    private fun stepLabel(definition: ToolDefinition, call: ToolCall): String {
        val path = call.input["path"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val command = call.input["command"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        return when (definition.category) {
            ToolCategory.FILE_READ -> "Reading ${path ?: "workspace"}"
            ToolCategory.FILE_SEARCH -> "Searching workspace"
            ToolCategory.FILE_MODIFY -> "Preparing ${definition.name}"
            ToolCategory.FILE_DESTRUCTIVE -> "Preparing destructive change"
            ToolCategory.SHELL -> "Running ${command?.take(80) ?: definition.name}"
            ToolCategory.PROCESS -> "Managing process"
            ToolCategory.GIT_READ, ToolCategory.GIT_MODIFY, ToolCategory.GIT_DESTRUCTIVE -> "Git: ${definition.name.removePrefix("git_")}"
            else -> "Running ${definition.name}"
        }
    }

    private fun summarizeInput(call: ToolCall): String {
        val safeKeys = listOf(
            "path", "source", "destination", "query", "glob", "startLine", "endLine", "overwrite",
            "createParents", "cwd", "processId", "url", "elementId", "direction", "taskId", "stepId",
            "artifactId", "researchSessionId", "sourceId", "role"
        )
        return buildJsonObject { safeKeys.forEach { key -> call.input[key]?.let { put(key, it) } } }.toString().take(1_000)
    }
}
