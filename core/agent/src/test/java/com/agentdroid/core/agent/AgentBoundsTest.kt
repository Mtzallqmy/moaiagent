package com.agentdroid.core.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentBoundsTest {
    private val tool = object : AgentTool {
        override val definition = ToolDefinition("read_file", "read", buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) }, RiskLevel.SAFE, ToolCategory.FILE_READ)
        override suspend fun execute(input: JsonObject, context: ToolContext) = ToolResult.success("ok")
    }
    private val permissions = object : PermissionGateway {
        override suspend fun authorize(request: PermissionRequest) = PermissionOutcome(PermissionDecision.ALLOW)
    }
    private val context = ContextManager(ContextSource { ContextSnapshot() })
    private val session = AgentSession("s", "c", "w", AgentMode.AGENT, "p", "m")

    @Test fun timeoutStopsSlowModel() = runBlocking {
        val model = object : AgentModelClient {
            override val supportsToolCalling = true
            override suspend fun complete(request: AgentModelRequest, onEvent: suspend (AgentModelEvent) -> Unit): Result<AgentModelResponse> {
                delay(250)
                return Result.success(AgentModelResponse("late"))
            }
        }
        val events = AgentLoop(ToolRegistry(listOf(tool)), permissions, context, config = AgentConfig(maxExecutionTimeMs = 50)).run(session, "x", model).toList()
        assertEquals(AgentErrorCode.AGENT_TIMEOUT, events.filterIsInstance<AgentEvent.Failed>().single().error.code)
    }

    @Test fun maxToolCallsStopsAdditionalCalls() = runBlocking {
        val model = object : AgentModelClient {
            override val supportsToolCalling = true
            override suspend fun complete(request: AgentModelRequest, onEvent: suspend (AgentModelEvent) -> Unit) = Result.success(
                AgentModelResponse("", listOf(
                    ToolCall("1", "read_file", buildJsonObject {}),
                    ToolCall("2", "read_file", buildJsonObject {})
                ))
            )
        }
        val events = AgentLoop(ToolRegistry(listOf(tool)), permissions, context, config = AgentConfig(maxTurns = 2, maxToolCalls = 1)).run(session, "x", model).toList()
        assertEquals(AgentErrorCode.AGENT_TOOL_CALL_LIMIT_REACHED, events.filterIsInstance<AgentEvent.Failed>().single().error.code)
    }
}
