package com.agentdroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.agentdroid.AgentDroidApplication
import com.agentdroid.R
import com.agentdroid.core.git.GitBranchInfo
import com.agentdroid.core.git.GitStatus
import com.agentdroid.core.git.validateCommitMessage
import com.agentdroid.core.runtime.ProcessSnapshot
import com.agentdroid.core.runtime.ProcessStatus
import com.agentdroid.core.runtime.RuntimeComponent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(nav: NavHostController, workspaceId: String, initialCwd: String = ".") {
    val context = LocalContext.current
    val container = (context.applicationContext as AgentDroidApplication).container
    val manager = container.terminalManager
    val allStates by manager.sessions.collectAsState()
    val states = allStates.filter { it.workspaceId == workspaceId }
    var selectedId by rememberSaveable(workspaceId) { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var ctrl by remember { mutableStateOf(false) }

    LaunchedEffect(workspaceId, initialCwd) {
        val current = states.firstOrNull { it.sessionId == selectedId } ?: states.lastOrNull()
        if (current != null) selectedId = current.sessionId
        else selectedId = manager.create(workspaceId, initialCwd.ifBlank { "." }).state.value.sessionId
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize().testTag("terminal_screen")) {
            TopAppBar(
                title = { Text(stringResource(R.string.terminal)) },
                navigationIcon = { IconButton(nav::popBackStack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton({ selectedId = manager.create(workspaceId, initialCwd.ifBlank { "." }).state.value.sessionId }) { Icon(Icons.Default.Add, stringResource(R.string.new_terminal)) }
                    selectedId?.let { id ->
                        IconButton({ renameId = id }) { Icon(Icons.Default.Edit, stringResource(R.string.rename_session)) }
                        IconButton({
                            manager.remove(id)
                            selectedId = manager.sessions.value.lastOrNull { it.workspaceId == workspaceId && it.sessionId != id }?.sessionId
                        }) { Icon(Icons.Default.Close, stringResource(R.string.close_session)) }
                    }
                }
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                states.forEach { state ->
                    FilterChip(
                        selected = selectedId == state.sessionId,
                        onClick = { selectedId = state.sessionId },
                        label = { Text(state.title) },
                        leadingIcon = { Icon(if (state.running) Icons.Default.Terminal else Icons.Default.StopCircle, null, Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("terminal_session_${state.sessionId}")
                    )
                }
            }
            val sessionId = selectedId
            val native = sessionId?.let(manager::nativeSession)
            if (sessionId == null || native == null) {
                Box(Modifier.weight(1f).fillMaxWidth()) { Text(stringResource(R.string.terminal_no_session), Modifier.padding(16.dp)) }
            } else {
                key(sessionId) {
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("terminal_view"),
                        factory = { ctx -> terminalView(ctx, native) },
                        update = { view -> manager.nativeSession(sessionId)?.let { if (view.mTermSession !== it) view.attachSession(it) } }
                    )
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, label = { Text(stringResource(R.string.terminal_ctrl)) })
                    AssistChip(onClick = { manager.get(sessionId)?.write(if (ctrl) "\u0003" else "c"); ctrl = false }, label = { Text("C") })
                    AssistChip(onClick = { manager.get(sessionId)?.write("\t") }, label = { Text(stringResource(R.string.terminal_tab)) })
                    AssistChip(onClick = { manager.get(sessionId)?.write("\u001B") }, label = { Text(stringResource(R.string.terminal_esc)) })
                    AssistChip(onClick = { manager.clear(sessionId) }, label = { Text(stringResource(R.string.clear_terminal)) })
                    AssistChip(onClick = {
                        val text = manager.get(sessionId)?.transcript().orEmpty()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
                    }, label = { Text(stringResource(R.string.copy_terminal)) })
                    AssistChip(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrEmpty()) manager.get(sessionId)?.write(text)
                    }, label = { Text(stringResource(R.string.paste_terminal)) })
                }
            }
        }
    }

    renameId?.let { id ->
        var title by remember(id) { mutableStateOf(manager.get(id)?.state?.value?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text(stringResource(R.string.rename_session)) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { Button({ if (manager.rename(id, title)) renameId = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ renameId = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private fun terminalView(context: Context, session: TerminalSession): TerminalView {
    lateinit var view: TerminalView
    val client = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val size = (18f * scale).roundToInt().coerceIn(10, 42)
            view.setTextSize(size)
            return size / 18f
        }
        override fun onSingleTapUp(e: MotionEvent) {
            view.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = view.hasFocus()
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
        override fun onEmulatorSet() = Unit
        override fun logError(tag: String, message: String) = Unit
        override fun logWarn(tag: String, message: String) = Unit
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
        override fun logStackTrace(tag: String, e: Exception) = Unit
    }
    view = TerminalView(context, null)
    view.setTerminalViewClient(client)
    view.setTextSize(18)
    view.isFocusableInTouchMode = true
    view.attachSession(session)
    return view
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitWorkspaceScreen(nav: NavHostController, workspaceId: String) {
    val context = LocalContext.current
    val container = (context.applicationContext as AgentDroidApplication).container
    val engine = container.gitEngine
    val root = container.workspaceRoot(workspaceId)
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var branches by remember { mutableStateOf<List<GitBranchInfo>>(emptyList()) }
    var initialized by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var diff by remember { mutableStateOf<String?>(null) }
    var restorePath by remember { mutableStateOf<String?>(null) }
    var commitMessage by remember { mutableStateOf("") }
    var confirmCommit by remember { mutableStateOf(false) }

    fun refresh() = scope.launch {
        initialized = engine.isRepository(root)
        if (initialized) {
            status = engine.status(root).onFailure { error = it.message }.getOrNull()
            branches = engine.branches(root).getOrDefault(emptyList())
        } else { status = null; branches = emptyList() }
    }
    LaunchedEffect(workspaceId) { refresh() }

    Column(Modifier.fillMaxSize().testTag("git_screen")) {
        TopAppBar(title = { Text(stringResource(R.string.git)) }, navigationIcon = { IconButton(nav::popBackStack) { Icon(Icons.Default.ArrowBack, null) } }, actions = { IconButton({ refresh() }) { Icon(Icons.Default.Refresh, stringResource(R.string.runtime_refresh)) } })
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        if (!initialized) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.git_not_initialized))
                Button({ scope.launch { engine.init(root).onFailure { error = it.message }; refresh() } }, Modifier.testTag("git_init")) { Text(stringResource(R.string.git_initialize)) }
            }
            return@Column
        }
        val current = status
        if (current != null) {
            Text(stringResource(R.string.git_branch, current.branch ?: "HEAD"), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
            Text(if (current.clean) stringResource(R.string.git_clean) else stringResource(R.string.git_changes), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (branches.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { branches.forEach { b -> AssistChip(onClick = {}, label = { Text(if (b.current) "● ${b.name}" else b.name) }) } }
            val staged = current.staged.toSet()
            val rows = buildList {
                current.staged.forEach { add(GitUiFile(it, stringResourceSafe(context, R.string.git_staged), true, false)) }
                current.modified.filterNot(staged::contains).forEach { add(GitUiFile(it, stringResourceSafe(context, R.string.git_modified), false, true)) }
                current.deleted.filterNot(staged::contains).forEach { add(GitUiFile(it, stringResourceSafe(context, R.string.git_deleted), false, true)) }
                current.untracked.filterNot(staged::contains).forEach { add(GitUiFile(it, stringResourceSafe(context, R.string.git_untracked), false, false)) }
                current.conflicting.forEach { if (none { row -> row.path == it }) add(GitUiFile(it, stringResourceSafe(context, R.string.git_conflicts), false, false)) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.path + it.staged }) { item ->
                    ListItem(
                        headlineContent = { Text(item.path, fontFamily = FontFamily.Monospace) },
                        supportingContent = { Text(item.kind) },
                        leadingContent = { Icon(if (item.staged) Icons.Default.CheckCircle else Icons.Default.Description, null) },
                        trailingContent = {
                            Row {
                                IconButton({ scope.launch { diff = engine.diff(root, item.path, item.staged).getOrNull()?.patch ?: "" } }) { Icon(Icons.Default.Difference, stringResource(R.string.git_view_diff)) }
                                if (item.staged) IconButton({ scope.launch { engine.restore(root, listOf(item.path), staged = true).onFailure { error = it.message }; refresh() } }) { Icon(Icons.Default.RemoveDone, stringResource(R.string.git_unstage)) }
                                else IconButton({ scope.launch { engine.add(root, listOf(item.path)).onFailure { error = it.message }; refresh() } }) { Icon(Icons.Default.AddTask, stringResource(R.string.git_stage)) }
                                if (item.restorable) IconButton({ restorePath = item.path }) { Icon(Icons.Default.Restore, stringResource(R.string.git_restore)) }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(commitMessage, { commitMessage = it }, Modifier.fillMaxWidth().testTag("git_commit_message"), label = { Text(stringResource(R.string.git_commit_message)) }, maxLines = 4)
                Button(onClick = { if (runCatching { validateCommitMessage(commitMessage) }.isSuccess) confirmCommit = true }, enabled = current.staged.isNotEmpty(), modifier = Modifier.testTag("git_commit")) { Text(stringResource(R.string.git_commit)) }
                if (current.staged.isEmpty()) Text(stringResource(R.string.git_no_staged), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    diff?.let { patch -> AlertDialog(onDismissRequest = { diff = null }, title = { Text(stringResource(R.string.git_diff_title)) }, text = { Text(patch.ifBlank { "(empty diff)" }, Modifier.fillMaxWidth().heightIn(max = 500.dp).horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace) }, confirmButton = { TextButton({ diff = null }) { Text(stringResource(R.string.close)) } }) }
    restorePath?.let { path -> AlertDialog(onDismissRequest = { restorePath = null }, title = { Text(stringResource(R.string.git_restore_title)) }, text = { Text(stringResource(R.string.git_restore_body)) }, confirmButton = { Button({ scope.launch { engine.restore(root, listOf(path), staged = false).onFailure { error = it.message }; restorePath = null; refresh() } }, Modifier.testTag("git_restore_confirm")) { Text(stringResource(R.string.git_restore)) } }, dismissButton = { TextButton({ restorePath = null }) { Text(stringResource(R.string.cancel)) } }) }
    if (confirmCommit) AlertDialog(onDismissRequest = { confirmCommit = false }, title = { Text(stringResource(R.string.git_commit_confirm)) }, text = { Text(commitMessage) }, confirmButton = { Button({ scope.launch { engine.commit(root, commitMessage).onSuccess { commitMessage = "" }.onFailure { error = it.message }; confirmCommit = false; refresh() } }, Modifier.testTag("git_commit_confirm")) { Text(stringResource(R.string.git_commit_confirm)) } }, dismissButton = { TextButton({ confirmCommit = false }) { Text(stringResource(R.string.cancel)) } })
}

private data class GitUiFile(val path: String, val kind: String, val staged: Boolean, val restorable: Boolean)
private fun stringResourceSafe(context: Context, id: Int): String = context.getString(id)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeScreen(nav: NavHostController) {
    val context = LocalContext.current
    val container = (context.applicationContext as AgentDroidApplication).container
    val scope = rememberCoroutineScope()
    var components by remember { mutableStateOf<List<RuntimeComponent>>(emptyList()) }
    var processes by remember { mutableStateOf<List<ProcessSnapshot>>(emptyList()) }
    fun refresh() = scope.launch { components = container.runtimeDiscovery.list(); processes = container.processManager.list() }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxSize().testTag("runtime_screen")) {
        TopAppBar(title = { Text(stringResource(R.string.runtime)) }, navigationIcon = { IconButton(nav::popBackStack) { Icon(Icons.Default.ArrowBack, null) } }, actions = { IconButton({ refresh() }) { Icon(Icons.Default.Refresh, stringResource(R.string.runtime_refresh)) } })
        LazyColumn(Modifier.fillMaxSize()) {
            items(components, key = { it.id }) { item ->
                ListItem(headlineContent = { Text(item.label) }, supportingContent = { Text(item.version ?: if (item.available) stringResource(R.string.runtime_available) else stringResource(R.string.runtime_not_installed)) }, leadingContent = { Icon(if (item.available) Icons.Default.CheckCircle else Icons.Default.Cancel, null) })
            }
            item { HorizontalDivider(); Text(stringResource(R.string.processes), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            items(processes, key = { it.processId }) { process ->
                ListItem(headlineContent = { Text(process.command, fontFamily = FontFamily.Monospace) }, supportingContent = { Text("${process.status} • ${process.cwd}") }, leadingContent = { Icon(if (process.status == ProcessStatus.RUNNING) Icons.Default.PlayCircle else Icons.Default.StopCircle, null) })
            }
        }
    }
}
