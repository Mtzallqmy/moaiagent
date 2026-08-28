package com.agentdroid.core.subagents

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class SubagentRole { CODING, RESEARCH, BROWSER, REVIEW }

@Serializable
enum class SubagentStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

@Serializable
enum class ContextSection {
    TASK_SUMMARY,
    WORKSPACE_SUMMARY,
    SELECTED_FILES,
    GIT_DIFF,
    RESEARCH_FINDINGS,
    SOURCE_REFERENCES,
    BROWSER_STATE,
    ARTIFACT_REFERENCES,
    SUBAGENT_RESULTS
}

/** A deliberately bounded context envelope; it is never the main agent transcript. */
@Serializable
data class SubagentContext(
    val workspaceId: String,
    val conversationId: String,
    val sections: Map<ContextSection, String> = emptyMap()
)

@Serializable
data class SubagentProfile(
    val role: SubagentRole,
    val instructions: String,
    val allowedTools: Set<String>,
    val allowedContext: Set<ContextSection>,
    val maxContextCharacters: Int,
    val tokenLimit: Int,
    val toolCallLimit: Int
) {
    init {
        require(instructions.isNotBlank())
        require(maxContextCharacters > 0 && tokenLimit > 0 && toolCallLimit >= 0)
    }
}

@Serializable
data class SubagentTask(
    val id: String,
    val role: SubagentRole,
    val objective: String,
    val context: SubagentContext,
    val rootTaskId: String? = null,
    val parentTaskId: String? = null,
    val parentSubagentId: String? = null,
    val delegationDepth: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(id.isNotBlank() && objective.isNotBlank())
        require(delegationDepth >= 0)
    }
}

@Serializable
data class SubagentFailure(
    val code: SubagentFailureCode,
    val summary: String,
    val recoverable: Boolean,
    val attempt: Int
)

@Serializable
enum class SubagentFailureCode {
    EXECUTION_FAILED,
    TIMED_OUT,
    TOOL_NOT_ALLOWED,
    TOOL_LIMIT_REACHED,
    SUBAGENT_LIMIT_REACHED,
    CONCURRENT_LIMIT_REACHED,
    DELEGATION_DEPTH_EXCEEDED,
    CANCELLED
}

@Serializable
data class SubagentResult(
    val subagentId: String,
    val taskId: String,
    val role: SubagentRole,
    val status: SubagentStatus,
    val summary: String,
    val output: JsonObject = JsonObject(emptyMap()),
    val artifactReferences: List<String> = emptyList(),
    val sourceReferences: List<String> = emptyList(),
    val failure: SubagentFailure? = null,
    val attempts: Int = 1,
    val startedAt: Long,
    val finishedAt: Long
)

@Serializable
data class SubagentLimits(
    val maxSubagents: Int = 8,
    val maxConcurrentSubagents: Int = 3,
    val maxDelegationDepth: Int = 2,
    val maxTaskDurationMs: Long = 120_000,
    val defaultTokenLimit: Int = 8_000,
    val defaultToolCallLimit: Int = 24,
    val maxRetries: Int = 1
) {
    init {
        require(maxSubagents > 0 && maxConcurrentSubagents > 0)
        require(maxDelegationDepth >= 0 && maxTaskDurationMs > 0)
        require(defaultTokenLimit > 0 && defaultToolCallLimit >= 0 && maxRetries >= 0)
    }
}

/** UI-safe projection: summaries and state only, never prompts or model reasoning. */
@Serializable
data class SubagentTimelineItem(
    val subagentId: String,
    val parentSubagentId: String?,
    val taskId: String,
    val role: SubagentRole,
    val label: String,
    val status: SubagentStatus,
    val startedAt: Long?,
    val finishedAt: Long?,
    val failureSummary: String? = null
)

class SubagentLimitReached(
    val failureCode: SubagentFailureCode = SubagentFailureCode.SUBAGENT_LIMIT_REACHED,
    message: String
) : IllegalStateException(message)
class DelegationDepthExceeded(message: String) : IllegalStateException(message)
class SubagentToolDenied(message: String) : IllegalStateException(message)
class SubagentToolLimitReached(message: String) : IllegalStateException(message)
