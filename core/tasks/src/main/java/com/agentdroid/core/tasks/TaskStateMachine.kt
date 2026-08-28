package com.agentdroid.core.tasks

object TaskStateMachine {
    fun start(stepId: String? = null) = mutation("start") { task, now ->
        requireTask(task, setOf(TaskStatus.PENDING, TaskStatus.RUNNING), "start")
        val expectedNext = nextPending(task)?.id ?: throw TaskEngineException.InvalidTransition(task.status, "start without a pending step")
        val target = stepId ?: expectedNext
        if (target != expectedNext) throw TaskEngineException.InvalidTransition(TaskStatus.PENDING, "start a step out of order")
        val targetStep = task.plan.steps.firstOrNull { it.id == target } ?: throw TaskEngineException.StepNotFound(target)
        if (targetStep.status != TaskStatus.PENDING) throw TaskEngineException.InvalidTransition(targetStep.status, "start step")
        if (task.plan.steps.any { it.status == TaskStatus.RUNNING }) throw TaskEngineException.InvalidTransition(TaskStatus.RUNNING, "start a second step")
        val steps = task.plan.steps.map { step ->
            if (step.id == target) step.copy(status = TaskStatus.RUNNING, startedAt = step.startedAt ?: now, error = null) else step
        }
        update(task, now, steps, TaskStatus.RUNNING, TaskWaitReason.NONE, target) to Event(TaskEventType.STEP_STARTED, target)
    }

    fun completeStep(stepId: String) = mutation("complete step") { task, now ->
        requireTask(task, setOf(TaskStatus.RUNNING), "complete step")
        val steps = changeStep(task, stepId, TaskStatus.RUNNING) { it.copy(status = TaskStatus.COMPLETED, finishedAt = now, error = null) }
        val done = steps.all { it.status == TaskStatus.COMPLETED }
        val updated = update(task, now, steps, if (done) TaskStatus.COMPLETED else TaskStatus.RUNNING, TaskWaitReason.NONE, null)
            .copy(finishedAt = if (done) now else null)
        updated to Event(if (done) TaskEventType.COMPLETED else TaskEventType.STEP_COMPLETED, stepId)
    }

    fun failStep(stepId: String, error: String) = mutation("fail step") { task, now ->
        require(error.isNotBlank())
        val boundedError = error.take(2_000)
        requireTask(task, setOf(TaskStatus.RUNNING), "fail step")
        val steps = changeStep(task, stepId, TaskStatus.RUNNING) { it.copy(status = TaskStatus.FAILED, finishedAt = now, error = boundedError) }
        update(task, now, steps, TaskStatus.FAILED, TaskWaitReason.NONE, stepId).copy(finishedAt = now, failure = boundedError) to
            Event(TaskEventType.STEP_FAILED, stepId, boundedError)
    }

    fun waitForPermission(stepId: String) = waitMutation(stepId, TaskStatus.WAITING_PERMISSION, TaskWaitReason.PERMISSION, TaskEventType.WAITING_PERMISSION)
    fun waitForUser(stepId: String, message: String? = null) = waitMutation(stepId, TaskStatus.WAITING_USER, TaskWaitReason.USER_INPUT, TaskEventType.WAITING_USER, message)

    fun pause() = mutation("pause") { task, now ->
        requireTask(task, setOf(TaskStatus.RUNNING), "pause")
        update(task, now, task.plan.steps, TaskStatus.WAITING_USER, TaskWaitReason.PAUSED, task.currentStepId) to Event(TaskEventType.PAUSED, task.currentStepId)
    }

    fun resume() = mutation("resume") { task, now ->
        requireTask(task, setOf(TaskStatus.WAITING_PERMISSION, TaskStatus.WAITING_USER), "resume")
        val step = task.currentStepId?.let { id -> task.plan.steps.firstOrNull { it.id == id } }
        require((step == null && task.plan.steps.none { it.status == TaskStatus.RUNNING }) || step?.status == TaskStatus.RUNNING) {
            "Waiting task has inconsistent running-step state"
        }
        update(task, now, task.plan.steps, TaskStatus.RUNNING, TaskWaitReason.NONE, task.currentStepId).copy(recoveryRequired = false) to Event(TaskEventType.RESUMED, task.currentStepId)
    }

    fun cancel(message: String? = null) = mutation("cancel") { task, now ->
        requireTask(task, TaskStatus.values().filterNot { it.isTerminal }.toSet(), "cancel")
        val steps = task.plan.steps.map { if (!it.status.isTerminal) it.copy(status = TaskStatus.CANCELLED, finishedAt = now) else it }
        update(task, now, steps, TaskStatus.CANCELLED, TaskWaitReason.NONE, null).copy(startedAt = task.startedAt, finishedAt = now) to Event(TaskEventType.CANCELLED, message = message)
    }

    fun retry(stepId: String) = mutation("retry") { task, now ->
        requireTask(task, setOf(TaskStatus.FAILED), "retry")
        val failed = task.plan.steps.firstOrNull { it.id == stepId } ?: throw TaskEngineException.StepNotFound(stepId)
        if (failed.status != TaskStatus.FAILED) throw TaskEngineException.InvalidTransition(failed.status, "retry")
        if (failed.retryCount >= failed.maxRetries) throw TaskEngineException.RetryLimitReached(stepId)
        val steps = task.plan.steps.map {
            if (it.id == stepId) it.copy(status = TaskStatus.RUNNING, retryCount = it.retryCount + 1, startedAt = now, finishedAt = null, error = null) else it
        }
        update(task, now, steps, TaskStatus.RUNNING, TaskWaitReason.NONE, stepId).copy(finishedAt = null, failure = null) to Event(TaskEventType.RETRIED, stepId)
    }

    fun revisePlan(plan: TaskPlan) = mutation("revise plan") { task, now ->
        requireTask(task, setOf(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.WAITING_USER), "revise plan")
        require(plan.steps.none { it.status == TaskStatus.RUNNING })
        require(task.plan.steps.none { it.status == TaskStatus.RUNNING }) { "A running step must finish before revising the plan" }
        task.copy(plan = plan, progress = progress(plan.steps), currentStepId = null, updatedAt = now, revision = task.revision + 1) to Event(TaskEventType.PLAN_UPDATED)
    }

    fun attachArtifact(artifact: ArtifactRef) = mutation("attach artifact") { task, now ->
        require(task.artifacts.none { it.artifactId == artifact.artifactId })
        task.copy(artifacts = task.artifacts + artifact, updatedAt = now, revision = task.revision + 1) to Event(TaskEventType.ARTIFACT_ATTACHED, message = artifact.title)
    }

    private fun waitMutation(stepId: String, status: TaskStatus, reason: TaskWaitReason, event: TaskEventType, message: String? = null) = mutation("wait") { task, now ->
        requireTask(task, setOf(TaskStatus.RUNNING), "wait")
        val step = task.plan.steps.firstOrNull { it.id == stepId } ?: throw TaskEngineException.StepNotFound(stepId)
        if (step.status != TaskStatus.RUNNING) throw TaskEngineException.InvalidTransition(step.status, "wait")
        update(task, now, task.plan.steps, status, reason, stepId) to Event(event, stepId, message)
    }

    private data class Event(val type: TaskEventType, val stepId: String? = null, val message: String? = null)
    private fun mutation(action: String, body: (Task, Long) -> Pair<Task, Event>) = TaskMutation { task, now, eventId ->
        val (updated, e) = body(task, now)
        TaskMutationResult(updated, TaskEvent(eventId, task.id, e.type, now, e.stepId, e.message, updated.revision))
    }

    private fun update(task: Task, now: Long, steps: List<TaskStep>, status: TaskStatus, reason: TaskWaitReason, current: String?) = task.copy(
        plan = task.plan.copy(steps = steps, updatedAt = now), status = status, waitReason = reason,
        progress = progress(steps), currentStepId = current, startedAt = task.startedAt ?: now,
        updatedAt = now, revision = task.revision + 1
    )

    private fun progress(steps: List<TaskStep>): Int = (steps.count { it.status == TaskStatus.COMPLETED } * 100) / steps.size
    private fun nextPending(task: Task) = task.plan.steps.firstOrNull { it.status == TaskStatus.PENDING }
    private fun requireTask(task: Task, valid: Set<TaskStatus>, action: String) { if (task.status !in valid) throw TaskEngineException.InvalidTransition(task.status, action) }
    private fun changeStep(task: Task, id: String, expected: TaskStatus, change: (TaskStep) -> TaskStep): List<TaskStep> {
        var found = false
        val steps = task.plan.steps.map { step ->
            if (step.id != id) step else {
                found = true
                if (step.status != expected) throw TaskEngineException.InvalidTransition(step.status, "change step")
                change(step)
            }
        }
        if (!found) throw TaskEngineException.StepNotFound(id)
        return steps
    }
}
