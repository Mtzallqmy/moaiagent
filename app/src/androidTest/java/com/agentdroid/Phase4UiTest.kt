package com.agentdroid

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.agentdroid.core.browser.BrowserPageState
import com.agentdroid.core.browser.BrowserTabMetadata
import com.agentdroid.core.artifacts.Artifact
import com.agentdroid.core.artifacts.ArtifactType
import com.agentdroid.core.subagents.SubagentRole
import com.agentdroid.core.subagents.SubagentStatus
import com.agentdroid.core.subagents.SubagentTimelineItem
import com.agentdroid.core.tasks.Task
import com.agentdroid.core.tasks.TaskPlan
import com.agentdroid.core.tasks.TaskStatus
import com.agentdroid.core.tasks.TaskStep
import com.agentdroid.ui.BrowserUiState
import com.agentdroid.ui.FormFieldPreview
import com.agentdroid.ui.Phase4ArtifactViewer
import com.agentdroid.ui.Phase4BrowserScreen
import com.agentdroid.ui.Phase4TasksScreen
import com.agentdroid.ui.SensitiveFormPermissionDialog
import com.agentdroid.ui.SensitiveFormPermissionUi
import com.agentdroid.ui.SubagentTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Phase4UiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun browserAddressAndAgentLinkUseCallbacks() {
        var navigated = ""
        var linked = false
        compose.setContent {
            MaterialTheme {
                Phase4BrowserScreen(
                    state = BrowserUiState(
                        sessionId = "session-1",
                        tabs = listOf(BrowserTabMetadata("tab-1", "Example", "https://example.test")),
                        activeTabId = "tab-1",
                        page = BrowserPageState(title = "Example", currentUrl = "https://example.test")
                    ),
                    onNavigate = { navigated = it }, onBack = {}, onForward = {}, onRefresh = {}, onStop = {},
                    onNewTab = {}, onSelectTab = {}, onCloseTab = {}, onOpenExternal = {},
                    onAgentLinkChanged = { linked = it }
                )
            }
        }

        compose.onNodeWithTag("browser_url").performTextReplacement("https://docs.example.test")
        compose.onNodeWithTag("browser_go").performClick()
        compose.runOnIdle { assertEquals("https://docs.example.test", navigated) }
        compose.onNodeWithTag("browser_agent_link").performClick()
        compose.runOnIdle { assertTrue(linked) }
    }

    @Test fun sensitiveFormRedactsValueAndOnlyOffersOneTimePermission() {
        var allowed = false
        compose.setContent {
            MaterialTheme {
                SensitiveFormPermissionDialog(
                    request = SensitiveFormPermissionUi(
                        domain = "accounts.example.test",
                        action = "/login",
                        fields = listOf(
                            FormFieldPreview("email", "person@example.test", false),
                            FormFieldPreview("password", "never-display-this", true)
                        )
                    ),
                    onAllowOnce = { allowed = true }, onDeny = {}
                )
            }
        }

        compose.onNodeWithTag("browser_form_permission").assertIsDisplayed()
        compose.onNodeWithText("••••••••").assertIsDisplayed()
        compose.onNodeWithText("never-display-this").assertDoesNotExist()
        compose.onNodeWithTag("form_allow_once").performClick()
        compose.runOnIdle { assertTrue(allowed) }
    }

    @Test fun taskProgressAndCancelAreDrivenByStateAndCallbacks() {
        var selected by mutableStateOf<String?>(null)
        var cancelled = ""
        val task = Task(
            id = "task-1", title = "Research Android libraries", workspaceId = "workspace-1", conversationId = "conversation-1",
            plan = TaskPlan(
                summary = "Search and compare",
                steps = listOf(TaskStep("step-1", "Search sources", position = 0, status = TaskStatus.RUNNING, startedAt = 1L)),
                updatedAt = 1L
            ),
            status = TaskStatus.RUNNING, progress = 35, currentStepId = "step-1", createdAt = 1L, startedAt = 1L
        )
        compose.setContent {
            MaterialTheme {
                Phase4TasksScreen(
                    tasks = listOf(task), selectedTaskId = selected, now = 10_001L,
                    onSelectTask = { selected = it }, onPause = {}, onCancel = { cancelled = it }, onRetry = {},
                    onOpenConversation = {}, onOpenArtifact = {}
                )
            }
        }

        compose.onNodeWithTag("task_task-1").performClick()
        compose.onNodeWithTag("task_detail_task-1").assertIsDisplayed()
        compose.onNodeWithTag("task_cancel").performClick()
        compose.runOnIdle { assertEquals("task-1", cancelled) }
    }

    @Test fun artifactDeletionRequiresConfirmation() {
        var deleted = ""
        val artifact = Artifact(
            id = "artifact-1", conversationId = "conversation-1", workspaceId = "workspace-1",
            type = ArtifactType.REPORT, title = "Library comparison", filePath = "Artifacts/report.md",
            mimeType = "text/markdown", createdAt = 1L, updatedAt = 1L, sizeBytes = 42
        )
        compose.setContent {
            MaterialTheme {
                Phase4ArtifactViewer(
                    artifacts = listOf(artifact), selectedArtifactId = "artifact-1", content = "# Report",
                    onSelect = {}, onOpen = {}, onRename = { _, _ -> }, onShare = {}, onCopy = {},
                    onDelete = { deleted = it.id }, onExport = {}
                )
            }
        }

        compose.onNodeWithTag("artifact_delete").performClick()
        compose.onNodeWithTag("artifact_delete_confirm").performClick()
        compose.runOnIdle { assertEquals("artifact-1", deleted) }
    }

    @Test fun subagentTimelineExposesStatusWithoutReasoning() {
        compose.setContent {
            MaterialTheme {
                SubagentTimeline(
                    mainAgentLabel = "Main Agent",
                    items = listOf(SubagentTimelineItem("agent-1", null, "task-1", SubagentRole.RESEARCH, "Collect sources", SubagentStatus.COMPLETED, 1L, 2L))
                )
            }
        }
        compose.onNodeWithTag("subagent_agent-1").assertIsDisplayed()
        compose.onNodeWithText("Research Agent — completed").assertIsDisplayed()
    }
}
