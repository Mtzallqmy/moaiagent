package com.agentdroid.core.tasks

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistence boundary; a Room adapter stores the complete record transactionally. */
interface TaskPersistence {
    suspend fun loadAll(): List<PersistedTaskRecord>
    suspend fun save(record: PersistedTaskRecord)
    suspend fun delete(taskId: String)
}

interface TaskRepository {
    suspend fun create(request: NewTask): Task
    suspend fun get(taskId: String): Task?
    suspend fun list(workspaceId: String? = null, conversationId: String? = null): List<Task>
    suspend fun events(taskId: String): List<TaskEvent>
    suspend fun mutate(taskId: String, expectedRevision: Long? = null, operation: TaskMutation): Task
    suspend fun restore()
}

data class TaskMutationResult(val task: Task, val event: TaskEvent)
fun interface TaskMutation { fun apply(task: Task, now: Long, eventId: String): TaskMutationResult }

class InMemoryTaskRepository(
    private val clock: TaskClock = TaskClock(System::currentTimeMillis),
    private val ids: TaskIdGenerator = TaskIdGenerator { java.util.UUID.randomUUID().toString() },
    private val limits: TaskLimits = TaskLimits(),
    private val persistence: TaskPersistence? = null
) : TaskRepository {
    private val lock = Mutex()
    private val records = linkedMapOf<String, PersistedTaskRecord>()

    override suspend fun create(request: NewTask): Task = lock.withLock {
        require(request.title.isNotBlank() && request.title.length <= limits.maxTitleLength)
        if (request.plan.steps.size > limits.maxStepsPerTask) throw TaskEngineException.LimitReached("Task has too many steps")
        if (records.values.count { it.task.workspaceId == request.workspaceId && !it.task.status.isTerminal } >= limits.maxTasksPerWorkspace) {
            throw TaskEngineException.LimitReached("Workspace has too many active tasks")
        }
        val now = clock.now()
        val task = Task(ids.nextId(), request.title.trim(), request.workspaceId, request.conversationId, request.plan, createdAt = now, updatedAt = now)
        val event = event(task, TaskEventType.CREATED, now)
        val record = PersistedTaskRecord(task, listOf(event))
        records[task.id] = record
        persistence?.save(record)
        task
    }

    override suspend fun get(taskId: String): Task? = lock.withLock { records[taskId]?.task }

    override suspend fun list(workspaceId: String?, conversationId: String?): List<Task> = lock.withLock {
        records.values.asSequence().map { it.task }
            .filter { workspaceId == null || it.workspaceId == workspaceId }
            .filter { conversationId == null || it.conversationId == conversationId }
            .sortedByDescending { it.updatedAt }.toList()
    }

    override suspend fun events(taskId: String): List<TaskEvent> = lock.withLock {
        records[taskId]?.events ?: throw TaskEngineException.NotFound(taskId)
    }

    override suspend fun mutate(taskId: String, expectedRevision: Long?, operation: TaskMutation): Task = lock.withLock {
        val current = records[taskId] ?: throw TaskEngineException.NotFound(taskId)
        if (expectedRevision != null && expectedRevision != current.task.revision) throw TaskEngineException.RevisionConflict(expectedRevision, current.task.revision)
        val result = operation.apply(current.task, clock.now(), ids.nextId())
        require(result.task.id == taskId && result.task.revision == current.task.revision + 1) { "Mutation must preserve id and increment revision exactly once" }
        val record = PersistedTaskRecord(result.task, current.events + result.event)
        persistence?.save(record)
        records[taskId] = record
        result.task
    }

    override suspend fun restore() = lock.withLock {
        val loaded = persistence?.loadAll().orEmpty()
        records.clear()
        loaded.forEach { record ->
            val task = if (record.task.status in setOf(TaskStatus.RUNNING, TaskStatus.WAITING_PERMISSION)) {
                val now = clock.now()
                record.task.copy(
                    status = TaskStatus.WAITING_USER,
                    waitReason = TaskWaitReason.RECOVERY_REQUIRED,
                    recoveryRequired = true,
                    updatedAt = now,
                    revision = record.task.revision + 1
                )
            } else record.task
            val events = if (task !== record.task) record.events + event(task, TaskEventType.RECOVERY_REQUIRED, task.updatedAt, message = "Task execution must be resumed explicitly") else record.events
            val restored = PersistedTaskRecord(task, events)
            records[task.id] = restored
            if (task !== record.task) persistence?.save(restored)
        }
    }

    private fun event(task: Task, type: TaskEventType, now: Long, stepId: String? = null, message: String? = null) =
        TaskEvent(ids.nextId(), task.id, type, now, stepId, message?.take(limits.maxEventMessageLength), task.revision)
}
