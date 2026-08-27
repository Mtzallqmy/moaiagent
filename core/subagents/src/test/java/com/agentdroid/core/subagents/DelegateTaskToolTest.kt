package com.agentdroid.core.subagents

import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegateTaskToolTest {
    @Test fun delegatesStructuredTaskAndReturnsReferences() = runBlocking {
        var captured: SubagentTask? = null
        val coordinator = object : SubagentCoordinator {
            override val timeline = MutableStateFlow<List<SubagentTimelineItem>>(emptyList())
            override suspend fun delegate(task: SubagentTask): SubagentResult {
                captured = task
                return SubagentResult(
                    "agent-1", task.id, task.role, SubagentStatus.COMPLETED, "Compared sources",
                    sourceReferences = listOf("source-1"), attempts = 1, startedAt = 1, finishedAt = 2
                )
            }
            override suspend fun delegate(role: SubagentRole, objective: String, context: SubagentContext, parentTask: SubagentTask?) =
                error("not used")
        }
        val tool = DelegateTaskTool(coordinator)
        val input = buildJsonObject {
            put("role", "RESEARCH")
            put("task", "Compare Android PTY libraries")
            put("context", "Phase 3 runtime architecture")
        }
        val context = ToolContext("workspace", "conversation", "session", AgentMode.AGENT, toolCallId = "call-1")
        val result = tool.execute(input, context)

        assertTrue(result.success)
        assertEquals("call-1", captured?.id)
        assertEquals(SubagentRole.RESEARCH, captured?.role)
        assertEquals("Phase 3 runtime architecture", captured?.context?.sections?.get(ContextSection.TASK_SUMMARY))
        assertTrue(result.output.toString().contains("source-1"))
        assertEquals(RiskLevel.EXTERNAL, tool.effectiveRisk(input, context))
    }

    @Test fun rejectsUnknownRoleAndBlankTask() = runBlocking {
        val never = object : SubagentCoordinator {
            override val timeline = MutableStateFlow<List<SubagentTimelineItem>>(emptyList())
            override suspend fun delegate(task: SubagentTask) = error("must not run")
            override suspend fun delegate(role: SubagentRole, objective: String, context: SubagentContext, parentTask: SubagentTask?) = error("must not run")
        }
        val tool = DelegateTaskTool(never)
        val context = ToolContext("w", "c", "s", AgentMode.AGENT)
        val invalidRole = tool.execute(buildJsonObject { put("role", "WRITER"); put("task", "work") }, context)
        val blank = tool.execute(buildJsonObject { put("role", "REVIEW"); put("task", "  ") }, context)

        assertFalse(invalidRole.success)
        assertFalse(blank.success)
    }
}
