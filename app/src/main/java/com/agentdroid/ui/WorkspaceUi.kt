package com.agentdroid.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.agentdroid.R
import com.agentdroid.core.workspace.ChangeSetStatus
import com.agentdroid.core.workspace.FileChange
import com.agentdroid.core.workspace.WorkspaceFileInfo
import com.agentdroid.data.database.WorkspaceEntity
import com.agentdroid.viewmodel.ChangeSetsViewModel
import com.agentdroid.viewmodel.ContainerViewModelFactory
import com.agentdroid.viewmodel.PermissionsViewModel
import com.agentdroid.viewmodel.WorkspaceFilesViewModel
import com.agentdroid.viewmodel.WorkspacesViewModel
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Phase2WorkspacesScreen(nav: NavHostController, factory: ContainerViewModelFactory) {
    val vm: WorkspacesViewModel = viewModel(factory = factory)
    val workspaces by vm.items.collectAsState()
    var editing by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleting by remember { mutableStateOf<WorkspaceEntity?>(null) }
    Column(Modifier.fillMaxSize().testTag("workspaces_screen")) {
        TopAppBar(
            title = { Text(stringResource(R.string.workspaces)) },
            actions = {
                IconButton({ editing = WorkspaceEntity(UUID.randomUUID().toString(), "", "", System.currentTimeMillis(), System.currentTimeMillis()) }) {
                    Icon(Icons.Default.Add, stringResource(R.string.create_workspace))
                }
            }
        )
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(workspaces, key = { it.id }) { workspace ->
                ElevatedCard({ nav.navigate("workspace/${workspace.id}") }, Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ListItem(
                        headlineContent = { Text(workspace.name) },
                        supportingContent = { Text(workspace.description.ifBlank { workspace.rootPath }) },
                        leadingContent = { Icon(Icons.Default.Folder, null) },
                        trailingContent = {
                            Row {
                                IconButton({ editing = workspace }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
                                IconButton({ deleting = workspace }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                            }
                        }
                    )
                }
            }
        }
    }
    editing?.let { workspace ->
        var name by remember(workspace.id) { mutableStateOf(workspace.name) }
        var description by remember(workspace.id) { mutableStateOf(workspace.description) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (workspace.name.isBlank()) stringResource(R.string.create_workspace) else stringResource(R.string.edit_workspace)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) })
                    OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.description)) })
                }
            },
            confirmButton = { Button({ if (name.isNotBlank()) { vm.save(workspace.copy(name = name.trim(), description = description, updatedAt = System.currentTimeMillis())); editing = null } }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    deleting?.let { workspace ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_workspace_title)) },
            text = { Text(stringResource(R.string.delete_workspace_body)) },
            confirmButton = { Button({ vm.delete(workspace.id); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
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
    var createKind by remember { mutableStateOf<String?>(null) }
    var moveTarget by remember { mutableStateOf<WorkspaceFileInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileInfo?>(null) }
    LaunchedEffect(workspaceId) { vm.openWorkspace(workspaceId) }

    if (editorPath != null) {
        FileEditorScreen(vm, editorPath!!, editorText)
        return
    }

    Column(Modifier.fillMaxSize().testTag("workspace_browser")) {
        TopAppBar(
            title = { Column { Text(workspace?.name ?: stringResource(R.string.workspace_scope)); if (path.isNotBlank()) Text(path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) } },
            navigationIcon = { IconButton({ if (path.isBlank()) nav.popBackStack() else vm.goUp() }) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton({ createKind = "file" }) { Icon(Icons.Default.NoteAdd, stringResource(R.string.create_file)) }
                IconButton({ createKind = "folder" }) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.create_folder)) }
                IconButton({ nav.navigate("changes/$workspaceId") }) { Icon(Icons.Default.Difference, stringResource(R.string.changes)) }
            }
        )
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        if (entries.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.empty_folder)) }
        else LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.path }) { item ->
                ListItem(
                    modifier = Modifier.clickable { if (item.directory) vm.openDirectory(item.path) else vm.openFile(item.path) }.testTag("workspace_item_${item.path}"),
                    headlineContent = { Text(item.name) },
                    supportingContent = { Text(if (item.directory) stringResource(R.string.folder_with_date, formatModified(item.modifiedAt)) else "${formatSize(item.size)} • ${formatModified(item.modifiedAt)}") },
                    leadingContent = { Icon(if (item.directory) Icons.Default.Folder else fileIcon(item.name), null) },
                    trailingContent = {
                        Row {
                            IconButton({ moveTarget = item }) { Icon(Icons.Default.DriveFileRenameOutline, stringResource(R.string.move_or_rename)) }
                            IconButton({ deleteTarget = item }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }

    createKind?.let { kind ->
        NameDialog(if (kind == "folder") stringResource(R.string.create_folder) else stringResource(R.string.create_file), stringResource(R.string.name), onDismiss = { createKind = null }) { name ->
            if (kind == "folder") vm.createFolder(name) else vm.createFile(name)
            createKind = null
        }
    }
    moveTarget?.let { item -> NameDialog(stringResource(R.string.move_rename_item, item.name), stringResource(R.string.destination), item.path, { moveTarget = null }) { destination -> vm.move(item.path, destination); moveTarget = null } }
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_item_title, item.name)) },
            text = { Text(stringResource(R.string.delete_item_body)) },
            confirmButton = { Button({ vm.delete(item.path); deleteTarget = null }, Modifier.testTag("workspace_delete_confirm")) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ deleteTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorScreen(vm: WorkspaceFilesViewModel, path: String, text: String) {
    val original by vm.originalText.collectAsState()
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val currentDirection = LocalLayoutDirection.current
    val direction = if (isCodeFile(path)) LayoutDirection.Ltr else currentDirection
    val lineNumbers = remember(text) { (1..maxOf(1, text.count { it == '\n' } + 1)).joinToString("\n") }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Column(Modifier.fillMaxSize().testTag("file_editor")) {
            TopAppBar(
                title = { Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall) },
                navigationIcon = { IconButton({ vm.editorPath.value = null }) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back_to_files)) } },
                actions = {
                    IconButton(vm::undo) { Icon(Icons.Default.Undo, stringResource(R.string.undo)) }
                    IconButton(vm::redo) { Icon(Icons.Default.Redo, stringResource(R.string.redo)) }
                    IconButton(vm::saveEditor, enabled = text != original, modifier = Modifier.testTag("file_save")) { Icon(Icons.Default.Save, stringResource(R.string.save)) }
                }
            )
            Row(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(vertical).padding(12.dp)) {
                Text(lineNumbers, Modifier.padding(end = 12.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(text, vm::updateEditor, Modifier.widthIn(min = 640.dp).testTag("file_editor_text"), textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceChangesScreen(nav: NavHostController, factory: ContainerViewModelFactory, workspaceId: String) {
    val vm: ChangeSetsViewModel = viewModel(factory = factory)
    val changes by vm.items.collectAsState()
    LaunchedEffect(workspaceId) { vm.setWorkspace(workspaceId) }
    val pending = changes.filter { it.status == ChangeSetStatus.PROPOSED }
    Column(Modifier.fillMaxSize().testTag("changes_screen")) {
        TopAppBar(title = { Text(stringResource(R.string.workspace_changes)) }, navigationIcon = { IconButton(nav::popBackStack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } })
        if (pending.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton({ pending.forEach { vm.accept(it.id) } }, Modifier.testTag("accept_all")) { Text(stringResource(R.string.accept_all)) }
            TextButton({ pending.forEach { vm.reject(it.id) } }, Modifier.testTag("reject_all")) { Text(stringResource(R.string.reject_all)) }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(changes, key = { it.id }) { changeSet ->
                ElevatedCard({ nav.navigate("diff/$workspaceId/${changeSet.id}") }, Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ListItem(headlineContent = { Text(stringResource(R.string.files_changed, changeSet.files.size, changeSet.addedLines, changeSet.removedLines)) }, supportingContent = { Text(changeSet.status.name) }, leadingContent = { Icon(Icons.Default.Difference, null) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(nav: NavHostController, factory: ContainerViewModelFactory, workspaceId: String, changeSetId: String) {
    val vm: ChangeSetsViewModel = viewModel(factory = factory)
    val changeSet by vm.selected.collectAsState()
    var edit by remember { mutableStateOf<FileChange?>(null) }
    LaunchedEffect(workspaceId, changeSetId) { vm.setWorkspace(workspaceId); vm.select(changeSetId) }
    Column(Modifier.fillMaxSize().testTag("diff_screen")) {
        TopAppBar(title = { Text(stringResource(R.string.diff)) }, navigationIcon = { IconButton(nav::popBackStack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } })
        val current = changeSet
        if (current == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        else {
            Text(stringResource(R.string.files_changed, current.files.size, current.addedLines, current.removedLines), Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(current.files, key = { it.path + (it.destinationPath ?: "") }) { file ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.destinationPath?.let { "${file.path} → $it" } ?: file.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                            Text(file.changeType.name, style = MaterialTheme.typography.labelSmall)
                            if (file.diff.isNotBlank()) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(file.diff, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                if (current.status == ChangeSetStatus.PROPOSED) {
                                    TextButton({ vm.accept(current.id) }, Modifier.testTag("diff_accept")) { Text(stringResource(R.string.accept)) }
                                    TextButton({ vm.reject(current.id) }, Modifier.testTag("diff_reject")) { Text(stringResource(R.string.reject)) }
                                    if (file.afterContent != null) TextButton({ edit = file }) { Text(stringResource(R.string.edit)) }
                                }
                                if (current.status == ChangeSetStatus.APPLIED) TextButton({ vm.revert(current.id) }, Modifier.testTag("diff_revert")) { Text(stringResource(R.string.revert)) }
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
            title = { Text(stringResource(R.string.edit_proposed, file.path)) },
            text = { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), minLines = 10, textStyle = TextStyle(fontFamily = FontFamily.Monospace)) } },
            confirmButton = { Button({ vm.edit(changeSetId, file.path, value); edit = null }) { Text(stringResource(R.string.save_proposal)) } },
            dismissButton = { TextButton({ edit = null }) { Text(stringResource(R.string.cancel)) } }
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
        TopAppBar(title = { Text(stringResource(R.string.agent_permissions)) }, navigationIcon = { IconButton(nav::popBackStack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } })
        LazyColumn(Modifier.fillMaxSize()) {
            item { Text(stringResource(R.string.always_rules), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            if (rules.isEmpty()) item { Text(stringResource(R.string.no_permission_rules), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rules, key = { it.id }) { rule ->
                ListItem(
                    headlineContent = { Text("${rule.toolName}: ${rule.decision.name}") },
                    supportingContent = { Text(rule.workspaceId?.let { stringResource(R.string.workspace_id_label, it) } ?: stringResource(R.string.all_workspaces)) },
                    trailingContent = { IconButton({ vm.deleteRule(rule.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete_rule)) } }
                )
            }
            item { HorizontalDivider(); Text(stringResource(R.string.recent_tool_audit), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            items(audit.take(50), key = { it.id }) { entry -> ListItem(headlineContent = { Text("${entry.status} ${entry.toolName}") }, supportingContent = { Text("${entry.durationMs} ms • ${entry.resultSummary}") }, leadingContent = { Icon(Icons.Default.ReceiptLong, null) }) }
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
        confirmButton = { Button({ if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private fun formatSize(size: Long): String = when { size < 1024 -> "$size B"; size < 1024 * 1024 -> "${size / 1024} KB"; else -> String.format("%.1f MB", size.toDouble() / (1024 * 1024)) }
private fun formatModified(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
private fun fileIcon(name: String) = when (name.substringAfterLast('.', "").lowercase()) { "kt", "java", "js", "ts", "tsx", "jsx", "py", "go", "rs", "c", "cpp", "h" -> Icons.Default.Code; "md", "txt", "json", "xml", "yaml", "yml", "toml", "gradle", "kts" -> Icons.Default.Description; else -> Icons.Default.InsertDriveFile }
private fun isCodeFile(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in setOf("kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "go", "rs", "c", "cpp", "h", "gradle", "xml", "json", "yaml", "yml", "toml")
