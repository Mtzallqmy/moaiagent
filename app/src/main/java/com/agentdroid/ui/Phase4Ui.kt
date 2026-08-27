package com.agentdroid.ui

import android.view.ViewGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.agentdroid.R
import com.agentdroid.core.artifacts.Artifact
import com.agentdroid.core.artifacts.ArtifactType
import com.agentdroid.core.browser.BrowserPageState
import com.agentdroid.core.browser.BrowserSurface
import com.agentdroid.core.browser.BrowserTabMetadata
import com.agentdroid.core.subagents.SubagentStatus
import com.agentdroid.core.subagents.SubagentTimelineItem
import com.agentdroid.core.tasks.Task
import com.agentdroid.core.tasks.TaskStatus
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

/** UI contract for a browser session. The model-facing layer never receives [surface]. */
data class BrowserUiState(
    val sessionId: String,
    val tabs: List<BrowserTabMetadata>,
    val activeTabId: String,
    val page: BrowserPageState,
    val surface: BrowserSurface? = null,
    val linkedToAgent: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Phase4BrowserScreen(
    state: BrowserUiState,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onNewTab: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    onAgentLinkChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var address by rememberSaveableBrowserAddress(state.activeTabId) {
        mutableStateOf(state.page.currentUrl.orEmpty())
    }
    LaunchedEffect(state.page.currentUrl) {
        state.page.currentUrl?.let { if (it != address) address = it }
    }

    Column(modifier.fillMaxSize().testTag("phase4_browser")) {
        TopAppBar(
            title = {
                Column {
                    Text(state.page.title.ifBlank { stringResource(R.string.phase4_browser) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    state.page.currentUrl?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
            actions = {
                IconButton(
                    onClick = { state.page.currentUrl?.let(onOpenExternal) },
                    enabled = state.page.currentUrl != null,
                    modifier = Modifier.testTag("browser_external")
                ) { Icon(Icons.Default.OpenInNew, stringResource(R.string.phase4_browser_open_external)) }
                FilterChip(
                    selected = state.linkedToAgent,
                    onClick = { onAgentLinkChanged(!state.linkedToAgent) },
                    label = { Text(stringResource(R.string.phase4_agent_link)) },
                    leadingIcon = { Icon(Icons.Default.Link, null, Modifier.size(16.dp)) },
                    modifier = Modifier.padding(end = 8.dp).testTag("browser_agent_link")
                )
            }
        )
        if (state.page.loading) LinearProgressIndicator(
            progress = { state.page.progress.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().testTag("browser_loading")
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            state.tabs.forEach { tab ->
                FilterChip(
                    selected = tab.tabId == state.activeTabId,
                    onClick = { onSelectTab(tab.tabId) },
                    label = { Text(tab.title.ifBlank { tab.currentUrl ?: stringResource(R.string.phase4_new_tab) }, maxLines = 1) },
                    trailingIcon = {
                        IconButton(onClick = { onCloseTab(tab.tabId) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, stringResource(R.string.phase4_close_tab), Modifier.size(16.dp))
                        }
                    },
                    modifier = Modifier.widthIn(max = 220.dp).testTag("browser_tab_${tab.tabId}")
                )
            }
            IconButton(onClick = onNewTab, modifier = Modifier.testTag("browser_new_tab")) { Icon(Icons.Default.Add, stringResource(R.string.phase4_new_tab)) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = state.page.canGoBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.phase4_browser_back)) }
            IconButton(onClick = onForward, enabled = state.page.canGoForward) { Icon(Icons.Default.ArrowForward, stringResource(R.string.phase4_browser_forward)) }
            IconButton(onClick = if (state.page.loading) onStop else onRefresh) {
                Icon(if (state.page.loading) Icons.Default.Stop else Icons.Default.Refresh, stringResource(if (state.page.loading) R.string.stop else R.string.phase4_browser_refresh))
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f).testTag("browser_url"),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.phase4_search_or_address)) }
                )
            }
            IconButton(
                onClick = { address.trim().takeIf(String::isNotEmpty)?.let(onNavigate) },
                enabled = address.isNotBlank(),
                modifier = Modifier.testTag("browser_go")
            ) { Icon(Icons.Default.ArrowForward, stringResource(R.string.phase4_go)) }
        }
        state.page.lastError?.let {
            Text(it.technicalMessage, Modifier.fillMaxWidth().padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
        }
        BrowserSurfaceHost(state.surface, state.activeTabId, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun rememberSaveableBrowserAddress(key: String, init: () -> MutableState<String>): MutableState<String> =
    remember(key) { init() }

@Composable
private fun BrowserSurfaceHost(surface: BrowserSurface?, key: String, modifier: Modifier) {
    if (surface == null) {
        Box(modifier.testTag("browser_surface_empty"), contentAlignment = Alignment.Center) { Text(stringResource(R.string.phase4_no_page_loaded)) }
        return
    }
    key(key, surface) {
        AndroidView(
            factory = {
                (surface.view.parent as? ViewGroup)?.removeView(surface.view)
                surface.view
            },
            update = { view ->
                if (view !== surface.view) error("Browser surface changed without a new key")
            },
            modifier = modifier.testTag("browser_surface")
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Phase4TasksScreen(
    tasks: List<Task>,
    selectedTaskId: String?,
    now: Long = System.currentTimeMillis(),
    onSelectTask: (String) -> Unit,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenArtifact: (String) -> Unit,
    onBackToList: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val selected = tasks.firstOrNull { it.id == selectedTaskId }
    Column(modifier.fillMaxSize().testTag("phase4_tasks")) {
        TopAppBar(
            title = { Text(selected?.title ?: stringResource(R.string.phase4_tasks), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (selected != null) IconButton(onBackToList, Modifier.testTag("tasks_back")) { Icon(Icons.Default.ArrowBack, stringResource(R.string.phase4_back_to_tasks)) }
            }
        )
        if (selected == null) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(tasks, key = Task::id) { task -> TaskListRow(task, now) { onSelectTask(task.id) } }
            }
        } else {
            TaskDetails(selected, now, onPause, onCancel, onRetry, onOpenConversation, onOpenArtifact)
        }
    }
}

@Composable
private fun TaskListRow(task: Task, now: Long, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(R.string.phase4_task_summary, task.statusLabel(), task.progress, task.durationLabel(now))) },
        leadingContent = { TaskStatusIcon(task.status) },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        modifier = Modifier.clickable(onClick = onClick).testTag("task_${task.id}")
    )
    LinearProgressIndicator(progress = { task.progress / 100f }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    HorizontalDivider()
}

@Composable
private fun TaskDetails(
    task: Task,
    now: Long,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenArtifact: (String) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().testTag("task_detail_${task.id}"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(task.title, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.phase4_task_status_progress, task.statusLabel(), task.progress))
            LinearProgressIndicator(progress = { task.progress / 100f }, Modifier.fillMaxWidth())
            Text(stringResource(R.string.phase4_current_step, task.plan.steps.firstOrNull { it.id == task.currentStepId }?.title ?: stringResource(R.string.phase4_not_available)))
            Text(stringResource(R.string.phase4_started, task.startedAt?.let(::dateLabel) ?: stringResource(R.string.phase4_not_started)))
            Text(stringResource(R.string.phase4_duration, task.durationLabel(now)))
            Text(stringResource(R.string.phase4_workspace, task.workspaceId), style = MaterialTheme.typography.bodySmall)
        }
        items(task.plan.steps, key = { it.id }) { step ->
            ListItem(
                headlineContent = { Text(step.title) },
                supportingContent = { step.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                leadingContent = { TaskStatusIcon(step.status) },
                modifier = Modifier.testTag("task_step_${step.id}")
            )
        }
        if (task.artifacts.isNotEmpty()) {
            item { Text(stringResource(R.string.phase4_artifacts), style = MaterialTheme.typography.titleMedium) }
            items(task.artifacts, key = { it.artifactId }) { artifact ->
                ListItem(
                    headlineContent = { Text(artifact.title) }, supportingContent = { Text(artifact.type) },
                    leadingContent = { Icon(Icons.Default.Description, null) },
                    modifier = Modifier.testTag("task_artifact_${artifact.artifactId}")
                )
                TextButton({ onOpenArtifact(artifact.artifactId) }) { Text(stringResource(R.string.phase4_open_artifact)) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (task.status == TaskStatus.RUNNING) OutlinedButton({ onPause(task.id) }, Modifier.testTag("task_pause")) { Text(stringResource(R.string.phase4_pause)) }
                if (!task.status.isTerminal) OutlinedButton({ onCancel(task.id) }, Modifier.testTag("task_cancel")) { Text(stringResource(R.string.cancel)) }
                if (task.status == TaskStatus.FAILED || task.recoveryRequired) Button({ onRetry(task.id) }, Modifier.testTag("task_retry")) { Text(stringResource(R.string.retry)) }
                TextButton({ onOpenConversation(task.conversationId) }) { Text(stringResource(R.string.phase4_open_conversation)) }
            }
        }
    }
}

@Composable
private fun TaskStatusIcon(status: TaskStatus) {
    val icon = when (status) {
        TaskStatus.PENDING -> Icons.Default.Schedule
        TaskStatus.RUNNING -> Icons.Default.PlayCircle
        TaskStatus.WAITING_PERMISSION, TaskStatus.WAITING_USER -> Icons.Default.Lock
        TaskStatus.COMPLETED -> Icons.Default.CheckCircle
        TaskStatus.FAILED -> Icons.Default.Error
        TaskStatus.CANCELLED -> Icons.Default.Cancel
    }
    Icon(icon, statusLabel(status))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Phase4ArtifactViewer(
    artifacts: List<Artifact>,
    selectedArtifactId: String?,
    content: String?,
    contentTruncated: Boolean = false,
    onSelect: (String) -> Unit,
    onOpen: (Artifact) -> Unit,
    onRename: (Artifact, String) -> Unit,
    onShare: (Artifact) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (Artifact) -> Unit,
    onExport: (Artifact) -> Unit,
    onBackToList: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val selected = artifacts.firstOrNull { it.id == selectedArtifactId }
    var rename by remember(selected?.id) { mutableStateOf<String?>(null) }
    var delete by remember(selected?.id) { mutableStateOf(false) }
    Column(modifier.fillMaxSize().testTag("phase4_artifacts")) {
        TopAppBar(
            title = { Text(selected?.title ?: stringResource(R.string.phase4_artifacts), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (selected != null) IconButton(onBackToList, Modifier.testTag("artifacts_back")) { Icon(Icons.Default.ArrowBack, stringResource(R.string.phase4_back_to_artifacts)) }
            }
        )
        if (selected == null) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(artifacts, key = Artifact::id) { artifact ->
                    ListItem(
                        headlineContent = { Text(artifact.title) },
                        supportingContent = { Text(stringResource(R.string.phase4_artifact_summary, artifactTypeLabel(artifact.type), artifact.sizeBytes)) },
                        leadingContent = { Icon(artifactIcon(artifact.type), null) },
                        modifier = Modifier.testTag("artifact_${artifact.id}")
                    )
                    TextButton({ onSelect(artifact.id) }) { Text(stringResource(R.string.phase4_preview)) }
                    HorizontalDivider()
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistChip({ onOpen(selected) }, { Text(stringResource(R.string.open)) }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) })
                AssistChip({ rename = selected.title }, { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                AssistChip({ onShare(selected) }, { Text(stringResource(R.string.phase4_share)) }, leadingIcon = { Icon(Icons.Default.Share, null) })
                AssistChip({ content?.let(onCopy) }, { Text(stringResource(R.string.copy)) }, enabled = content != null, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                AssistChip({ delete = true }, { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, modifier = Modifier.testTag("artifact_delete"))
                AssistChip({ onExport(selected) }, { Text(stringResource(R.string.phase4_export)) }, leadingIcon = { Icon(Icons.Default.Download, null) })
            }
            ArtifactPreview(selected, content, contentTruncated, Modifier.weight(1f))
        }
    }
    rename?.let { initial ->
        var value by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { rename = null }, title = { Text(stringResource(R.string.phase4_rename_artifact)) },
            text = { OutlinedTextField(value, { value = it }, Modifier.testTag("artifact_rename_input"), singleLine = true) },
            confirmButton = { Button({ onRename(selected!!, value.trim()); rename = null }, enabled = value.isNotBlank(), modifier = Modifier.testTag("artifact_rename_confirm")) { Text(stringResource(R.string.rename)) } },
            dismissButton = { TextButton({ rename = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (delete && selected != null) AlertDialog(
        onDismissRequest = { delete = false }, title = { Text(stringResource(R.string.phase4_delete_artifact_title)) },
        text = { Text(stringResource(R.string.phase4_delete_artifact_body, selected.title)) },
        confirmButton = { Button({ onDelete(selected); delete = false }, Modifier.testTag("artifact_delete_confirm")) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton({ delete = false }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun ArtifactPreview(artifact: Artifact, content: String?, truncated: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("artifact_preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(artifact.filePath, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        if (artifact.sourceReferences.isNotEmpty()) Text(pluralStringResource(R.plurals.phase4_verified_sources, artifact.sourceReferences.size, artifact.sourceReferences.size), style = MaterialTheme.typography.labelMedium)
        when {
            !artifact.type.textual -> Text(stringResource(R.string.phase4_binary_preview))
            content == null -> CircularProgressIndicator()
            else -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(content, Modifier.padding(12.dp), fontFamily = if (artifact.type == ArtifactType.CODE || artifact.type == ArtifactType.JSON) FontFamily.Monospace else FontFamily.Default)
            }
        }
        if (truncated) Text(stringResource(R.string.phase4_preview_truncated), color = MaterialTheme.colorScheme.tertiary)
    }
}

private fun artifactIcon(type: ArtifactType) = when (type) {
    ArtifactType.SCREENSHOT -> Icons.Default.Image
    ArtifactType.CODE, ArtifactType.JSON -> Icons.Default.Code
    else -> Icons.Default.Description
}

@Composable
fun SubagentTimeline(
    mainAgentLabel: String,
    items: List<SubagentTimelineItem>,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().testTag("subagent_timeline")) {
        ListItem(headlineContent = { Text(mainAgentLabel) }, leadingContent = { Icon(Icons.Default.AccountTree, null) })
        items.forEach { item ->
            val depth = subagentDepth(item, items)
            Row(
                Modifier.fillMaxWidth().padding(start = (16 + depth * 24).dp, end = 16.dp, top = 6.dp, bottom = 6.dp).testTag("subagent_${item.subagentId}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(subagentIcon(item.status), subagentStatusLabel(item.status), Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.phase4_subagent_line, subagentRoleLabel(item.role), subagentStatusLabel(item.status).lowercase()))
                    Text(item.label, style = MaterialTheme.typography.bodySmall)
                    item.failureSummary?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private fun subagentDepth(item: SubagentTimelineItem, all: List<SubagentTimelineItem>): Int {
    var parent = item.parentSubagentId
    var depth = 0
    val visited = mutableSetOf(item.subagentId)
    while (parent != null && visited.add(parent)) {
        depth++
        parent = all.firstOrNull { it.subagentId == parent }?.parentSubagentId
    }
    return depth.coerceAtMost(4)
}

private fun subagentIcon(status: SubagentStatus) = when (status) {
    SubagentStatus.QUEUED -> Icons.Default.Schedule
    SubagentStatus.RUNNING -> Icons.Default.Pending
    SubagentStatus.COMPLETED -> Icons.Default.CheckCircle
    SubagentStatus.FAILED -> Icons.Default.Error
    SubagentStatus.CANCELLED -> Icons.Default.Cancel
}

data class SensitiveFormPermissionUi(
    val domain: String,
    val action: String?,
    val fields: List<FormFieldPreview>
)

data class FormFieldPreview(val name: String, val valuePreview: String, val sensitive: Boolean)

@Composable
fun SensitiveFormPermissionDialog(
    request: SensitiveFormPermissionUi?,
    onAllowOnce: () -> Unit,
    onDeny: () -> Unit
) {
    request ?: return
    AlertDialog(
        onDismissRequest = onDeny,
        modifier = Modifier.testTag("browser_form_permission"),
        icon = { Icon(Icons.Default.Security, null) },
        title = { Text(stringResource(R.string.phase4_submit_sensitive_form)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.phase4_domain, request.domain))
                Text(stringResource(R.string.phase4_form_action, request.action ?: stringResource(R.string.phase4_current_page)), fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.phase4_fields_submitted), style = MaterialTheme.typography.titleSmall)
                request.fields.forEach { field ->
                    ListItem(
                        headlineContent = { Text(field.name) },
                        supportingContent = { Text(if (field.sensitive) stringResource(R.string.phase4_redacted_value) else field.valuePreview) },
                        leadingContent = { Icon(if (field.sensitive) Icons.Default.Lock else Icons.Default.TextFields, null) }
                    )
                }
                Text(stringResource(R.string.phase4_sensitive_form_notice), style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { Button(onAllowOnce, Modifier.testTag("form_allow_once")) { Text(stringResource(R.string.allow_once)) } },
        dismissButton = { TextButton(onDeny, Modifier.testTag("form_deny")) { Text(stringResource(R.string.deny)) } }
    )
}

@Composable
private fun Task.statusLabel() = statusLabel(status)

@Composable
private fun statusLabel(status: TaskStatus) = stringResource(when (status) {
    TaskStatus.PENDING -> R.string.phase4_status_pending
    TaskStatus.RUNNING -> R.string.phase4_status_running
    TaskStatus.WAITING_PERMISSION -> R.string.phase4_status_waiting_permission
    TaskStatus.WAITING_USER -> R.string.phase4_status_waiting_user
    TaskStatus.COMPLETED -> R.string.phase4_status_completed
    TaskStatus.FAILED -> R.string.phase4_status_failed
    TaskStatus.CANCELLED -> R.string.phase4_status_cancelled
})

@Composable
private fun Task.durationLabel(now: Long): String {
    val start = startedAt ?: return stringResource(R.string.phase4_not_available)
    val end = finishedAt ?: now
    val seconds = max(0L, end - start) / 1_000
    val minutes = seconds / 60
    return if (minutes > 0) stringResource(R.string.phase4_duration_minutes_seconds, minutes, seconds % 60)
    else stringResource(R.string.phase4_duration_seconds, seconds)
}

@Composable
private fun artifactTypeLabel(type: ArtifactType) = stringResource(when (type) {
    ArtifactType.MARKDOWN -> R.string.phase4_artifact_markdown
    ArtifactType.PLAIN_TEXT -> R.string.phase4_artifact_plain_text
    ArtifactType.JSON -> R.string.phase4_artifact_json
    ArtifactType.CSV -> R.string.phase4_artifact_csv
    ArtifactType.HTML -> R.string.phase4_artifact_html
    ArtifactType.CODE -> R.string.phase4_artifact_code
    ArtifactType.REPORT -> R.string.phase4_artifact_report
    ArtifactType.SCREENSHOT -> R.string.phase4_artifact_screenshot
})

@Composable
private fun subagentRoleLabel(role: com.agentdroid.core.subagents.SubagentRole) = stringResource(when (role) {
    com.agentdroid.core.subagents.SubagentRole.CODING -> R.string.phase4_subagent_coding
    com.agentdroid.core.subagents.SubagentRole.RESEARCH -> R.string.phase4_subagent_research
    com.agentdroid.core.subagents.SubagentRole.BROWSER -> R.string.phase4_subagent_browser
    com.agentdroid.core.subagents.SubagentRole.REVIEW -> R.string.phase4_subagent_review
})

@Composable
private fun subagentStatusLabel(status: SubagentStatus) = stringResource(when (status) {
    SubagentStatus.QUEUED -> R.string.phase4_subagent_queued
    SubagentStatus.RUNNING -> R.string.phase4_status_running
    SubagentStatus.COMPLETED -> R.string.phase4_status_completed
    SubagentStatus.FAILED -> R.string.phase4_status_failed
    SubagentStatus.CANCELLED -> R.string.phase4_status_cancelled
})

private fun dateLabel(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
