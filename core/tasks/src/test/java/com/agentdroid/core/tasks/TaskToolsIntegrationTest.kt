package com.agentdroid.core.tasks

import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.ToolCall
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskToolsIntegrationTest {
    @Test fun `agent tools execute a real three step workflow`() = runBlocking {
        val fixture = Fixture()
        val create = fixture.call("create_task", buildJsonObject {
            put("title", "Markdown research"); put("summary", "Compare libraries")
            put("steps", buildJsonArray { add(JsonPrimitive("Search")); add(JsonPrimitive("Compare")); add(JsonPrimitive("Save artifact")) })
        })
        assertTrue(create.success)
        val taskId = create.output["id"]!!.jsonPrimitive.content
        val steps = create.output["plan"]!!.jsonObject["steps"] as JsonArray
        steps.forEach { stepJson ->
            val stepId = stepJson.jsonObject["id"]!!.jsonPrimitive.content
            assertTrue(fixture.call("update_task", obj("taskId" to taskId, "action" to "start", "stepId" to stepId)).success)
            assertTrue(fixture.call("complete_task_step", obj("taskId" to taskId, "stepId" to stepId)).success)
        }
        val result = fixture.call("get_task", obj("taskId" to taskId))
        val task = result.output["task"]!!.jsonObject
        assertEquals("COMPLETED", task["status"]!!.jsonPrimitive.content)
        assertEquals("100", task["progress"]!!.jsonPrimitive.content)
    }

    @Test fun `tools reject forged transition and expose permission waiting`() = runBlocking {
        val fixture = Fixture()
        val created = fixture.createOneStep()
        val taskId = created.first
        val stepId = created.second
        val invalid = fixture.call("complete_task_step", obj("taskId" to taskId, "stepId" to stepId))
        assertFalse(invalid.success)
        fixture.call("update_task", obj("taskId" to taskId, "action" to "start", "stepId" to stepId))
        val waiting = fixture.call("update_task", obj("taskId" to taskId, "action" to "wait_permission", "stepId" to stepId))
        assertEquals("WAITING_PERMISSION", waiting.output["status"]!!.jsonPrimitive.content)
    }

    @Test fun `agent tools handle failure retry and cancellation through validated actions`() = runBlocking {
        val fixture = Fixture()
        val (taskId, stepId) = fixture.createOneStep()
        fixture.call("update_task", obj("taskId" to taskId, "action" to "start", "stepId" to stepId))
        val failed = fixture.call("fail_task_step", obj("taskId" to taskId, "stepId" to stepId, "error" to "source unavailable"))
        assertEquals("FAILED", failed.output["status"]!!.jsonPrimitive.content)
        val retried = fixture.call("update_task", obj("taskId" to taskId, "action" to "retry", "stepId" to stepId))
        assertEquals("RUNNING", retried.output["status"]!!.jsonPrimitive.content)
        val cancelled = fixture.call("update_task", obj("taskId" to taskId, "action" to "cancel"))
        assertEquals("CANCELLED", cancelled.output["status"]!!.jsonPrimitive.content)
    }

    private class Fixture {
        private var id = 0
        private val ids = TaskIdGenerator { "id-${id++}" }
        private val engine = TaskEngine(InMemoryTaskRepository(TaskClock { 100 }, ids), ConciseTaskPlanner(ids), TaskClock { 100 })
        private val registry = ToolRegistry(createTaskTools(engine))
        private val context = ToolContext("workspace", "conversation", "session", AgentMode.AGENT)

        suspend fun call(name: String, input: JsonObject) = registry.execute(ToolCall("call-${id++}", name, input), context)
        suspend fun createOneStep(): Pair<String, String> {
            val result = call("create_task", buildJsonObject {
                put("title", "Task"); put("summary", "Plan"); put("steps", buildJsonArray { add(JsonPrimitive("One")) })
            })
            return result.output["id"]!!.jsonPrimitive.content to
                (result.output["plan"]!!.jsonObject["steps"] as JsonArray).first().jsonObject["id"]!!.jsonPrimitive.content
        }
    }
}

private fun obj(vararg values: Pair<String, String>) = buildJsonObject { values.forEach { (key, value) -> put(key, value) } }
