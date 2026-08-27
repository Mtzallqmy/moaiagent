package com.agentdroid.core.subagents

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBackedSubagentTest {
    @Test fun modelAndToolRegistryAdaptersReceiveOnlyBoundedRoleContract(): Unit = runBlocking {
        val requests = mutableListOf<SubagentModelRequest>()
        var turn = 0
        val model = SubagentModelGateway { request ->
            requests += request
            turn++
            if (turn == 1) {
                SubagentModelResponse(
                    toolCalls = listOf(SubagentToolCall("search-1", "web_search", buildJsonObject { put("query", "PTY Android") }))
                )
            } else {
                SubagentModelResponse(finalSummary = "Two sources compared", sourceReferences = listOf("s1", "s2"))
            }
        }
        val executed = mutableListOf<String>()
        val profile = DefaultSubagentProfiles.RESEARCH.copy(tokenLimit = 1_000)
        val coordinator = DefaultSubagentCoordinator(
            factory = SubagentFactory { ModelBackedSubagent(it.role, model) },
            toolGateway = SubagentToolGateway { name, _, _ ->
                executed += name
                SubagentToolResult(true, "search complete")
            },
            profiles = SubagentProfileProvider { profile }
        )
        val result = coordinator.delegate(
            SubagentRole.RESEARCH,
            "Compare PTY libraries",
            SubagentContext(
                "w", "c",
                mapOf(ContextSection.TASK_SUMMARY to "runtime", ContextSection.SELECTED_FILES to "private code")
            )
        )

        assertEquals(SubagentStatus.COMPLETED, result.status)
        assertEquals(listOf("web_search"), executed)
        assertEquals(2, requests.size)
        assertEquals(profile.allowedTools, requests.first().allowedTools)
        assertEquals(1_000, requests.first().tokenLimit)
        assertTrue(requests.first().context.sections.keys.none { it == ContextSection.SELECTED_FILES })
        assertEquals(listOf("s1", "s2"), result.sourceReferences)
    }
}
