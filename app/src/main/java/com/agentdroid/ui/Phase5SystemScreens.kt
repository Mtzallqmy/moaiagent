package com.agentdroid.ui

import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.agentdroid.AgentDroidApplication
import com.agentdroid.R
import com.agentdroid.core.localai.LocalModelDescriptor
import com.agentdroid.core.mcp.McpConnectionState
import com.agentdroid.core.mcp.McpConnectionStatus
import com.agentdroid.core.runtime.RuntimePackCapabilityState
import com.agentdroid.core.runtime.RuntimePackSourceKind
import com.agentdroid.core.runtime.RuntimePackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun Phase5LocalModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AgentDroidApplication
    val manager = app.container.localModelManager
    val models by manager.models.collectAsState()
    val loaded by manager.loadedModelId.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val importedText = stringResource(R.string.model_imported)
    val importFailed = stringResource(R.string.model_import_failed, "%s")
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val fileName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: "model.gguf"
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { source ->
                    manager.importModel(fileName.substringBeforeLast('.').ifBlank { fileName }, fileName, source).getOrThrow()
                } ?: error("Could not open model file")
            }
            message = result.fold({ importedText }, { importFailed.format(it.message ?: "unknown") })
        }
    }

    Phase5Scaffold(stringResource(R.string.local_models), onBack) {
        item {
            Button(onClick = { launcher.launch(arrayOf("application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.import_model))
            }
        }
        message?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (models.isEmpty()) item { Text(stringResource(R.string.no_local_models), Modifier.padding(vertical = 24.dp)) }
        items(models, key = { it.id }) { model ->
            LocalModelCard(model, loaded == model.id,
                onLoad = { scope.launch { message = manager.load(model.id).fold({ stringResourceSafe(context, R.string.operation_success) }, { it.message }) } },
                onUnload = { scope.launch { manager.unload(model.id); message = null } },
                onDefault = { scope.launch { manager.setDefault(model.id) } },
                onDelete = { scope.launch { manager.delete(model.id).onFailure { message = it.message } } })
        }
    }
}

@Composable
private fun LocalModelCard(model: LocalModelDescriptor, loaded: Boolean, onLoad: () -> Unit, onUnload: () -> Unit, onDefault: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                if (model.isDefault) AssistChip(onClick = {}, label = { Text(stringResource(R.string.default_model)) })
            }
            Text(model.fileName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.backend_label, model.backend.name))
            Text(stringResource(R.string.size_label, Formatter.formatShortFileSize(context, model.sizeBytes)))
            model.metadata.contextSize?.let { Text(stringResource(R.string.model_context, it)) }
            Text(if (model.compatibility.supported) stringResource(R.string.supported) else stringResource(R.string.unsupported), color = if (model.compatibility.supported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            model.compatibility.reason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!model.metadata.supportsToolCalling) Text(stringResource(R.string.tool_calling_not_supported), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (loaded) Button(onUnload) { Text(stringResource(R.string.unload_model)) }
                else Button(onLoad, enabled = model.compatibility.supported) { Text(stringResource(R.string.load_model)) }
                OutlinedButton(onDefault) { Text(stringResource(R.string.set_default_model)) }
                IconButton(onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            }
        }
    }
}

@Composable
fun Phase5RuntimePacksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AgentDroidApplication
    val controller = app.runtimePacks
    val localModels by app.container.localModelManager.models.collectAsState()
    val scope = rememberCoroutineScope()
    var states by remember { mutableStateOf<List<RuntimePackCapabilityState>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun refresh() { states = controller.list() }
    LaunchedEffect(Unit) { refresh() }
    val python = states.firstOrNull { it.state.manifest.id == "python" }?.agentExecutable == true
    val node = states.firstOrNull { it.state.manifest.id == "node" }?.agentExecutable == true
    val git = states.firstOrNull { it.state.manifest.id == "git" }?.agentExecutable == true
    val shell = states.firstOrNull { it.state.manifest.id == "base-shell" }?.agentExecutable == true
    val local = localModels.any { it.compatibility.supported }
    val offlineReady = local && python && git && shell

    Phase5Scaffold(stringResource(R.string.runtime_packs), onBack) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (offlineReady) stringResource(R.string.offline_ready) else stringResource(R.string.offline_not_ready), style = MaterialTheme.typography.titleMedium)
                CapabilityLine(stringResource(R.string.offline_local_model), local)
                CapabilityLine(stringResource(R.string.offline_python), python)
                CapabilityLine(stringResource(R.string.offline_node), node)
                CapabilityLine(stringResource(R.string.offline_git), git)
            } }
        }
        message?.let { item { Text(it) } }
        items(states, key = { it.state.manifest.id }) { capability ->
            val state = capability.state
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(state.manifest.displayName, style = MaterialTheme.typography.titleMedium)
                Text(state.manifest.version, fontFamily = FontFamily.Monospace)
                Text(state.status.name)
                Text(if (capability.agentExecutable) stringResource(R.string.runtime_agent_executable) else stringResource(R.string.runtime_component_only), color = if (capability.agentExecutable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.verifiedChecksum != null) Text(stringResource(R.string.runtime_checksum_verified), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.manifest.sourceKind == RuntimePackSourceKind.TRUSTED_DOWNLOAD && state.status == RuntimePackStatus.NOT_INSTALLED) {
                        Button({ scope.launch { controller.install(state.manifest.id).fold({ message = stringResourceSafe(context, R.string.operation_success) }, { message = it.message }); refresh() } }) { Text(stringResource(R.string.install_runtime)) }
                    }
                    if (state.manifest.sourceKind == RuntimePackSourceKind.TRUSTED_DOWNLOAD && state.status == RuntimePackStatus.INSTALLED) {
                        OutlinedButton({ scope.launch { controller.uninstall(state.manifest.id); refresh() } }) { Text(stringResource(R.string.uninstall_runtime)) }
                    }
                }
            } }
        }
    }
}

@Composable
private fun CapabilityLine(label: String, available: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (available) Icons.Default.CheckCircle else Icons.Default.Cancel, null); Spacer(Modifier.width(8.dp)); Text(label) }
}

@Composable
fun Phase5McpServersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AgentDroidApplication
    val controller = app.mcpController
    val states by controller.states.collectAsState()
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<McpConnectionState?>(null) }
    var creating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Phase5Scaffold(stringResource(R.string.mcp_servers), onBack) {
        item { Text(stringResource(R.string.mcp_external_warning), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button({ creating = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_mcp_server)) } }
        message?.let { item { Text(it) } }
        if (states.isEmpty()) item { Text(stringResource(R.string.mcp_no_servers), Modifier.padding(vertical = 24.dp)) }
        items(states, key = { it.config.id }) { state -> McpServerCard(state,
            onEdit = { editor = state },
            onConnect = { scope.launch { controller.connect(state.config.id).onFailure { message = it.message } } },
            onDisconnect = { scope.launch { controller.disconnect(state.config.id).onFailure { message = it.message } } },
            onTest = { scope.launch { message = controller.test(state.config.id).fold({ "${it.name} · ${it.protocolVersion}" }, { it.message }) } },
            onToggle = { enabled -> scope.launch { controller.setEnabled(state.config.id, enabled).onFailure { message = it.message } } },
            onDelete = { scope.launch { controller.delete(state.config.id).onFailure { message = it.message } } }) }
    }
    if (creating || editor != null) McpServerEditor(editor?.config?.name.orEmpty(), editor?.config?.endpoint.orEmpty(), editor?.config?.enabled ?: true,
        onDismiss = { creating = false; editor = null },
        onSave = { name, endpoint, enabled, credential -> scope.launch {
            controller.save(editor?.config?.id, name, endpoint, enabled, credential.ifBlank { null }).fold(
                { creating = false; editor = null }, { message = it.message })
        } })
}

@Composable
private fun McpServerCard(state: McpConnectionState, onEdit: () -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit, onTest: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(state.config.name, style = MaterialTheme.typography.titleMedium); Switch(state.config.enabled, onToggle) }
        Text(state.config.endpoint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text(state.status.name)
        state.identity?.let { Text(stringResource(R.string.mcp_protocol, it.protocolVersion)) }
        Text(stringResource(R.string.mcp_tools, state.tools.size)); state.tools.take(8).forEach { Text("• ${it.name}", fontFamily = FontFamily.Monospace) }
        Text(stringResource(R.string.mcp_resources, state.resources.size)); state.resources.take(8).forEach { Text("• ${it.name}") }
        state.error?.let { Text(stringResource(R.string.mcp_error, it), color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.status == McpConnectionStatus.CONNECTED) Button(onDisconnect) { Text(stringResource(R.string.mcp_disconnect)) }
            else Button(onConnect, enabled = state.config.enabled) { Text(stringResource(R.string.mcp_connect)) }
            OutlinedButton(onTest, enabled = state.config.enabled) { Text(stringResource(R.string.mcp_test)) }
            IconButton(onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
            IconButton(onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    } }
}

@Composable
private fun McpServerEditor(initialName: String, initialEndpoint: String, initialEnabled: Boolean, onDismiss: () -> Unit, onSave: (String, String, Boolean, String) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var endpoint by remember(initialEndpoint) { mutableStateOf(initialEndpoint) }
    var credential by remember { mutableStateOf("") }
    var enabled by remember(initialEnabled) { mutableStateOf(initialEnabled) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.add_mcp_server)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.mcp_server_name)) })
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.mcp_endpoint)) })
        OutlinedTextField(credential, { credential = it }, label = { Text(stringResource(R.string.mcp_credential)) })
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(enabled, { enabled = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.enabled)) }
    } }, confirmButton = { Button({ if (name.isNotBlank() && endpoint.isNotBlank()) onSave(name, endpoint, enabled, credential) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun Phase5StorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    suspend fun refresh() { sizes = withContext(Dispatchers.IO) {
        mapOf(
            R.string.storage_workspaces to safeTreeSize(File(context.filesDir, "workspaces")),
            R.string.storage_models to safeTreeSize(File(context.filesDir, "local-models")),
            R.string.storage_runtimes to safeTreeSize(File(context.filesDir, "runtime-packs")),
            R.string.storage_cache to safeTreeSize(context.cacheDir),
            R.string.storage_database to safeTreeSize(context.getDatabasePath("agentdroid.db").parentFile)
        )
    } }
    LaunchedEffect(Unit) { refresh() }
    Phase5Scaffold(stringResource(R.string.storage), onBack) {
        items(sizes.entries.toList(), key = { it.key }) { entry -> ListItem(headlineContent = { Text(stringResource(entry.key)) }, supportingContent = { Text(Formatter.formatShortFileSize(context, entry.value)) }) }
        item { Button({ scope.launch(Dispatchers.IO) { context.cacheDir.deleteRecursively(); context.cacheDir.mkdirs(); withContext(Dispatchers.Main) { refresh() } } }) { Text(stringResource(R.string.clear_cache_action)) } }
        item { OutlinedButton({ scope.launch { refresh() } }) { Text(stringResource(R.string.storage_refresh)) } }
    }
}

@Composable
private fun Phase5Scaffold(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.phase5_back)) } })
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

private fun safeTreeSize(root: File?): Long {
    if (root == null || !root.exists()) return 0
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return 0
    var total = 0L
    canonicalRoot.walkTopDown().forEach { file ->
        if (file.isFile) {
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            if (canonical != null && (canonical == canonicalRoot || canonical.path.startsWith(canonicalRoot.path + File.separator))) total += file.length()
        }
    }
    return total
}

private fun stringResourceSafe(context: android.content.Context, id: Int): String = context.getString(id)
