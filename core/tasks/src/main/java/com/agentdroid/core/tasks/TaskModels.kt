package com.agentdroid.core.tasks

import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus {
    PENDING, RUNNING, WAITING_PERMISSION, WAITING_USER, COMPLETED, FAILED, CANCELLED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

@Serializable
enum class TaskWaitReason { NONE, PERMISSION, USER_INPUT, PAUSED, RECOVERY_REQUIRED }

@Serializable
data class TaskStep(
    val id: String,
    val title: String,
    val description: String? = null,
    val position: Int,
    val status: TaskStatus = TaskStatus.PENDING,
    val retryCount: Int = 0,
    val maxRetries: Int = 2,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null
) {
    init {
        require(id.isNotBlank() && title.isNotBlank())
        require(position >= 0 && retryCount >= 0 && maxRetries >= 0)
        require(status != TaskStatus.COMPLETED || finishedAt != null)
    }
}

/** A concise, user-visible execution plan. It must never contain hidden reasoning. */
@Serializable
data class TaskPlan(
    val summary: String,
    val steps: List<TaskStep>,
    val revision: Int = 1,
    val updatedAt: Long
) {
    init {
        require(summary.isNotBlank())
        require(revision > 0)
        require(steps.isNotEmpty())
        require(steps.map { it.id }.distinct().size == steps.size)
        require(steps.map { it.position } == steps.indices.toList())
    }
}

@Serializable
data class ArtifactRef(
    val artifactId: String,
    val title: String,
    val type: String,
    val uri: String? = null
)

@Serializable
data class Task(
    val id: String,
    val title: String,
    val workspaceId: String,
    val conversationId: String,
    val plan: TaskPlan,
    val status: TaskStatus = TaskStatus.PENDING,
    val waitReason: TaskWaitReason = TaskWaitReason.NONE,
    val progress: Int = 0,
    val currentStepId: String? = null,
    val artifacts: List<ArtifactRef> = emptyList(),
    val createdAt: Long,
    val startedAt: Long? = null,
    val updatedAt: Long = createdAt,
    val finishedAt: Long? = null,
    val failure: String? = null,
    val recoveryRequired: Boolean = false,
    val revision: Long = 0
) {
    init {
        require(id.isNotBlank() && title.isNotBlank())
        require(workspaceId.isNotBlank() && conversationId.isNotBlank())
        require(progress in 0..100)
        require(currentStepId == null || plan.steps.any { it.id == currentStepId })
        require(status != TaskStatus.COMPLETED || progress == 100)
    }
}

@Serializable
enum class TaskEventType {
    CREATED, STARTED, PLAN_UPDATED, STEP_STARTED, STEP_COMPLETED, STEP_FAILED,
    WAITING_PERMISSION, WAITING_USER, PAUSED, RESUMED, RETRIED, CANCELLED,
    COMPLETED, FAILED, RECOVERY_REQUIRED, ARTIFACT_ATTACHED
}

@Serializable
data class TaskEvent(
    val id: String,
    val taskId: String,
    val type: TaskEventType,
    val timestamp: Long,
    val stepId: String? = null,
    val message: String? = null,
    val taskRevision: Long
)

data class NewTask(
    val title: String,
    val workspaceId: String,
    val conversationId: String,
    val plan: TaskPlan
)

data class PersistedTaskRecord(val task: Task, val events: List<TaskEvent>)

fun interface TaskClock { fun now(): Long }
fun interface TaskIdGenerator { fun nextId(): String }

data class TaskLimits(
    val maxTasksPerWorkspace: Int = 100,
    val maxStepsPerTask: Int = 50,
    val maxTitleLength: Int = 240,
    val maxEventMessageLength: Int = 2_000
) {
    init { require(maxTasksPerWorkspace > 0 && maxStepsPerTask > 0 && maxTitleLength > 0 && maxEventMessageLength > 0) }
}

sealed class TaskEngineException(message: String) : IllegalStateException(message) {
    class NotFound(id: String) : TaskEngineException("Task not found: $id")
    class StepNotFound(id: String) : TaskEngineException("Task step not found: $id")
    class InvalidTransition(from: TaskStatus, action: String) : TaskEngineException("Cannot $action while task/step is $from")
    class LimitReached(message: String) : TaskEngineException(message)
    class RetryLimitReached(id: String) : TaskEngineException("Retry limit reached for step: $id")
    class RevisionConflict(expected: Long, actual: Long) : TaskEngineException("Task revision conflict: expected $expected, actual $actual")
}
