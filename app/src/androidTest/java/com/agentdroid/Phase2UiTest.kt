package com.agentdroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.agentdroid.core.agent.*
import com.agentdroid.core.workspace.FileChange
import com.agentdroid.core.workspace.FileChangeType
import com.agentdroid.core.workspace.hashText
import com.agentdroid.data.database.ProviderConfigEntity
import com.agentdroid.data.database.WorkspaceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class Phase2UiTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var container: AppContainer
    private lateinit var workspaceId: String
    private lateinit var workspaceName: String
    private lateinit var providerName: String

    @Before fun seed() = runBlocking {
        container = (composeRule.activity.application as AgentDroidApplication).container
        val suffix = UUID.randomUUID().toString().take(8)
        workspaceId = "ui_$suffix"
        workspaceName = "UI Workspace $suffix"
        providerName = "UI Fake $suffix"
        val now = System.currentTimeMillis()
        val root = container.workspaceRoot(workspaceId).apply { mkdirs() }
        container.database.workspaces().upsert(WorkspaceEntity(workspaceId, workspaceName, "UI test workspace", now, now, root.canonicalPath))
        container.database.providers().upsert(
            ProviderConfigEntity("fake_$suffix", providerName, "FAKE", null, "fake-model", null, null, null, null, "{}", true)
        )
        container.workspaceFileSystem(workspaceId).resolve("sample.kt").writeText("val answer = 41\n")
        composeRule.waitForIdle()
    }

    @Test fun switchChatPlanAgentModesWithWorkspace() {
        composeRule.onNodeWithText("Provider").performClick()
        composeRule.onNodeWithText(providerName).performClick()
        composeRule.onNodeWithTag("workspace_selector").performClick()
        composeRule.onNodeWithText(workspaceName).performClick()
        composeRule.onNodeWithTag("mode_plan").performClick().assertIsSelected()
        composeRule.onNodeWithTag("mode_agent").performClick().assertIsSelected()
        composeRule.onNodeWithTag("mode_chat").performClick().assertIsSelected()
    }

    @Test fun openWorkspaceOpenFileEditSaveUndoRedo() {
        composeRule.onNodeWithText("Workspaces").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(workspaceName)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(workspaceName).performClick()
        composeRule.onNodeWithTag("workspace_browser").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace_item_sample.kt").performClick()
        composeRule.onNodeWithTag("file_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("file_editor_text").performTextReplacement("val answer = 42\n// edited")
        composeRule.onNodeWithContentDescription("Undo").performClick()
        composeRule.onNodeWithContentDescription("Redo").performClick()
        composeRule.onNodeWithTag("file_save").performClick()
        composeRule.waitUntil(5_000) { container.workspaceFileSystem(workspaceId).read("sample.kt").content?.contains("42") == true }
        assertTrue(container.workspaceFileSystem(workspaceId).read("sample.kt").content.orEmpty().contains("// edited"))
    }

    @Test fun permissionDialogApprovesThroughCoordinator() {
        val request = PermissionRequest(
            requestId = "ui-permission",
            toolCall = ToolCall("call-ui", "patch_file", buildJsonObject { put("path", "sample.kt"); put("reason", "UI permission test") }),
            definition = ToolDefinition("patch_file", "Patch file", buildJsonObject { put("type", "object") }, RiskLevel.MODIFY, ToolCategory.FILE_MODIFY),
            workspaceId = workspaceId,
            conversationId = "ui-conversation",
            sessionId = "ui-session",
            reason = "Update sample",
            preview = ToolPreview("Update sample.kt", "sample.kt", "-val answer = 41\n+val answer = 42")
        )
        val result = CoroutineScope(Dispatchers.Default).async { container.permissionCoordinator.prompt(request) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("permission_dialog")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("permission_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("permission_allow_once").performClick()
        assertEquals(PermissionDecision.ALLOW, runBlocking { result.await() }.decision)
    }

    @Test fun viewDiffRejectAndAcceptRevertChanges() = runBlocking {
        val fs = container.workspaceFileSystem(workspaceId)
        val before = fs.read("sample.kt").content.orEmpty()
        val after = before.replace("41", "42")
        val first = container.changeSetManager(workspaceId).propose(listOf(FileChange(
            path = "sample.kt", beforeHash = fs.sha256("sample.kt"), afterHash = hashText(after), beforeContent = before, afterContent = after,
            diff = container.diffEngine.diff("sample.kt", before, after).unifiedDiff, changeType = FileChangeType.MODIFY
        )))

        composeRule.onNodeWithText("Workspaces").performClick()
        composeRule.onNodeWithText(workspaceName).performClick()
        composeRule.onNodeWithContentDescription("Changes").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("PROPOSED")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("PROPOSED").performClick()
        composeRule.onNodeWithTag("diff_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("diff_reject").performClick()
        composeRule.waitUntil(5_000) { container.changeSetManager(workspaceId).get(first.id)?.status?.name == "REJECTED" }
        assertEquals("REJECTED", container.changeSetManager(workspaceId).get(first.id)?.status?.name)

        composeRule.onNodeWithContentDescription("Back").performClick()
        val secondBefore = fs.read("sample.kt").content.orEmpty()
        val secondAfter = secondBefore.replace("41", "43")
        val second = container.changeSetManager(workspaceId).propose(listOf(FileChange(
            path = "sample.kt", beforeHash = fs.sha256("sample.kt"), afterHash = hashText(secondAfter), beforeContent = secondBefore, afterContent = secondAfter,
            diff = container.diffEngine.diff("sample.kt", secondBefore, secondAfter).unifiedDiff, changeType = FileChangeType.MODIFY
        )))
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("PROPOSED")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(hasText("PROPOSED"))[0].performClick()
        composeRule.onNodeWithTag("diff_accept").performClick()
        composeRule.waitUntil(5_000) { fs.read("sample.kt").content?.contains("43") == true }
        composeRule.onNodeWithTag("diff_revert").performClick()
        composeRule.waitUntil(5_000) { container.changeSetManager(workspaceId).get(second.id)?.status?.name == "REVERTED" }
        assertTrue(fs.read("sample.kt").content.orEmpty().contains("41"))
    }
}
