package com.agentdroid.core.agent

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    private val safeTool = object : AgentTool {
        override val definition = ToolDefinition("read_file", "read", objectSchema(), RiskLevel.SAFE, ToolCategory.FILE_READ)
        override suspend fun execute(input: JsonObject, context: ToolContext) = ToolResult.success("read ok", buildJsonObject { put("content", "hello") })
    }
    private val modifyTool = object : AgentTool {
        override val definition = ToolDefinition("patch_file", "patch", objectSchema(), RiskLevel.MODIFY, ToolCategory.FILE_MODIFY)
        override suspend fun preview(input: JsonObject, context: ToolContext) = ToolPreview("Patch file", "a.txt", "-old\n+new")
        override suspend fun execute(input: JsonObject, context: ToolContext) = ToolResult.success("patch staged", buildJsonObject { put("changeSetId", "cs1") }, "cs1")
    }

    @Test fun registryFiltersModesAndValidatesUnknownTools() = runBlocking {
        val registry = ToolRegistry(listOf(safeTool, modifyTool))
        assertEquals(setOf("read_file", "patch_file"), registry.list().map { it.name }.toSet())
        assertTrue(registry.toolsForMode(AgentMode.CHAT).isEmpty())
        assertEquals(listOf("read_file"), registry.toolsForMode(AgentMode.PLAN).map { it.name })
        assertEquals(2, registry.toolsForMode(AgentMode.AGENT).size)

        val context = ToolContext("w", "c", "s", AgentMode.PLAN)
        val denied = registry.execute(ToolCall("1", "patch_file", buildJsonObject {}), context)
        assertFalse(denied.success)
        assertEquals(AgentErrorCode.MODE_RESTRICTION, denied.error?.code)
        val missing = registry.execute(ToolCall("2", "missing", buildJsonObject {}), context)
        assertEquals(AgentErrorCode.TOOL_NOT_FOUND, missing.error?.code)
    }

    @Test fun readThenPatchPermissionThenFinishInjectsToolResults() = runBlocking {
        val model = ScriptedModel(
            listOf(
                AgentModelResponse("", listOf(ToolCall("read1", "read_file", buildJsonObject {}))),
                AgentModelResponse("", listOf(ToolCall("patch1", "patch_file", buildJsonObject { put("reason", "update test") }))),
                AgentModelResponse("Done")
            )
        )
        val permissions = RecordingPermissionGateway(PermissionOutcome(PermissionDecision.ALLOW, PermissionScope.ONCE))
        val loop = AgentLoop(ToolRegistry(listOf(safeTool, modifyTool)), permissions, emptyContext())
        val events = loop.run(session(), "change it", model).toList()

        assertTrue(events.any { it is AgentEvent.PermissionRequired && it.request.definition.name == "patch_file" })
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.call.name == "read_file" && it.result.success })
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.call.name == "patch_file" && it.result.changeSetId == "cs1" })
        assertTrue(events.any { it is AgentEvent.FinalAnswer && it.text == "Done" })
        assertEquals(3, model.requests.size)
        assertTrue(model.requests[1].messages.any { it.role == AgentMessageRole.TOOL && it.toolCallId == "read1" && it.content.contains("hello") })
        assertTrue(model.requests[2].messages.any { it.role == AgentMessageRole.TOOL && it.toolCallId == "patch1" && it.content.contains("changeSetId") })
        assertTrue(permissions.requests.any { it.definition.name == "patch_file" && it.preview?.diff?.contains("+new") == true })
        assertTrue(permissions.clearedSessions.contains("session"))
    }

    @Test fun permissionDeniedIsReturnedToModelAndAgentCanRecover() = runBlocking {
        val model = ScriptedModel(
            listOf(
                AgentModelResponse("", listOf(ToolCall("patch1", "patch_file", buildJsonObject {}))),
                AgentModelResponse("I could not modify the file.")
            )
        )
        val loop = AgentLoop(
            ToolRegistry(listOf(modifyTool)),
            RecordingPermissionGateway(PermissionOutcome(PermissionDecision.DENY)),
            emptyContext()
        )
        val events = loop.run(session(), "change it", model).toList()
        assertTrue(model.requests[1].messages.any { it.role == AgentMessageRole.TOOL && it.content.contains("PERMISSION_DENIED") })
        assertTrue(events.any { it is AgentEvent.FinalAnswer })
    }

    @Test fun toolFailureIsReturnedAndAlternativeTurnCanFinish() = runBlocking {
        val failing = object : AgentTool {
            override val definition = ToolDefinition("search_files", "search", objectSchema(), RiskLevel.SAFE, ToolCategory.FILE_SEARCH)
            override suspend fun execute(input: JsonObject, context: ToolContext) = ToolResult.failure(AgentError.io("disk failed"))
        }
        val model = ScriptedModel(
            listOf(
                AgentModelResponse("", listOf(ToolCall("s1", "search_files", buildJsonObject {}))),
                AgentModelResponse("Recovered")
            )
        )
        val events = AgentLoop(ToolRegistry(listOf(failing)), RecordingPermissionGateway(), emptyContext())
            .run(session(), "find", model).toList()
        assertTrue(model.requests[1].messages.any { it.role == AgentMessageRole.TOOL && it.content.contains("IO_ERROR") })
        assertTrue(events.any { it is AgentEvent.FinalAnswer && it.text == "Recovered" })
    }

    @Test fun maxTurnsStopsInfiniteToolLoop() = runBlocking {
        val loopingModel = object : AgentModelClient {
            override val supportsToolCalling = true
            var calls = 0
            override suspend fun complete(request: AgentModelRequest, onEvent: suspend (AgentModelEvent) -> Unit): Result<AgentModelResponse> {
                calls++
                return Result.success(AgentModelResponse("", listOf(ToolCall("r$calls", "read_file", buildJsonObject {}))))
            }
        }
        val events = AgentLoop(
            ToolRegistry(listOf(safeTool)), RecordingPermissionGateway(), emptyContext(), config = AgentConfig(maxTurns = 2, maxToolCalls = 10)
        ).run(session(), "loop", loopingModel).toList()
        val failure = events.filterIsInstance<AgentEvent.Failed>().lastOrNull()
        assertNotNull(failure)
        assertEquals(AgentErrorCode.AGENT_TURN_LIMIT_REACHED, failure?.error?.code)
        assertEquals(2, loopingModel.calls)
    }

    @Test fun repeatedFailuresStopBeforeInfiniteRetry() = runBlocking {
        val failing = object : AgentTool {
            override val definition = ToolDefinition("read_file", "read", objectSchema(), RiskLevel.SAFE, ToolCategory.FILE_READ)
            override suspend fun execute(input: JsonObject, context: ToolContext) = ToolResult.failure(AgentError.io("same failure"))
        }
        val loopingModel = object : AgentModelClient {
            override val supportsToolCalling = true
            var calls = 0
            override suspend fun complete(request: AgentModelRequest, onEvent: suspend (AgentModelEvent) -> Unit): Result<AgentModelResponse> {
                calls++
                return Result.success(AgentModelResponse("", listOf(ToolCall("same", "read_file", buildJsonObject {}))))
            }
        }
        val events = AgentLoop(
            ToolRegistry(listOf(failing)), RecordingPermissionGateway(), emptyContext(),
            config = AgentConfig(maxTurns = 10, maxToolCalls = 10, maxConsecutiveFailures = 3, maxRepeatedFailureSignature = 1)
        ).run(session(), "loop", loopingModel).toList()
        assertEquals(AgentErrorCode.CONSECUTIVE_FAILURE_LIMIT_REACHED, events.filterIsInstance<AgentEvent.Failed>().last().error.code)
        assertTrue(loopingModel.calls <= 2)
    }

    @Test fun providerWithoutToolsFailsPlanModeCleanly() = runBlocking {
        val model = object : AgentModelClient {
            override val supportsToolCalling = false
            override suspend fun complete(request: AgentModelRequest, onEvent: suspend (AgentModelEvent) -> Unit) = Result.success(AgentModelResponse("never"))
        }
        val events = AgentLoop(ToolRegistry(listOf(safeTool)), RecordingPermissionGateway(), emptyContext())
            .run(session(mode = AgentMode.PLAN), "plan", model).toList()
        assertEquals(AgentErrorCode.PROVIDER_ERROR, events.filterIsInstance<AgentEvent.Failed>().single().error.code)
    }

    private fun emptyContext() = ContextManager(ContextSource { ContextSnapshot() })
    private fun session(mode: AgentMode = AgentMode.AGENT) = AgentSession("session", "conversation", "workspace", mode, "provider", "model")

    private class ScriptedModel(private val responses: List<AgentModelResponse>) : AgentModelClient {
        override val supportsToolCalling = true
        val requests = mutableListOf<AgentModelRequest>()
        private var index = 0
        override suspend fun complete(request: AgentModelRequest, onEvent: suspend (AgentModelEvent) -> Unit): Result<AgentModelResponse> {
            requests += request
            return Result.success(responses[index++])
        }
    }

    private class RecordingPermissionGateway(private val outcome: PermissionOutcome = PermissionOutcome(PermissionDecision.ALLOW)) : PermissionGateway {
        val requests = mutableListOf<PermissionRequest>()
        val clearedSessions = mutableListOf<String>()
        override suspend fun authorize(request: PermissionRequest): PermissionOutcome { requests += request; return outcome }
        override fun clearSession(sessionId: String) { clearedSessions += sessionId }
    }

    companion object {
        private fun objectSchema() = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("reason", buildJsonObject { put("type", "string") })
            })
        }
    }
}
