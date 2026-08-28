package com.agentdroid.core.tasks

import com.agentdroid.core.agent.AgentModelClient
import com.agentdroid.core.agent.AgentModelEvent
import com.agentdroid.core.agent.AgentModelRequest
import com.agentdroid.core.agent.AgentModelResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDrivenPlannerTest {
    @Test fun `calculator and Android research produce materially different plans`(): Unit = runBlocking {
        val planner = planner(GoalAwareClient())

        val calculator = planner.create(
            PlannerInput(
                goal = "Create a calculator website",
                skills = listOf("frontend"),
                currentCapabilities = listOf("workspace.write", "browser.verify")
            ),
            now = 1L
        )
        val research = planner.create(
            PlannerInput(
                goal = "Research Android foreground services",
                projectMemory = listOf("Prefer primary Android documentation"),
                currentCapabilities = listOf("research.search", "research.fetch")
            ),
            now = 2L
        )

        assertEquals(PlannerSource.MODEL, calculator.source)
        assertEquals(PlannerSource.MODEL, research.source)
        assertNotEquals(
            calculator.structuredPlan.steps.map { it.title },
            research.structuredPlan.steps.map { it.title }
        )
        assertTrue(calculator.structuredPlan.steps.any { "calculator" in it.goal.lowercase() })
        assertTrue(research.structuredPlan.steps.any { "source" in it.title.lowercase() })
    }

    @Test fun `cyclic plan is repaired before use`(): Unit = runBlocking {
        val client = QueueClient(mutableListOf(
            Result.success(AgentModelResponse(CYCLIC)),
            Result.success(AgentModelResponse(VALID_BUILD))
        ))

        val result = planner(client).create(
            PlannerInput("Implement a feature", currentCapabilities = listOf("workspace.write")),
            now = 3L
        )

        assertEquals(PlannerSource.REPAIRED_MODEL, result.source)
        assertEquals(2, client.requests.size)
        assertEquals(listOf("inspect", "implement"), result.structuredPlan.steps.map { it.id })
    }

    @Test fun `malformed JSON is repaired before use`(): Unit = runBlocking {
        val malformed = """{"summary":"broken","steps":[{"id":"one","title":"Broken""" 
        val client = QueueClient(mutableListOf(
            Result.success(AgentModelResponse(malformed)),
            Result.success(AgentModelResponse(VALID_BUILD))
        ))

        val result = planner(client).create(
            PlannerInput("Implement a feature", currentCapabilities = listOf("workspace.write")),
            now = 31L
        )

        assertEquals(PlannerSource.REPAIRED_MODEL, result.source)
        assertEquals(2, client.requests.size)
        assertEquals(listOf("inspect", "implement"), result.structuredPlan.steps.map { it.id })
    }

    @Test fun `unknown capability is rejected and repaired`(): Unit = runBlocking {
        val invalid = """{"summary":"x","steps":[{"id":"one","title":"Do it","goal":"Do it","dependencies":[],"expectedCapabilities":["runtime.root"],"acceptanceCriteria":["done"]}]}"""
        val client = QueueClient(mutableListOf(
            Result.success(AgentModelResponse(invalid)),
            Result.success(AgentModelResponse(VALID_BUILD))
        ))

        val result = planner(client).create(
            PlannerInput("Implement a feature", currentCapabilities = listOf("workspace.write")),
            now = 4L
        )

        assertEquals(PlannerSource.REPAIRED_MODEL, result.source)
        assertTrue(result.structuredPlan.steps.flatMap { it.expectedCapabilities }.all { it == "workspace.write" })
    }

    @Test fun `model failure uses deterministic goal-aware fallback`(): Unit = runBlocking {
        val failing = QueueClient(mutableListOf(
            Result.failure(IllegalStateException("provider offline")),
            Result.failure(IllegalStateException("provider offline")),
            Result.failure(IllegalStateException("provider offline")),
            Result.failure(IllegalStateException("provider offline"))
        ))
        val planner = planner(failing)

        val build = planner.create(PlannerInput("Create a calculator website"), 5L)
        val research = planner.create(PlannerInput("Research Android foreground services"), 6L)

        assertEquals(PlannerSource.DETERMINISTIC_FALLBACK, build.source)
        assertEquals(PlannerSource.DETERMINISTIC_FALLBACK, research.source)
        assertNotEquals(build.structuredPlan.steps.map { it.title }, research.structuredPlan.steps.map { it.title })
        assertTrue(build.modelFailure?.contains("offline") == true)
    }

    @Test fun `oversized model plan cannot escape configured limit`(): Unit = runBlocking {
        val oversized = """{"summary":"Too much","steps":[{"id":"s1","title":"one","goal":"one","dependencies":[],"expectedCapabilities":[],"acceptanceCriteria":["done"]},{"id":"s2","title":"two","goal":"two","dependencies":[],"expectedCapabilities":[],"acceptanceCriteria":["done"]},{"id":"s3","title":"three","goal":"three","dependencies":[],"expectedCapabilities":[],"acceptanceCriteria":["done"]}]}"""
        val client = QueueClient(mutableListOf(
            Result.success(AgentModelResponse(oversized)),
            Result.success(AgentModelResponse(oversized))
        ))
        val planner = ModelDrivenPlanner(client, "planner", ids(), ModelPlannerConfig(maxSteps = 2))

        val result = planner.create(PlannerInput("Do a general task"), 7L)

        assertEquals(PlannerSource.DETERMINISTIC_FALLBACK, result.source)
        assertTrue(result.structuredPlan.steps.size <= 2)
    }

    private fun planner(client: AgentModelClient) = ModelDrivenPlanner(client, "planner", ids())

    private fun ids(): TaskIdGenerator {
        var next = 0
        return TaskIdGenerator { "task-step-${next++}" }
    }

    private class QueueClient(
        private val responses: MutableList<Result<AgentModelResponse>>
    ) : AgentModelClient {
        val requests = mutableListOf<AgentModelRequest>()
        override val supportsToolCalling = true
        override suspend fun complete(
            request: AgentModelRequest,
            onEvent: suspend (AgentModelEvent) -> Unit
        ): Result<AgentModelResponse> {
            requests += request
            return responses.removeAt(0)
        }
    }

    private class GoalAwareClient : AgentModelClient {
        override val supportsToolCalling = true
        override suspend fun complete(
            request: AgentModelRequest,
            onEvent: suspend (AgentModelEvent) -> Unit
        ): Result<AgentModelResponse> {
            val prompt = request.messages.joinToString("\n") { it.content }
            return Result.success(AgentModelResponse(if (prompt.contains("calculator", true)) CALCULATOR else RESEARCH))
        }
    }

    companion object {
        private const val VALID_BUILD = """{"summary":"Implement safely","steps":[{"id":"inspect","title":"Inspect workspace","goal":"Inspect the existing state","dependencies":[],"expectedCapabilities":[],"acceptanceCriteria":["Existing state is known"]},{"id":"implement","title":"Implement change","goal":"Implement the requested change","dependencies":["inspect"],"expectedCapabilities":["workspace.write"],"acceptanceCriteria":["Requested change exists"]}]}"""
        private const val CYCLIC = """{"summary":"cycle","steps":[{"id":"one","title":"One","goal":"One","dependencies":["two"],"expectedCapabilities":[],"acceptanceCriteria":["done"]},{"id":"two","title":"Two","goal":"Two","dependencies":["one"],"expectedCapabilities":[],"acceptanceCriteria":["done"]}]}"""
        private const val CALCULATOR = """{"summary":"Build calculator","steps":[{"id":"design","title":"Design calculator UI","goal":"Define calculator interactions","dependencies":[],"expectedCapabilities":[],"acceptanceCriteria":["Operations are defined"]},{"id":"build","title":"Build calculator","goal":"Create the calculator website","dependencies":["design"],"expectedCapabilities":["workspace.write"],"acceptanceCriteria":["Calculator files exist"]}]}"""
        private const val RESEARCH = """{"summary":"Research foreground services","steps":[{"id":"scope","title":"Scope Android question","goal":"Define foreground service questions","dependencies":[],"expectedCapabilities":[],"acceptanceCriteria":["Questions are explicit"]},{"id":"sources","title":"Collect Android sources","goal":"Gather relevant Android documentation","dependencies":["scope"],"expectedCapabilities":["research.search","research.fetch"],"acceptanceCriteria":["Primary sources are collected"]}]}"""
    }
}
