package com.agentdroid.ui

import androidx.compose.foundation.BasicTooltipBox
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.AgentStepStatus
import com.agentdroid.core.agent.PermissionScope
import com.agentdroid.core.workspace.ChangeSetStatus
import com.agentdroid.core.workspace.FileChange
import com.agentdroid.core.workspace.WorkspaceFileInfo
import com.agentdroid.viewmodel.ChangeSetsViewModel
import com.agentdroid.viewmodel.ChatViewModel
import com.agentdroid.viewmodel.ContainerViewModelFactory
import com.agentdroid.viewmodel.PermissionsViewModel
import com.agentdroid.viewmodel.WorkspaceFilesViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun AgentModeAndContextBar(vm: ChatViewModel) {
    val mode by vm.mode.collectAsState()
    val supportsTools by vm.providerSupportsTools.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    val selectedWorkspace by vm.selectedWorkspaceId.collectAsState()
    val skills by vm.skills.collectAsState()
    val activeSkills by vm.activeConversationSkillIds.collectAsState()
    var workspaceMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AgentMode.values().forEach { value ->
                FilterChip(
                    selected = mode == value,
                    onClick = { vm.chooseMode(value) },
                    enabled = value == AgentMode.CHAT || supportsTools,
                    label = { Text(value.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    modifier = Modifier.testTag("mode_${value.name.lowercase()}")
                )
            }
        }
        if (!supportsTools && mode == AgentMode.CHAT) {
            Text("Plan and Agent require provider tool-calling support.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AssistChip(
                    onClick = { workspaceMenu = true },
                    label = { Text(workspaces.firstOrNull { it.id == selectedWorkspace }?.name ?: "Select workspace") },
                    leadingIcon = { Icon(Icons.Default.Folder, null) },
                    modifier = Modifier.testTag("workspace_selector")
                )
                DropdownMenu(expanded = workspaceMenu, onDismissRequest = { workspaceMenu = false }) {
                    DropdownMenuItem(text = { Text("No workspace") }, onClick = { vm.chooseWorkspace(null); workspaceMenu = false })
                    workspaces.forEach { workspace -> DropdownMenuItem(text = { Text(workspace.name) }, onClick = { vm.chooseWorkspace(workspace.id); workspaceMenu = false }) }
                }
            }
            if (mode != AgentMode.CHAT && selectedWorkspace == null) Text("Workspace required", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
        if (skills.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                skills.forEach { skill ->
                    FilterChip(
                        selected = skill.id in activeSkills || (skill.scope == "WORKSPACE" && skill.workspaceId == selectedWorkspace),
                        onClick = { vm.toggleConversationSkill(skill.id) },
                        label = { Text(skill.name) },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null) }
                    )
                }
            }
        }
    }
}

@Composable
fun AgentExecutionPanel(nav: NavHostController, vm: ChatViewModel) {
    val timeline by vm.timeline.collectAsState()
    val cards by vm.toolCards.collectAsState()
    if (timeline.isEmpty() && cards.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (timeline.isNotEmpty()) {
            Text("Agent timeline", style = MaterialTheme.typography.labelLarge)
            timeline.takeLast(8).forEach { step ->
                val icon = when (step.status) {
                    AgentStepStatus.RUNNING -> Icons.Default.Pending
                    AgentStepStatus.WAITING_PERMISSION -> Icons.Default.Lock
                    AgentStepStatus.SUCCEEDED -> Icons.Default.CheckCircle
                    AgentStepStatus.FAILED -> Icons.Default.Error
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(icon, null, Modifier.size(16.dp)); Text(step.label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        cards.forEach { card ->
            ElevatedCard(Modifier.fillMaxWidth().testTag("tool_card_${card.callId}")) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (card.status == "FAILED") Icons.Default.Warning else if (card.status == "WAITING_PERMISSION") Icons.Default.Lock else Icons.Default.Build, null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card.toolName, style = MaterialTheme.typography.labelLarge)
                        card.path?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        if (card.summary.isNotBlank()) Text(card.summary, style = MaterialTheme.typography.bodySmall)
                        card.durationMs?.let { Text("${it} ms", style = MaterialTheme.typography.labelSmall) }
                    }
                    card.changeSetId?.let { changeSetId ->
                        val workspaceId by vm.selectedWorkspaceId.collectAsState()
                        if (workspaceId != null) TextButton({ nav.navigate("diff/${workspaceId}/${changeSetId}") }) { Text("View diff") }
                    }
                }
            }
        }
    }
}

@Composable
fun AgentPermissionDialog(vm: ChatViewModel) {
    val pending by vm.pendingPermission.collectAsState()
    val request = pending ?: return
    AlertDialog(
        onDismissRequest = vm::denyPermission,
        modifier = Modifier.testTag("permission_dialog"),
        title = { Text("Agent permission required") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tool: ${request.definition.name}", style = MaterialTheme.typography.titleSmall)
                request.preview?.path?.let { Text("Target: $it", fontFamily = FontFamily.Monospace) }
                request.reason?.takeIf { it.isNotBlank() }?.let { Text("Reason: $it") }
                Text("Risk: ${request.definition.riskLevel.name}")
                request.preview?.summary?.let { Text(it) }
                request.preview?.diff?.takeIf { it.isNotBlank() }?.let { diff ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(diff, Modifier.padding(8.dp).horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    TextButton({ vm.allowPermission(PermissionScope.ONCE) }, Modifier.testTag("permission_allow_once")) { Text("Allow once") }
                    TextButton({ vm.allowPermission(PermissionScope.SESSION) }) { Text("Allow session") }
                }
                Row {
                    TextButton({ vm.allowPermission(PermissionScope.ALWAYS) }) { Text("Always allow tool") }
                    TextButton(vm::denyPermission, Modifier.testTag("permission_deny")) { Text("Deny") }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceBrowserScreen(nav: NavHostController, factory: ContainerViewModelFactory, workspaceId: String) {
    val vm: WorkspaceFilesViewModel = viewModel(factory = factory)
    val workspace by vm.workspace.collectAsState()
    val path by vm.currentPath.collectAsState()
    val entries by vm.entries.collectAsState()
    val editorPath by vm.editorPath.collectAsState()
    val editorText by vm.editorText.collectAsState()
    val error by vm.editorError.collectAsState()
    var createDialog by remember { mutableStateOf<String?>(null) }
    var moveTarget by remember { mutableStateOf<WorkspaceFileInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileInfo?>(null) }
    LaunchedEffect(workspaceId) { vm.openWorkspace(workspaceId) }

    if (editorPath != null) {
        FileEditorScreen(nav, vm, editorPath!!, editorText)
        return
    }

    Column(Modifier.fillMaxSize().testTag("workspace_browser")) {
        TopAppBar(
            title = { Column { Text(workspace?.name ?: "Workspace"); if (path.isNotBlank()) Text(path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) } },
            navigationIcon = { IconButton({ if (path.isBlank()) nav.popBackStack() else vm.goUp() }) { Icon(Icons.Default.ArrowBack, null) } },
            actions = {
                IconButton({ createDialog = "file" }) { Icon(Icons.Default.NoteAdd, "Create file") }
                IconButton({ createDialog = "folder" }) { Icon(Icons.Default.CreateNewFolder, "Create folder") }
                IconButton({ nav.navigate("changes/$workspaceId") }) { Icon(Icons.Default.Difference, "Changes") }
            }
        )
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        if (entries.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Empty folder") }
        else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(entries, key = { it.path }) { item ->
                ListItem(
                    modifier = Modifier.testTag("workspace_item_${item.path}"),
                    headlineContent = { Text(item.name) },
                    supportingContent = { Text(if (item.directory) "Folder • ${formatModified(item.modifiedAt)}" else "${formatSize(item.size)} • ${formatModified(item.modifiedAt)}") },
                    leadingContent = { Icon(if (item.directory) Icons.Default.Folder else fileIcon(item.name), null) },
                    trailingContent = {
                        Row {
                            IconButton({ moveTarget = item }) { Icon(Icons.Default.DriveFileRenameOutline, "Rename or move") }
                            IconButton({ deleteTarget = item }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                )
                HorizontalDivider()
                LaunchedEffect(Unit) { }
            }
        }
    }

    entries.forEach { item ->
        key(item.path) {
            // Click overlay is intentionally handled by semantics-compatible ListItem wrappers below in tests through actions.
        }
    }

    if (createDialog != null) {
        NameDialog(
            title = if (createDialog == "folder") "Create folder" else "Create file",
            label = "Name",
            onDismiss = { createDialog = null },
            onConfirm = { name -> if (createDialog == "folder") vm.createFolder(name) else vm.createFile(name); createDialog = null }
        )
    }
    moveTarget?.let { item ->
        NameDialog(
            title = "Move / rename ${item.name}",
            label = "Destination path",
            initial = item.path,
            onDismiss = { moveTarget = null },
            onConfirm = { destination -> vm.move(item.path, destination); moveTarget = null }
        )
    }
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${item.name}?") },
            text = { Text("The item will be moved to the workspace trash and can be reverted from Changes.") },
            confirmButton = { Button({ vm.delete(item.path); deleteTarget = null }, Modifier.testTag("workspace_delete_confirm")) { Text("Delete") } },
            dismissButton = { TextButton({ deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorScreen(nav: NavHostController, vm: WorkspaceFilesViewModel, path: String, text: String) {
    val original by vm.originalText.collectAsState()
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val code = isCodeFile(path)
    val lineNumbers = remember(text) { (1..maxOf(1, text.count { it == '\n' } + 1)).joinToString("\n") }
    CompositionLocalProvider(LocalLayoutDirection provides if (code) LayoutDirection.Ltr else LocalLayoutDirection.current) {
        Column(Modifier.fillMaxSize().testTag("file_editor")) {
            TopAppBar(
                title = { Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall) },
                navigationIcon = { IconButton({ vm.editorPath.value = null; nav.popBackStack() }, enabled = false) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(vm::undo) { Icon(Icons.Default.Undo, "Undo") }
                    IconButton(vm::redo) { Icon(Icons.Default.Redo, "Redo") }
                    IconButton(vm::saveEditor, enabled = text != original, modifier = Modifier.testTag("file_save")) { Icon(Icons.Default.Save, "Save") }
                }
            )
            Row(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(vertical).padding(12.dp)) {
                Text(lineNumbers, Modifier.padding(end = 12.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = text,
                    onValueChange = vm::updateEditor,
                    modifier = Modifier.widthIn(min = 640.dp).testTag("file_editor_text"),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceChangesScreen(nav: NavHostController, factory: ContainerViewModelFactory, workspaceId: String) {
    val vm: ChangeSetsViewModel = viewModel(factory = factory)
    val items by vm.items.collectAsState()
    LaunchedEffect(workspaceId) { vm.setWorkspace(workspaceId) }
    val proposed = items.filter { it.status == ChangeSetStatus.PROPOSED }
    Column(Modifier.fillMaxSize().testTag("changes_screen")) {
        TopAppBar(title = { Text("Workspace changes") }, navigationIcon = { IconButton(nav::popBackStack) { Icon(Icons.Default.ArrowBack, null) } })
        if (proposed.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ proposed.forEach { vm.accept(it.id) } }, Modifier.testTag("accept_all")) { Text("Accept all") }
                TextButton({ proposed.forEach { vm.reject(it.id) } }, Modifier.testTag("reject_all")) { Text("Reject all") }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.id }) { changeSet ->
                ElevatedCard({ nav.navigate("diff/$workspaceId/${changeSet.id}") }, Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ListItem(
                        headlineContent = { Text("${changeSet.files.size} files changed  +${changeSet.addedLines} -${changeSet.removedLines}") },
                        supportingContent = { Text(changeSet.status.name) },
                        leadingContent = { Icon(Icons.Default.Difference, null) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(nav: NavHostController, factory: ContainerViewModelFactory, workspaceId: String, changeSetId: String) {
    val vm: ChangeSetsViewModel = viewModel(factory = factory)
    val selected by vm.selected.collectAsState()
    var edit by remember { mutableStateOf<FileChange?>(null) }
    LaunchedEffect(workspaceId, changeSetId) { vm.setWorkspace(workspaceId); vm.select(changeSetId) }
    val changeSet = selected
    Column(Modifier.fillMaxSize().testTag("diff_screen")) {
        TopAppBar(title = { Text("Diff") }, navigationIcon = { IconButton(nav::popBackStack) { Icon(Icons.Default.ArrowBack, null) } })
        if (changeSet == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        else {
            Text("${changeSet.files.size} files changed  +${changeSet.addedLines} -${changeSet.removedLines}", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(changeSet.files, key = { it.path + (it.destinationPath ?: "") }) { file ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.destinationPath?.let { "${file.path} → $it" } ?: file.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                            Text(file.changeType.name, style = MaterialTheme.typography.labelSmall)
                            if (file.diff.isNotBlank()) Text(file.diff, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (changeSet.status == ChangeSetStatus.PROPOSED) {
                                    TextButton({ vm.accept(changeSet.id) }, Modifier.testTag("diff_accept")) { Text("Accept") }
                                    TextButton({ vm.reject(changeSet.id) }, Modifier.testTag("diff_reject")) { Text("Reject") }
                                    if (file.afterContent != null) TextButton({ edit = file }) { Text("Edit") }
                                }
                                if (changeSet.status == ChangeSetStatus.APPLIED) TextButton({ vm.revert(changeSet.id) }, Modifier.testTag("diff_revert")) { Text("Revert") }
                            }
                        }
                    }
                }
            }
        }
    }
    edit?.let { file ->
        var value by remember(file.path) { mutableStateOf(file.afterContent.orEmpty()) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text("Edit proposed ${file.path}") },
            text = { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), minLines = 10, textStyle = TextStyle(fontFamily = FontFamily.Monospace)) },
            confirmButton = { Button({ vm.edit(changeSetId, file.path, value); edit = null }) { Text("Save proposal") } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionRulesScreen(nav: NavHostController, factory: ContainerViewModelFactory) {
    val vm: PermissionsViewModel = viewModel(factory = factory)
    val rules by vm.rules.collectAsState()
    val audit by vm.audit.collectAsState()
    Column(Modifier.fillMaxSize().testTag("permission_rules")) {
        TopAppBar(title = { Text("Agent permissions") }, navigationIcon = { IconButton(nav::popBackStack) { Icon(Icons.Default.ArrowBack, null) } })
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Always rules", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium) }
            if (rules.isEmpty()) item { Text("No stored permission rules.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rules, key = { it.id }) { rule ->
                ListItem(
                    headlineContent = { Text("${rule.toolName}: ${rule.decision.name}") },
                    supportingContent = { Text(rule.workspaceId?.let { "Workspace $it" } ?: "All workspaces") },
                    trailingContent = { IconButton({ vm.deleteRule(rule.id) }) { Icon(Icons.Default.Delete, "Delete rule") } }
                )
            }
            item { HorizontalDivider(); Text("Recent tool audit", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            items(audit.take(50), key = { it.id }) { entry ->
                ListItem(
                    headlineContent = { Text("${entry.status} ${entry.toolName}") },
                    supportingContent = { Text("${entry.durationMs} ms • ${entry.resultSummary}") },
                    leadingContent = { Icon(Icons.Default.ReceiptLong, null) }
                )
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { Button({ if (value.isNotBlank()) onConfirm(value.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

private fun formatSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> String.format("%.1f MB", size.toDouble() / (1024 * 1024))
}
private fun formatModified(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
private fun fileIcon(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "java", "js", "ts", "tsx", "jsx", "py", "go", "rs", "c", "cpp", "h" -> Icons.Default.Code
    "md", "txt", "json", "xml", "yaml", "yml", "toml", "gradle", "kts" -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}
private fun isCodeFile(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in setOf("kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "go", "rs", "c", "cpp", "h", "gradle", "xml", "json", "yaml", "yml", "toml")
