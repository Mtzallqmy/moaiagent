package com.agentdroid.core.tasks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {
    @Test fun `three step task reports actual progress and completes`(): Unit = runBlocking {
        val fixture = Fixture()
        var task = fixture.create()
        assertEquals(TaskStatus.PENDING, task.status)
        assertEquals(0, task.progress)

        task.plan.steps.forEachIndexed { index, step ->
            task = fixture.engine.start(task.id, "workspace", step.id, task.revision)
            task = fixture.engine.completeStep(task.id, "workspace", step.id, task.revision)
            assertEquals(((index + 1) * 100) / 3, task.progress)
        }

        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(100, task.progress)
        assertTrue(task.plan.steps.all { it.status == TaskStatus.COMPLETED })
    }

    @Test fun `cancel is terminal and cancels unfinished steps`(): Unit = runBlocking {
        val fixture = Fixture()
        var task = fixture.create()
        task = fixture.engine.start(task.id, "workspace", task.plan.steps.first().id)
        task = fixture.engine.cancel(task.id, "workspace", "user stopped")
        assertEquals(TaskStatus.CANCELLED, task.status)
        assertTrue(task.plan.steps.all { it.status == TaskStatus.CANCELLED })
        assertFails<TaskEngineException.InvalidTransition> { fixture.engine.resume(task.id, "workspace") }
    }

    @Test fun `permission wait and pause resume do not forge step status`(): Unit = runBlocking {
        val fixture = Fixture()
        var task = fixture.create()
        val step = task.plan.steps.first()
        task = fixture.engine.start(task.id, "workspace", step.id)
        task = fixture.engine.waitForPermission(task.id, "workspace", step.id)
        assertEquals(TaskStatus.WAITING_PERMISSION, task.status)
        assertEquals(TaskStatus.RUNNING, task.plan.steps.first().status)
        task = fixture.engine.resume(task.id, "workspace")
        task = fixture.engine.pause(task.id, "workspace")
        assertEquals(TaskWaitReason.PAUSED, task.waitReason)
        task = fixture.engine.resume(task.id, "workspace")
        assertEquals(TaskStatus.RUNNING, task.status)
    }

    @Test fun `failed step can retry only within its declared limit`(): Unit = runBlocking {
        val fixture = Fixture(maxRetries = 1)
        var task = fixture.create()
        val step = task.plan.steps.first()
        task = fixture.engine.start(task.id, "workspace", step.id)
        task = fixture.engine.failStep(task.id, "workspace", step.id, "network")
        assertEquals(TaskStatus.FAILED, task.status)
        task = fixture.engine.retry(task.id, "workspace", step.id)
        assertEquals(1, task.plan.steps.first().retryCount)
        task = fixture.engine.failStep(task.id, "workspace", step.id, "again")
        assertFails<TaskEngineException.RetryLimitReached> { fixture.engine.retry(task.id, "workspace", step.id) }
    }

    @Test fun `optimistic revision rejects stale tool updates`(): Unit = runBlocking {
        val fixture = Fixture()
        val task = fixture.create()
        fixture.engine.start(task.id, "workspace", expectedRevision = 0)
        assertFails<TaskEngineException.RevisionConflict> { fixture.engine.cancel(task.id, "workspace", expectedRevision = 0) }
    }

    @Test fun `workspace scope prevents cross workspace reads and writes`(): Unit = runBlocking {
        val fixture = Fixture()
        val task = fixture.create()
        assertEquals(null, fixture.engine.get(task.id, "other"))
        assertFails<TaskEngineException.NotFound> { fixture.engine.cancel(task.id, "other") }
    }

    @Test fun `steps cannot be started out of plan order`(): Unit = runBlocking {
        val fixture = Fixture()
        val task = fixture.create()
        assertFails<TaskEngineException.InvalidTransition> {
            fixture.engine.start(task.id, "workspace", task.plan.steps.last().id)
        }
    }

    @Test fun `planner can revise remaining actions between steps without hidden reasoning`(): Unit = runBlocking {
        val fixture = Fixture()
        var task = fixture.create()
        val firstStepId = task.plan.steps.first().id
        task = fixture.engine.start(task.id, "workspace", firstStepId)
        task = fixture.engine.completeStep(task.id, "workspace", firstStepId)
        task = fixture.engine.revisePlan(task.id, "workspace", "Use the verified sources", listOf("Review", "Publish"))
        assertEquals(2, task.plan.revision)
        assertEquals(listOf("Search", "Review", "Publish"), task.plan.steps.map { it.title })
        assertFalse(task.plan.summary.contains("reason", ignoreCase = true))
    }

    private class Fixture(maxRetries: Int = 2) {
        private var sequence = 0
        private val ids = TaskIdGenerator { "id-${sequence++}" }
        private val clock = TaskClock { 1_000L + sequence }
        private val repository = InMemoryTaskRepository(clock, ids)
        private val planner = object : TaskPlanner {
            private val delegate = ConciseTaskPlanner(ids)
            override fun create(summary: String, stepTitles: List<String>, now: Long): TaskPlan {
                val plan = delegate.create(summary, stepTitles, now)
                return plan.copy(steps = plan.steps.map { it.copy(maxRetries = maxRetries) })
            }
            override fun revise(plan: TaskPlan, summary: String, remainingStepTitles: List<String>, now: Long) = delegate.revise(plan, summary, remainingStepTitles, now)
        }
        val engine = TaskEngine(repository, planner, clock)
        suspend fun create() = engine.create("Research", "workspace", "conversation", "Collect and compare", listOf("Search", "Compare", "Write"))
    }

    private suspend inline fun <reified T : Throwable> assertFails(noinline block: suspend () -> Unit) {
        var thrown: Throwable? = null
        try { block() } catch (failure: Throwable) { thrown = failure }
        assertTrue("Expected ${T::class.java.simpleName}, got $thrown", thrown is T)
    }
}
