package com.agentdroid.core.permissions

import com.agentdroid.core.agent.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandPermissionRuleTest {
    @Test fun alwaysRuleIsScopedToConstrainedCommandPattern() = runBlocking {
        val store = InMemoryPermissionRuleStore()
        var prompts = 0
        val engine = PermissionEngine(store, PermissionPrompter { prompts++; PermissionResponse(PermissionDecision.ALLOW, PermissionScope.ALWAYS) })
        val definition = ToolDefinition("run_command", "run", buildJsonObject { put("type", "object") }, RiskLevel.MODIFY, ToolCategory.SHELL)
        fun request(pattern: String) = PermissionRequest("r$prompts", ToolCall("c$prompts", "run_command", buildJsonObject {}), definition, "ws", "conv", "session", ruleKey = "run_command:$pattern")

        assertEquals(PermissionDecision.ALLOW, engine.authorize(request("git diff *")).decision)
        assertEquals(1, prompts)
        assertEquals(PermissionDecision.ALLOW, engine.authorize(request("git diff *")).decision)
        assertEquals(1, prompts)
        assertEquals(PermissionDecision.ALLOW, engine.authorize(request("rm *")).decision)
        assertEquals(2, prompts)
    }

    @Test fun unsafeWildcardCannotBePersisted() {
        val engine = PermissionEngine(InMemoryPermissionRuleStore(), PermissionPrompter { PermissionResponse(PermissionDecision.DENY) })
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.setAlways("run_command:*", "ws", PermissionDecision.ALLOW) }
        }
    }
}
