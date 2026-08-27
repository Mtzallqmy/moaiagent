package com.agentdroid.core.tasks

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.AgentTool
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCategory
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolDefinition
import com.agentdroid.core.agent.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

fun createTaskTools(engine: TaskEngine): List<AgentTool> = listOf(
    CreateTaskTool(engine), UpdateTaskTool(engine), CompleteTaskStepTool(engine), FailTaskStepTool(engine),
    ListTasksTool(engine), GetTaskTool(engine)
)

private abstract class TaskTool(protected val engine: TaskEngine) : AgentTool {
    override fun availableInMode(mode: AgentMode): Boolean = when (mode) {
        AgentMode.CHAT -> false
        AgentMode.PLAN -> definition.riskLevel == RiskLevel.SAFE
        AgentMode.AGENT -> true
    }

    protected suspend fun safely(block: suspend () -> ToolResult): ToolResult = try {
        block()
    } catch (failure: TaskEngineException.NotFound) {
        ToolResult.failure(AgentError.validation(failure.message.orEmpty()))
    } catch (failure: TaskEngineException.StepNotFound) {
        ToolResult.failure(AgentError.validation(failure.message.orEmpty()))
    } catch (failure: TaskEngineException.InvalidTransition) {
        ToolResult.failure(AgentError.validation(failure.message.orEmpty()))
    } catch (failure: TaskEngineException.RevisionConflict) {
        ToolResult.failure(AgentError.validation(failure.message.orEmpty()))
    } catch (failure: TaskEngineException.LimitReached) {
        ToolResult.failure(AgentError.validation(failure.message.orEmpty()))
    } catch (failure: TaskEngineException.RetryLimitReached) {
        ToolResult.failure(AgentError.validation(failure.message.orEmpty()))
    } catch (failure: IllegalArgumentException) {
        ToolResult.failure(AgentError.validation(failure.message.orEmpty()))
    }
}

private class CreateTaskTool(engine: TaskEngine) : TaskTool(engine) {
    override val definition = ToolDefinition(
        "create_task", "Create a persisted multi-step task with a short user-visible plan.",
        schema(listOf("title", "summary", "steps"), mapOf("title" to "string", "summary" to "string", "steps" to "array")),
        RiskLevel.MODIFY, ToolCategory.WORKSPACE
    )
    override suspend fun execute(input: JsonObject, context: ToolContext) = safely {
        val steps = input.stringArray("steps")
        require(steps.isNotEmpty()) { "steps must not be empty" }
        val task = engine.create(input.string("title")!!, context.workspaceId, context.conversationId, input.string("summary")!!, steps)
        ToolResult.success("Task created", taskJson(task))
    }
}

/** Uses semantic actions so the model cannot write an arbitrary or contradictory status. */
private class UpdateTaskTool(engine: TaskEngine) : TaskTool(engine) {
    override val definition = ToolDefinition(
        "update_task", "Advance or revise a task using a validated action: start, wait_permission, wait_user, pause, resume, cancel, retry, or revise_plan.",
        schema(listOf("taskId", "action"), mapOf("taskId" to "string", "action" to "string", "stepId" to "string", "message" to "string", "summary" to "string", "steps" to "array", "expectedRevision" to "integer")),
        RiskLevel.MODIFY, ToolCategory.WORKSPACE
    )
    override suspend fun execute(input: JsonObject, context: ToolContext) = safely {
        val id = input.string("taskId")!!
        val step = input.string("stepId")
        val revision = input.long("expectedRevision")
        val updated = when (input.string("action")!!.lowercase()) {
            "start" -> engine.start(id, context.workspaceId, step, revision)
            "wait_permission" -> engine.waitForPermission(id, context.workspaceId, requireStep(step), revision)
            "wait_user" -> engine.waitForUser(id, context.workspaceId, requireStep(step), input.string("message"), revision)
            "pause" -> engine.pause(id, context.workspaceId, revision)
            "resume" -> engine.resume(id, context.workspaceId, revision)
            "cancel" -> engine.cancel(id, context.workspaceId, input.string("message"), revision)
            "retry" -> engine.retry(id, context.workspaceId, requireStep(step), revision)
            "revise_plan" -> engine.revisePlan(id, context.workspaceId, input.string("summary") ?: throw IllegalArgumentException("summary is required"), input.stringArray("steps").also { require(it.isNotEmpty()) { "steps must not be empty" } }, revision)
            else -> throw IllegalArgumentException("Unsupported task action")
        }
        ToolResult.success("Task ${updated.status.name.lowercase()}", taskJson(updated))
    }
}

private class CompleteTaskStepTool(engine: TaskEngine) : TaskTool(engine) {
    override val definition = ToolDefinition(
        "complete_task_step", "Complete the currently running task step; task progress is derived automatically.",
        schema(listOf("taskId", "stepId"), mapOf("taskId" to "string", "stepId" to "string", "expectedRevision" to "integer")),
        RiskLevel.MODIFY, ToolCategory.WORKSPACE
    )
    override suspend fun execute(input: JsonObject, context: ToolContext) = safely {
        val task = engine.completeStep(input.string("taskId")!!, context.workspaceId, input.string("stepId")!!, input.long("expectedRevision"))
        ToolResult.success("Task step completed", taskJson(task))
    }
}

private class FailTaskStepTool(engine: TaskEngine) : TaskTool(engine) {
    override val definition = ToolDefinition(
        "fail_task_step", "Fail the currently running task step with a bounded failure summary.",
        schema(listOf("taskId", "stepId", "error"), mapOf("taskId" to "string", "stepId" to "string", "error" to "string", "expectedRevision" to "integer")),
        RiskLevel.MODIFY, ToolCategory.WORKSPACE
    )
    override suspend fun execute(input: JsonObject, context: ToolContext) = safely {
        val task = engine.failStep(input.string("taskId")!!, context.workspaceId, input.string("stepId")!!, input.string("error")!!.take(2_000), input.long("expectedRevision"))
        ToolResult.success("Task step failed", taskJson(task))
    }
}

private class ListTasksTool(engine: TaskEngine) : TaskTool(engine) {
    override val definition = ToolDefinition(
        "list_tasks", "List task state derived from the task repository for this workspace.",
        schema(fields = mapOf("conversationId" to "string")), RiskLevel.SAFE, ToolCategory.WORKSPACE
    )
    override suspend fun execute(input: JsonObject, context: ToolContext) = safely {
        val tasks = engine.list(context.workspaceId, input.string("conversationId"))
        ToolResult.success("${tasks.size} tasks", buildJsonObject { put("tasks", buildJsonArray { tasks.forEach { add(taskJson(it)) } }) })
    }
}

private class GetTaskTool(engine: TaskEngine) : TaskTool(engine) {
    override val definition = ToolDefinition(
        "get_task", "Read one task, its real steps, progress, artifacts, and event timeline.",
        schema(listOf("taskId"), mapOf("taskId" to "string")), RiskLevel.SAFE, ToolCategory.WORKSPACE
    )
    override suspend fun execute(input: JsonObject, context: ToolContext) = safely {
        val id = input.string("taskId")!!
        val task = engine.get(id, context.workspaceId) ?: throw TaskEngineException.NotFound(id)
        val events = engine.events(id, context.workspaceId)
        ToolResult.success("Task ${task.status.name.lowercase()}", buildJsonObject {
            put("task", taskJson(task))
            put("events", buildJsonArray { events.forEach { e -> add(buildJsonObject {
                put("id", e.id); put("type", e.type.name); put("timestamp", e.timestamp); e.stepId?.let { put("stepId", it) }; e.message?.let { put("message", it) }; put("taskRevision", e.taskRevision)
            }) } })
        })
    }
}

private fun taskJson(task: Task) = buildJsonObject {
    put("id", task.id); put("title", task.title); put("workspaceId", task.workspaceId); put("conversationId", task.conversationId)
    put("status", task.status.name); put("waitReason", task.waitReason.name); put("progress", task.progress); task.currentStepId?.let { put("currentStepId", it) }
    put("createdAt", task.createdAt); task.startedAt?.let { put("startedAt", it) }; put("updatedAt", task.updatedAt); task.finishedAt?.let { put("finishedAt", it) }
    task.failure?.let { put("failure", it) }; put("recoveryRequired", task.recoveryRequired); put("revision", task.revision)
    put("plan", buildJsonObject {
        put("summary", task.plan.summary); put("revision", task.plan.revision)
        put("steps", buildJsonArray { task.plan.steps.forEach { step -> add(buildJsonObject {
            put("id", step.id); put("title", step.title); put("position", step.position); put("status", step.status.name); put("retryCount", step.retryCount); put("maxRetries", step.maxRetries)
            step.startedAt?.let { put("startedAt", it) }; step.finishedAt?.let { put("finishedAt", it) }; step.error?.let { put("error", it) }
        }) } })
    })
    put("artifacts", buildJsonArray { task.artifacts.forEach { artifact -> add(buildJsonObject {
        put("artifactId", artifact.artifactId); put("title", artifact.title); put("type", artifact.type); artifact.uri?.let { put("uri", it) }
    }) } })
}

private fun schema(required: List<String> = emptyList(), fields: Map<String, String> = emptyMap()) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { fields.forEach { (key, type) -> put(key, buildJsonObject { put("type", type) }) } })
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
}
private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
private fun JsonObject.stringArray(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun requireStep(value: String?): String = value ?: throw IllegalArgumentException("stepId is required for this action")
