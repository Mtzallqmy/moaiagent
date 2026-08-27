package com.agentdroid.core.permissions

import com.agentdroid.core.agent.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionEngineTest {
    @Test fun safeToolsAreAllowedWithoutPrompt() = runBlocking {
        var prompts = 0
        val engine = PermissionEngine(InMemoryPermissionRuleStore(), PermissionPrompter { prompts++; PermissionResponse(PermissionDecision.DENY) })
        val result = engine.authorize(request("read_file", RiskLevel.SAFE))
        assertEquals(PermissionDecision.ALLOW, result.decision)
        assertEquals("risk-default", result.source)
        assertEquals(0, prompts)
    }

    @Test fun modifyDefaultsToAskAndOnceDoesNotPersist() = runBlocking {
        var prompts = 0
        val engine = PermissionEngine(InMemoryPermissionRuleStore(), PermissionPrompter { prompts++; PermissionResponse(PermissionDecision.ALLOW, PermissionScope.ONCE) })
        engine.authorize(request("write_file", RiskLevel.MODIFY))
        engine.authorize(request("write_file", RiskLevel.MODIFY))
        assertEquals(2, prompts)
    }

    @Test fun sessionPermissionExpiresWhenSessionClears() = runBlocking {
        var prompts = 0
        val engine = PermissionEngine(InMemoryPermissionRuleStore(), PermissionPrompter { prompts++; PermissionResponse(PermissionDecision.ALLOW, PermissionScope.SESSION) })
        val first = request("patch_file", RiskLevel.MODIFY, session = "s1")
        assertEquals(PermissionScope.SESSION, engine.authorize(first).scope)
        assertEquals("session", engine.authorize(first).source)
        assertEquals(1, prompts)
        engine.clearSession("s1")
        engine.authorize(first)
        assertEquals(2, prompts)
    }

    @Test fun alwaysPermissionPersistsAndCanBeRemoved() = runBlocking {
        val store = InMemoryPermissionRuleStore()
        var prompts = 0
        val engine = PermissionEngine(store, PermissionPrompter { prompts++; PermissionResponse(PermissionDecision.ALLOW, PermissionScope.ALWAYS) })
        val first = engine.authorize(request("move_file", RiskLevel.MODIFY, workspace = "w1"))
        assertEquals(PermissionScope.ALWAYS, first.scope)
        assertEquals(1, prompts)
        val second = engine.authorize(request("move_file", RiskLevel.MODIFY, workspace = "w1", session = "s2"))
        assertEquals("stored", second.source)
        assertEquals(1, prompts)
        val rule = store.list().single()
        assertEquals("w1", rule.workspaceId)
        engine.removeRule(rule.id)
        assertTrue(store.list().isEmpty())
    }

    @Test fun storedWorkspaceRuleOverridesGlobalRuleBySpecificity() = runBlocking {
        val store = InMemoryPermissionRuleStore(
            listOf(
                PermissionRule(toolName = "delete_file", workspaceId = null, decision = PermissionDecision.ALLOW, createdAt = 2),
                PermissionRule(toolName = "delete_file", workspaceId = "w1", decision = PermissionDecision.DENY, createdAt = 1)
            )
        )
        val engine = PermissionEngine(store, PermissionPrompter { error("must not prompt") })
        assertEquals(PermissionDecision.DENY, engine.authorize(request("delete_file", RiskLevel.DESTRUCTIVE, workspace = "w1")).decision)
        assertEquals(PermissionDecision.ALLOW, engine.authorize(request("delete_file", RiskLevel.DESTRUCTIVE, workspace = "w2")).decision)
    }

    @Test fun deniedPromptIsReturnedWithoutCreatingRule() = runBlocking {
        val store = InMemoryPermissionRuleStore()
        val engine = PermissionEngine(store, PermissionPrompter { PermissionResponse(PermissionDecision.DENY, PermissionScope.ALWAYS) })
        val result = engine.authorize(request("delete_file", RiskLevel.DESTRUCTIVE))
        assertEquals(PermissionDecision.DENY, result.decision)
        assertTrue(store.list().isEmpty())
    }

    private fun request(
        tool: String,
        risk: RiskLevel,
        workspace: String = "w1",
        session: String = "s1"
    ) = PermissionRequest(
        requestId = "request-$tool-$session",
        toolCall = ToolCall("call-$tool", tool, buildJsonObject { put("path", "a.txt") }),
        definition = ToolDefinition(tool, tool, buildJsonObject { put("type", "object") }, risk, ToolCategory.FILE_MODIFY),
        workspaceId = workspace,
        conversationId = "c1",
        sessionId = session
    )
}
