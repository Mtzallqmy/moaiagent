package com.agentdroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agentdroid.R
import com.agentdroid.integration.AppBackupManager
import kotlinx.coroutines.launch

@Composable
fun BackupRestoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { AppBackupManager(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = runCatching {
                val summary = context.contentResolver.openOutputStream(uri, "w")!!.use { manager.exportTo(it) }
                context.getString(R.string.backup_exported, summary.workspaces, summary.conversations, summary.workspaceFiles)
            }.getOrElse { context.getString(R.string.backup_failed, it.message ?: it.javaClass.simpleName) }
            busy = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = runCatching {
                val summary = context.contentResolver.openInputStream(uri)!!.use { manager.importFrom(it) }
                context.getString(R.string.backup_imported, summary.workspaces, summary.conversations, summary.workspaceFiles)
            }.getOrElse { context.getString(R.string.backup_failed, it.message ?: it.javaClass.simpleName) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.backup_title)) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }
        )
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.backup_safe_note), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { exportLauncher.launch("AgentDroid-backup.zip") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.backup_import)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
