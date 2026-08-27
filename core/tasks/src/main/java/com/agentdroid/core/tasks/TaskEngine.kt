package com.agentdroid.core.tasks

class TaskEngine(
    private val repository: TaskRepository,
    private val planner: TaskPlanner,
    private val clock: TaskClock = TaskClock(System::currentTimeMillis)
) {
    suspend fun create(title: String, workspaceId: String, conversationId: String, summary: String, steps: List<String>): Task =
        repository.create(NewTask(title, workspaceId, conversationId, planner.create(summary, steps, clock.now())))

    suspend fun get(taskId: String, workspaceId: String): Task? = repository.get(taskId)?.takeIf { it.workspaceId == workspaceId }

    suspend fun list(workspaceId: String, conversationId: String? = null): List<Task> = repository.list(workspaceId, conversationId)

    suspend fun start(taskId: String, workspaceId: String, stepId: String? = null, expectedRevision: Long? = null): Task =
        mutateScoped(taskId, workspaceId, expectedRevision, TaskStateMachine.start(stepId))

    suspend fun completeStep(taskId: String, workspaceId: String, stepId: String, expectedRevision: Long? = null): Task =
        mutateScoped(taskId, workspaceId, expectedRevision, TaskStateMachine.completeStep(stepId))

    suspend fun failStep(taskId: String, workspaceId: String, stepId: String, error: String, expectedRevision: Long? = null): Task =
        mutateScoped(taskId, workspaceId, expectedRevision, TaskStateMachine.failStep(stepId, error))

    suspend fun waitForPermission(taskId: String, workspaceId: String, stepId: String, expectedRevision: Long? = null): Task =
        mutateScoped(taskId, workspaceId, expectedRevision, TaskStateMachine.waitForPermission(stepId))

    suspend fun waitForUser(taskId: String, workspaceId: String, stepId: String, message: String? = null, expectedRevision: Long? = null): Task =
        mutateScoped(taskId, workspaceId, expectedRevision, TaskStateMachine.waitForUser(stepId, message))

    suspend fun pause(taskId: String, workspaceId: String, expectedRevision: Long? = null): Task =
        mutateScoped(taskId, workspaceId, expectedRevision, TaskStateMachine.pause())

    suspend fun resume(taskId: String, workspaceId: String, expectedRevision: Long? = null): Task =
        mutateScoped(taskId, workspaceId, expectedRevision, TaskStateMachine.resume())

    suspend fun cancel(taskId: String, workspaceId: String, reason: String? = null, expectedRevision: Long? = null): Task =
        mutateScoped(taskId, workspaceId, expectedRevision, TaskStateMachine.cancel(reason))

    suspend fun retry(taskId: String, workspaceId: String, stepId: String, expectedRevision: Long? = null): Task =
        mutateScoped(taskId, workspaceId, expectedRevision, TaskStateMachine.retry(stepId))

    suspend fun revisePlan(taskId: String, workspaceId: String, summary: String, remainingSteps: List<String>, expectedRevision: Long? = null): Task {
        val current = requireScoped(taskId, workspaceId)
        val revised = planner.revise(current.plan, summary, remainingSteps, clock.now())
        return repository.mutate(taskId, expectedRevision ?: current.revision, TaskStateMachine.revisePlan(revised))
    }

    suspend fun attachArtifact(taskId: String, workspaceId: String, artifact: ArtifactRef, expectedRevision: Long? = null): Task =
        mutateScoped(taskId, workspaceId, expectedRevision, TaskStateMachine.attachArtifact(artifact))

    suspend fun events(taskId: String, workspaceId: String): List<TaskEvent> {
        requireScoped(taskId, workspaceId)
        return repository.events(taskId)
    }

    suspend fun restore() = repository.restore()

    private suspend fun requireScoped(taskId: String, workspaceId: String): Task =
        repository.get(taskId)?.takeIf { it.workspaceId == workspaceId } ?: throw TaskEngineException.NotFound(taskId)

    private suspend fun mutateScoped(taskId: String, workspaceId: String, expectedRevision: Long?, operation: TaskMutation): Task {
        requireScoped(taskId, workspaceId)
        return repository.mutate(taskId, expectedRevision, operation)
    }
}
