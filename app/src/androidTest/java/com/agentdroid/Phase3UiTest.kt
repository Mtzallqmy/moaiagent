package com.agentdroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.agentdroid.data.database.WorkspaceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class Phase3UiTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var container: AppContainer
    private lateinit var workspaceId: String
    private lateinit var workspaceName: String

    @Before fun seed() = runBlocking {
        container = (composeRule.activity.application as AgentDroidApplication).container
        val suffix = UUID.randomUUID().toString().take(8)
        workspaceId = "p3_$suffix"
        workspaceName = "Phase3 Workspace $suffix"
        val now = System.currentTimeMillis()
        val root = container.workspaceRoot(workspaceId).apply { mkdirs() }
        java.io.File(root, "sub").mkdirs()
        java.io.File(root, "sample.txt").writeText("base\n")
        container.database.workspaces().upsert(WorkspaceEntity(workspaceId, workspaceName, "Phase 3 UI test", now, now, root.canonicalPath))
        composeRule.waitForIdle()
    }

    @Test fun terminalRunsRealPtyAndSupportsMultipleSessions() {
        openWorkspace()
        composeRule.onNodeWithTag("workspace_open_terminal").performClick()
        composeRule.onNodeWithTag("terminal_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_view").assertIsDisplayed()
        composeRule.waitUntil(8_000) { container.terminalManager.sessions.value.any { it.workspaceId == workspaceId } }
        val first = container.terminalManager.sessions.value.first { it.workspaceId == workspaceId }.sessionId
        container.terminalManager.get(first)!!.write("echo phase3-hello\r")
        composeRule.waitUntil(8_000) { container.terminalManager.get(first)?.transcript()?.contains("phase3-hello") == true }
        assertTrue(container.terminalManager.get(first)!!.transcript().contains("phase3-hello"))

        composeRule.onNodeWithContentDescription("New terminal").performClick()
        composeRule.waitUntil(5_000) { container.terminalManager.sessions.value.count { it.workspaceId == workspaceId } >= 2 }
        val ids = container.terminalManager.sessions.value.filter { it.workspaceId == workspaceId }.map { it.sessionId }
        composeRule.onNodeWithTag("terminal_session_${ids.first()}").performClick()
        composeRule.onNodeWithContentDescription("Close session").performClick()
        composeRule.waitUntil(5_000) { container.terminalManager.sessions.value.count { it.workspaceId == workspaceId } == 1 }
        assertEquals(1, container.terminalManager.sessions.value.count { it.workspaceId == workspaceId })
    }

    @Test fun gitUiInitializesStagesDiffsAndCommits() {
        openWorkspace()
        composeRule.onNodeWithTag("workspace_open_git").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("git_screen")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("git_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("git_init").performClick()
        composeRule.waitUntil(8_000) { runBlocking { container.gitEngine.isRepository(container.workspaceRoot(workspaceId)) } }
        composeRule.waitUntil(8_000) { composeRule.onAllNodes(hasText("sample.txt")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithContentDescription("View diff").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Git diff")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Git diff").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithContentDescription("Stage").performClick()
        composeRule.waitUntil(8_000) { runBlocking { "sample.txt" in container.gitEngine.status(container.workspaceRoot(workspaceId)).getOrThrow().staged } }
        composeRule.onNodeWithTag("git_commit_message").performTextReplacement("Phase 3 UI commit")
        composeRule.onNodeWithTag("git_commit").performClick()
        composeRule.onNodeWithTag("git_commit_confirm").performClick()
        composeRule.waitUntil(8_000) { runBlocking { container.gitEngine.log(container.workspaceRoot(workspaceId), 1).getOrDefault(emptyList()).firstOrNull()?.message == "Phase 3 UI commit" } }
        assertEquals("Phase 3 UI commit", runBlocking { container.gitEngine.log(container.workspaceRoot(workspaceId), 1).getOrThrow().first().message })
    }

    private fun openWorkspace() {
        composeRule.onNodeWithText("Workspaces").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(workspaceName)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(workspaceName).performClick()
        composeRule.onNodeWithTag("workspace_browser").assertIsDisplayed()
    }
}
