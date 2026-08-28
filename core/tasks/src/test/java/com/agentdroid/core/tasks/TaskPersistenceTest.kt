package com.agentdroid.core.tasks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPersistenceTest {
    @Test fun `restore marks interrupted execution as recovery required`(): Unit = runBlocking {
        val store = FakePersistence()
        val ids = sequentialIds()
        val planner = ConciseTaskPlanner(ids)
        val firstRepo = InMemoryTaskRepository(TaskClock { 10 }, ids, persistence = store)
        val first = TaskEngine(firstRepo, planner, TaskClock { 10 })
        var task = first.create("Long task", "w", "c", "Do work", listOf("Run command"))
        task = first.start(task.id, "w")
        assertEquals(TaskStatus.RUNNING, task.status)

        val secondRepo = InMemoryTaskRepository(TaskClock { 20 }, ids, persistence = store)
        val second = TaskEngine(secondRepo, planner, TaskClock { 20 })
        second.restore()
        val restored = second.get(task.id, "w")!!
        assertEquals(TaskStatus.WAITING_USER, restored.status)
        assertEquals(TaskWaitReason.RECOVERY_REQUIRED, restored.waitReason)
        assertTrue(restored.recoveryRequired)
        assertEquals(TaskEventType.RECOVERY_REQUIRED, second.events(task.id, "w").last().type)
    }

    @Test fun `repository returns immutable event history`(): Unit = runBlocking {
        val ids = sequentialIds()
        val repo = InMemoryTaskRepository(TaskClock { 10 }, ids)
        val engine = TaskEngine(repo, ConciseTaskPlanner(ids), TaskClock { 10 })
        var task = engine.create("Task", "w", "c", "Plan", listOf("One"))
        task = engine.start(task.id, "w")
        engine.completeStep(task.id, "w", task.currentStepId!!)
        assertEquals(listOf(TaskEventType.CREATED, TaskEventType.STEP_STARTED, TaskEventType.COMPLETED), engine.events(task.id, "w").map { it.type })
    }

    private class FakePersistence : TaskPersistence {
        private val records = linkedMapOf<String, PersistedTaskRecord>()
        override suspend fun loadAll() = records.values.toList()
        override suspend fun save(record: PersistedTaskRecord) { records[record.task.id] = record }
        override suspend fun delete(taskId: String) { records.remove(taskId) }
    }
}

private fun sequentialIds(): TaskIdGenerator {
    var value = 0
    return TaskIdGenerator { "id-${value++}" }
}
