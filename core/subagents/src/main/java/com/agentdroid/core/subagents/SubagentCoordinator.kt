package com.agentdroid.core.subagents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

enum class RecoveryAction { RETRY, FALLBACK, STOP }

data class RecoveryDecision(
    val action: RecoveryAction,
    val fallbackRole: SubagentRole? = null
)

fun interface SubagentRecoveryPolicy {
    fun decide(task: SubagentTask, failure: SubagentFailure, attempt: Int, limits: SubagentLimits): RecoveryDecision
}

object RetryThenStopPolicy : SubagentRecoveryPolicy {
    override fun decide(
        task: SubagentTask,
        failure: SubagentFailure,
        attempt: Int,
        limits: SubagentLimits
    ): RecoveryDecision = if (failure.recoverable && attempt <= limits.maxRetries) {
        RecoveryDecision(RecoveryAction.RETRY)
    } else {
        RecoveryDecision(RecoveryAction.STOP)
    }
}

class RetryThenFallbackPolicy(
    private val fallbackRoles: Map<SubagentRole, SubagentRole>,
    private val retriesBeforeFallback: Int = 1
) : SubagentRecoveryPolicy {
    init { require(retriesBeforeFallback >= 0) }

    override fun decide(
        task: SubagentTask,
        failure: SubagentFailure,
        attempt: Int,
        limits: SubagentLimits
    ): RecoveryDecision = when {
        failure.recoverable && attempt <= retriesBeforeFallback && attempt <= limits.maxRetries ->
            RecoveryDecision(RecoveryAction.RETRY)
        fallbackRoles[task.role] != null ->
            RecoveryDecision(RecoveryAction.FALLBACK, fallbackRoles.getValue(task.role))
        else -> RecoveryDecision(RecoveryAction.STOP)
    }
}

interface SubagentCoordinator {
    val timeline: StateFlow<List<SubagentTimelineItem>>
    suspend fun delegate(task: SubagentTask): SubagentResult
    suspend fun delegate(
        role: SubagentRole,
        objective: String,
        context: SubagentContext,
        parentTask: SubagentTask? = null
    ): SubagentResult
}

class DefaultSubagentCoordinator(
    private val factory: SubagentFactory,
    private val toolGateway: SubagentToolGateway,
    private val profiles: SubagentProfileProvider = DefaultSubagentProfileProvider(),
    private val limits: SubagentLimits = SubagentLimits(),
    private val recoveryPolicy: SubagentRecoveryPolicy = RetryThenStopPolicy,
    private val contextSubsetter: ContextSubsetter = ContextSubsetter(),
    private val skillBindings: SkillRoleBindingRepository = InMemorySkillRoleBindingRepository(),
    private val allowSkillToolExpansion: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) : SubagentCoordinator {
    private val stateMutex = Mutex()
    private var activeCount = 0
    private val delegatedCountByRoot = mutableMapOf<String, Int>()
    private val timelineItems = linkedMapOf<String, SubagentTimelineItem>()
    private val mutableTimeline = MutableStateFlow<List<SubagentTimelineItem>>(emptyList())
    override val timeline: StateFlow<List<SubagentTimelineItem>> = mutableTimeline.asStateFlow()

    override suspend fun delegate(
        role: SubagentRole,
        objective: String,
        context: SubagentContext,
        parentTask: SubagentTask?
    ): SubagentResult = delegate(
        SubagentTask(
            id = idGenerator(),
            role = role,
            objective = objective,
            context = context,
            rootTaskId = parentTask?.rootTaskId ?: parentTask?.id,
            parentTaskId = parentTask?.id,
            parentSubagentId = parentTask?.parentSubagentId,
            delegationDepth = (parentTask?.delegationDepth ?: -1) + 1
        )
    )

    override suspend fun delegate(task: SubagentTask): SubagentResult {
        validateAndReserve(task)
        val subagentId = idGenerator()
        updateTimeline(
            SubagentTimelineItem(
                subagentId = subagentId,
                parentSubagentId = task.parentSubagentId,
                taskId = task.id,
                role = task.role,
                label = task.objective.take(160),
                status = SubagentStatus.QUEUED,
                startedAt = null,
                finishedAt = null
            )
        )

        var attempt = 0
        var currentTask = task
        var startedAt = clock()
        val deadline = startedAt + limits.maxTaskDurationMs
        val visitedRoles = mutableSetOf(task.role)
        try {
            startedAt = clock()
            transitionTimeline(subagentId) { it.copy(status = SubagentStatus.RUNNING, startedAt = startedAt) }
            while (true) {
                attempt++
                val profile = resolvedProfile(currentTask.role)
                val boundedTask = currentTask.copy(context = contextSubsetter.subset(currentTask.context, profile))
                val toolCalls = AtomicInteger(0)
                val scope = executionScope(subagentId, boundedTask, profile, toolCalls)
                val remainingMs = (deadline - clock()).coerceAtLeast(1)
                val execution = runAttempt(factory.create(profile), boundedTask, scope, remainingMs)
                if (execution.isSuccess) {
                    val payload = execution.getOrThrow()
                    val finishedAt = clock()
                    val result = SubagentResult(
                        subagentId = subagentId,
                        taskId = task.id,
                        role = currentTask.role,
                        status = SubagentStatus.COMPLETED,
                        summary = payload.summary,
                        output = payload.output,
                        artifactReferences = payload.artifactReferences,
                        sourceReferences = payload.sourceReferences,
                        attempts = attempt,
                        startedAt = startedAt,
                        finishedAt = finishedAt
                    )
                    transitionTimeline(subagentId) { it.copy(status = SubagentStatus.COMPLETED, finishedAt = finishedAt) }
                    return result
                }

                val throwable = execution.exceptionOrNull()!!
                val failure = failureFor(throwable, attempt)
                val decision = recoveryPolicy.decide(currentTask, failure, attempt, limits)
                when (decision.action) {
                    RecoveryAction.RETRY -> if (attempt <= limits.maxRetries && clock() < deadline) continue else {
                        return failedResult(subagentId, task, currentTask.role, failure, attempt, startedAt)
                    }
                    RecoveryAction.FALLBACK -> {
                        val role = requireNotNull(decision.fallbackRole) { "Fallback decision requires a role" }
                        if (!visitedRoles.add(role) || clock() >= deadline) {
                            return failedResult(subagentId, task, currentTask.role, failure, attempt, startedAt)
                        }
                        currentTask = currentTask.copy(role = role)
                        continue
                    }
                    RecoveryAction.STOP -> {
                        return failedResult(subagentId, task, currentTask.role, failure, attempt, startedAt)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            val finishedAt = clock()
            transitionTimeline(subagentId) {
                it.copy(
                    status = SubagentStatus.CANCELLED,
                    finishedAt = finishedAt,
                    failureSummary = "Subagent cancelled"
                )
            }
            throw cancelled
        } finally {
            stateMutex.withLock {
                activeCount--
                if (task.delegationDepth == 0) delegatedCountByRoot.remove(rootId(task))
            }
        }
    }

    private suspend fun validateAndReserve(task: SubagentTask) = stateMutex.withLock {
        if (task.delegationDepth > limits.maxDelegationDepth) {
            throw DelegationDepthExceeded("Delegation depth ${task.delegationDepth} exceeds ${limits.maxDelegationDepth}")
        }
        val rootId = rootId(task)
        val delegatedCount = delegatedCountByRoot[rootId] ?: 0
        if (delegatedCount >= limits.maxSubagents) {
            throw SubagentLimitReached(message = "Subagent limit ${limits.maxSubagents} reached")
        }
        if (activeCount >= limits.maxConcurrentSubagents) {
            throw SubagentLimitReached(
                SubagentFailureCode.CONCURRENT_LIMIT_REACHED,
                "Concurrent subagent limit ${limits.maxConcurrentSubagents} reached"
            )
        }
        delegatedCountByRoot[rootId] = delegatedCount + 1
        activeCount++
    }

    private suspend fun resolvedProfile(role: SubagentRole): SubagentProfile {
        val base = profiles.profile(role)
        val bindings = skillBindings.bindingsFor(role)
        val instructions = buildString {
            append(base.instructions)
            bindings.mapNotNull { it.additionalInstructions }.filter { it.isNotBlank() }.forEach {
                append("\n\nSkill guidance: ").append(it)
            }
        }
        val tools = if (allowSkillToolExpansion) {
            base.allowedTools + bindings.flatMap { it.additionalAllowedTools }
        } else base.allowedTools
        return base.copy(
            instructions = instructions,
            allowedTools = tools,
            tokenLimit = minOf(base.tokenLimit, limits.defaultTokenLimit),
            toolCallLimit = minOf(base.toolCallLimit, limits.defaultToolCallLimit)
        )
    }

    private fun executionScope(
        subagentId: String,
        boundedTask: SubagentTask,
        profile: SubagentProfile,
        toolCalls: AtomicInteger
    ) = object : SubagentExecutionScope {
        override val profile = profile
        override val task = boundedTask

        override suspend fun executeTool(name: String, input: JsonObject): SubagentToolResult {
            if (name !in profile.allowedTools) throw SubagentToolDenied("Tool '$name' is not allowed for ${profile.role}")
            val count = toolCalls.incrementAndGet()
            if (count > profile.toolCallLimit || count > limits.defaultToolCallLimit) {
                throw SubagentToolLimitReached("Tool-call limit reached for ${profile.role}")
            }
            return toolGateway.execute(name, input, boundedTask)
        }

        override suspend fun delegate(role: SubagentRole, objective: String, context: SubagentContext): SubagentResult {
            val child = SubagentTask(
                id = idGenerator(),
                role = role,
                objective = objective,
                context = context,
                rootTaskId = boundedTask.rootTaskId ?: boundedTask.id,
                parentTaskId = boundedTask.id,
                parentSubagentId = subagentId,
                delegationDepth = boundedTask.delegationDepth + 1
            )
            return this@DefaultSubagentCoordinator.delegate(child)
        }
    }

    private suspend fun runAttempt(
        subagent: Subagent,
        task: SubagentTask,
        scope: SubagentExecutionScope,
        timeoutMs: Long
    ): Result<SubagentResultPayload> = try {
        Result.success(withTimeout(timeoutMs) { subagent.execute(task, scope) })
    } catch (timeout: TimeoutCancellationException) {
        Result.failure(SubagentAttemptException(SubagentFailureCode.TIMED_OUT, "Subagent timed out", true, timeout))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (denied: SubagentToolDenied) {
        Result.failure(SubagentAttemptException(SubagentFailureCode.TOOL_NOT_ALLOWED, denied.message ?: "Tool not allowed", false, denied))
    } catch (limit: SubagentToolLimitReached) {
        Result.failure(SubagentAttemptException(SubagentFailureCode.TOOL_LIMIT_REACHED, limit.message ?: "Tool-call limit reached", false, limit))
    } catch (limit: SubagentLimitReached) {
        Result.failure(SubagentAttemptException(limit.failureCode, limit.message ?: "Subagent limit reached", false, limit))
    } catch (depth: DelegationDepthExceeded) {
        Result.failure(SubagentAttemptException(SubagentFailureCode.DELEGATION_DEPTH_EXCEEDED, depth.message ?: "Delegation depth exceeded", false, depth))
    } catch (failure: Throwable) {
        Result.failure(SubagentAttemptException(SubagentFailureCode.EXECUTION_FAILED, failure.message ?: "Subagent execution failed", true, failure))
    }

    private fun failureFor(throwable: Throwable, attempt: Int): SubagentFailure {
        val attemptFailure = throwable as? SubagentAttemptException
        return SubagentFailure(
            code = attemptFailure?.code ?: SubagentFailureCode.EXECUTION_FAILED,
            summary = (attemptFailure?.message ?: throwable.message ?: "Subagent execution failed").take(500),
            recoverable = attemptFailure?.recoverable ?: true,
            attempt = attempt
        )
    }

    private suspend fun failedResult(
        subagentId: String,
        originalTask: SubagentTask,
        finalRole: SubagentRole,
        failure: SubagentFailure,
        attempt: Int,
        startedAt: Long
    ): SubagentResult {
        val finishedAt = clock()
        val status = if (failure.code == SubagentFailureCode.CANCELLED) SubagentStatus.CANCELLED else SubagentStatus.FAILED
        val result = SubagentResult(
            subagentId = subagentId,
            taskId = originalTask.id,
            role = finalRole,
            status = status,
            summary = failure.summary,
            failure = failure,
            attempts = attempt,
            startedAt = startedAt,
            finishedAt = finishedAt
        )
        transitionTimeline(subagentId) {
            it.copy(
                role = finalRole,
                status = status,
                finishedAt = finishedAt,
                failureSummary = failure.summary.take(240)
            )
        }
        return result
    }

    private suspend fun updateTimeline(item: SubagentTimelineItem) = stateMutex.withLock {
        timelineItems[item.subagentId] = item
        mutableTimeline.value = timelineItems.values.toList()
    }

    private suspend fun transitionTimeline(
        subagentId: String,
        transform: (SubagentTimelineItem) -> SubagentTimelineItem
    ) = stateMutex.withLock {
        timelineItems[subagentId] = transform(timelineItems.getValue(subagentId))
        mutableTimeline.value = timelineItems.values.toList()
    }

    private fun rootId(task: SubagentTask): String = task.rootTaskId ?: task.id
}

private class SubagentAttemptException(
    val code: SubagentFailureCode,
    message: String,
    val recoverable: Boolean,
    cause: Throwable
) : RuntimeException(message, cause)
