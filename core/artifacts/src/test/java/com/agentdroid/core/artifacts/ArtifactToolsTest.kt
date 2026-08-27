package com.agentdroid.core.artifacts

import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCall
import com.agentdroid.core.agent.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ArtifactToolsTest {
    private lateinit var root: File
    private lateinit var repository: ArtifactRepository
    private val context = ToolContext("w1", "c1", "session", AgentMode.AGENT)

    @Before fun setUp() {
        root = Files.createTempDirectory("artifact-tools").toFile()
        repository = FileArtifactRepository(ArtifactWorkspaceProvider { root }, newId = { "id-1" })
    }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun toolsCreateListReadAndClassifyDeleteAsDestructive() = runBlocking {
        val registry = createArtifactToolRegistry(ArtifactServices { repository })
        val create = ToolCall("call-1", "create_artifact", buildJsonObject {
            put("type", "MARKDOWN"); put("title", "Report"); put("content", "# Hello")
        })
        assertTrue(registry.execute(create, context).success)
        assertTrue(registry.execute(ToolCall("call-2", "list_artifacts", buildJsonObject { }), context).success)
        assertTrue(registry.execute(ToolCall("call-3", "read_artifact", buildJsonObject { put("id", "id-1") }), context).success)

        val delete = ToolCall("call-4", "delete_artifact", buildJsonObject { put("id", "id-1") })
        assertEquals(RiskLevel.DESTRUCTIVE, registry.effectiveRisk(delete, context).getOrThrow())
        assertTrue(registry.execute(delete, context).success)
    }
}
