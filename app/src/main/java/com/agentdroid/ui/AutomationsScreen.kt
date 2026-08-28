package com.agentdroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agentdroid.AgentDroidApplication
import com.agentdroid.R
import com.agentdroid.automation.AutomationManager
import com.agentdroid.automation.WorkspaceAutomation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun AutomationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AgentDroidApplication
    val manager = remember { AutomationManager(context) }
    val scheduled by manager.automations.collectAsState()
    val scope = rememberCoroutineScope()
    var workspaces by remember { mutableStateOf(emptyList<com.agentdroid.data.database.WorkspaceEntity>()) }
    var conversations by remember { mutableStateOf(emptyList<com.agentdroid.data.database.ConversationEntity>()) }
    var title by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var workspaceId by remember { mutableStateOf<String?>(null) }
    var conversationId by remember { mutableStateOf<String?>(null) }
    var repeat by remember { mutableStateOf("") }
    var delay by remember { mutableStateOf("0") }
    var requireNetwork by remember { mutableStateOf(false) }
    var requireCharging by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        workspaces = app.container.workspaces.observeAll().first()
        conversations = app.container.conversations.observeIncludingArchived().first().filterNot { it.archived }
        workspaceId = workspaceId ?: workspaces.firstOrNull()?.id
        conversationId = conversationId ?: conversations.firstOrNull()?.id
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.automations)) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Text(stringResource(R.string.automation_android_note), style = MaterialTheme.typography.bodySmall) }
            item { Text(stringResource(R.string.automation_new), style = MaterialTheme.typography.titleLarge) }
            item { OutlinedTextField(title, { title = it.take(240) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.title)) }) }
            item { OutlinedTextField(goal, { goal = it.take(8_000) }, Modifier.fillMaxWidth(), minLines = 3, label = { Text(stringResource(R.string.automation_goal)) }) }
            item {
                Text(stringResource(R.string.automation_workspace), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    workspaces.forEach { workspace ->
                        FilterChip(workspaceId == workspace.id, { workspaceId = workspace.id }, label = { Text(workspace.name) })
                    }
                }
            }
            item {
                Text(stringResource(R.string.automation_conversation), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    conversations.take(20).forEach { conversation ->
                        FilterChip(conversationId == conversation.id, { conversationId = conversation.id }, label = { Text(conversation.title) })
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(repeat, { repeat = it.filter(Char::isDigit).take(8) }, Modifier.weight(1f), label = { Text(stringResource(R.string.automation_repeat_minutes)) })
                    OutlinedTextField(delay, { delay = it.filter(Char::isDigit).take(8) }, Modifier.weight(1f), label = { Text(stringResource(R.string.automation_initial_delay)) })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(requireNetwork, { requireNetwork = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.automation_network)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(requireCharging, { requireCharging = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.automation_charging)) }
            }
            item {
                Button(
                    onClick = {
                        val workspace = workspaceId
                        val conversation = conversationId
                        val repeatMinutes = repeat.toLongOrNull()
                        val delayMinutes = delay.toLongOrNull() ?: 0L
                        if (title.isBlank() || goal.isBlank() || workspace == null || conversation == null || (repeatMinutes != null && repeatMinutes < 15)) {
                            error = context.getString(R.string.invalid_form)
                        } else {
                            runCatching {
                                manager.schedule(
                                    WorkspaceAutomation(
                                        id = UUID.randomUUID().toString().replace("-", ""),
                                        title = title.trim(), goal = goal.trim(), workspaceId = workspace,
                                        conversationId = conversation, repeatMinutes = repeatMinutes,
                                        initialDelayMinutes = delayMinutes, requiresNetwork = requireNetwork,
                                        requiresCharging = requireCharging
                                    )
                                )
                            }.onSuccess {
                                title = ""; goal = ""; repeat = ""; delay = "0"; error = null
                            }.onFailure { error = it.message }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.automation_schedule)) }
            }
            error?.let { value -> item { Text(value, color = MaterialTheme.colorScheme.error) } }
            if (scheduled.isEmpty()) item { Text(stringResource(R.string.automation_empty)) }
            items(scheduled, key = { it.id }) { spec ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(spec.title, style = MaterialTheme.typography.titleMedium)
                        Text(spec.goal, maxLines = 3)
                        Text(if (spec.repeatMinutes == null) stringResource(R.string.automation_once) else stringResource(R.string.automation_every, spec.repeatMinutes))
                        TextButton(onClick = { manager.cancel(spec.id) }) { Text(stringResource(R.string.automation_cancel)) }
                    }
                }
            }
        }
    }
}
