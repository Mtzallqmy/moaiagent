package com.agentdroid.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.AgentStepStatus
import com.agentdroid.core.agent.PermissionScope
import com.agentdroid.core.model.ChatPhase
import com.agentdroid.core.model.MessageStatus
import com.agentdroid.data.database.MessageEntity
import com.agentdroid.viewmodel.ChatViewModel
import com.agentdroid.viewmodel.ContainerViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Phase2ChatScreen(nav: NavHostController, factory: ContainerViewModelFactory, conversationId: String? = null) {
    val vm: ChatViewModel = viewModel(factory = factory)
    LaunchedEffect(conversationId) {
        if (conversationId != null) vm.openConversation(conversationId)
        else if (vm.selectedConversationId.value == null) vm.newConversation()
    }
    val providers by vm.providers.collectAsState()
    val selectedProvider by vm.selectedProviderId.collectAsState()
    val selectedModel by vm.selectedModelId.collectAsState()
    val conversation by vm.selectedConversationId.collectAsState()
    val phase by vm.phase.collectAsState()
    val error by vm.error.collectAsState()
    val messages = conversation?.let { vm.messages(it).collectAsState(initial = emptyList()).value }.orEmpty()
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().testTag("phase2_chat")) {
        TopAppBar(
            title = { Text("AgentDroid") },
            actions = { IconButton({ vm.newConversation() }) { Icon(Icons.Default.Add, "New conversation") } }
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                AssistChip(
                    onClick = { providerMenu = true },
                    label = { Text(providers.firstOrNull { it.id == selectedProvider }?.name ?: "Provider") },
                    leadingIcon = { Icon(Icons.Default.Cloud, null) }
                )
                DropdownMenu(providerMenu, { providerMenu = false }) {
                    providers.forEach { provider -> DropdownMenuItem({ Text(provider.name) }, { vm.chooseProvider(provider.id); providerMenu = false }) }
                }
            }
            Box {
                AssistChip(onClick = { modelMenu = true }, label = { Text(selectedModel ?: "Model") })
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    providers.firstOrNull { it.id == selectedProvider }?.modelId?.let { model ->
                        DropdownMenuItem({ Text(model) }, { vm.chooseModel(model); modelMenu = false })
                    }
                }
            }
        }
        AgentModeAndContextBar(vm)
        AgentExecutionPanel(nav, vm)
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text("Start a conversation") }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages, key = { it.id }) { message -> Phase2MessageBubble(message, vm) }
            }
        }
        error?.let { Text(it.userMessage, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).testTag("agent_composer"),
                placeholder = { Text("Message AgentDroid…") },
                maxLines = 6
            )
            Spacer(Modifier.width(8.dp))
            if (phase == ChatPhase.STREAMING || phase == ChatPhase.SUBMITTING) {
                IconButton(vm::stop, Modifier.testTag("agent_stop")) { Icon(Icons.Default.Stop, "Stop") }
            } else {
                IconButton(
                    onClick = { if (input.isNotBlank()) { vm.send(input); input = "" } },
                    modifier = Modifier.testTag("agent_send")
                ) { Icon(Icons.Default.Send, "Send") }
            }
        }
    }
    AgentPermissionDialog(vm)
}

@Composable
private fun Phase2MessageBubble(message: MessageEntity, vm: ChatViewModel) {
    val user = message.role == "USER"
    Surface(
        color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (user) "You" else "AgentDroid", style = MaterialTheme.typography.labelMedium)
            Text(message.content)
            if (message.status == MessageStatus.FAILED.name && !user) TextButton(vm::retry) { Text("Retry") }
        }
    }
}

@Composable
fun AgentModeAndContextBar(vm: ChatViewModel) {
    val mode by vm.mode.collectAsState()
    val supportsTools by vm.providerSupportsTools.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    val selectedWorkspace by vm.selectedWorkspaceId.collectAsState()
    val skills by vm.skills.collectAsState()
    val activeSkills by vm.activeConversationSkillIds.collectAsState()
    var workspaceMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AgentMode.values().forEach { value ->
                FilterChip(
                    selected = mode == value,
                    onClick = { vm.chooseMode(value) },
                    enabled = value == AgentMode.CHAT || supportsTools,
                    label = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier.testTag("mode_${value.name.lowercase()}")
                )
            }
        }
        if (!supportsTools) Text("Plan and Agent require provider tool-calling support.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            AssistChip(
                onClick = { workspaceMenu = true },
                label = { Text(workspaces.firstOrNull { it.id == selectedWorkspace }?.name ?: "Select workspace") },
                leadingIcon = { Icon(Icons.Default.Folder, null) },
                modifier = Modifier.testTag("workspace_selector")
            )
            DropdownMenu(workspaceMenu, { workspaceMenu = false }) {
                DropdownMenuItem({ Text("No workspace") }, { vm.chooseWorkspace(null); workspaceMenu = false })
                workspaces.forEach { workspace -> DropdownMenuItem({ Text(workspace.name) }, { vm.chooseWorkspace(workspace.id); workspaceMenu = false }) }
            }
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
    val workspaceId by vm.selectedWorkspaceId.collectAsState()
    if (timeline.isEmpty() && cards.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        timeline.takeLast(6).forEach { step ->
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
        cards.takeLast(8).forEach { card ->
            ElevatedCard(Modifier.fillMaxWidth().testTag("tool_card_${card.callId}")) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (card.status == "WAITING_PERMISSION") Icons.Default.Lock else if (card.status == "FAILED") Icons.Default.Warning else Icons.Default.Build, null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card.toolName, style = MaterialTheme.typography.labelLarge)
                        card.path?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                        if (card.summary.isNotBlank()) Text(card.summary, style = MaterialTheme.typography.bodySmall)
                        val stats = buildString {
                            card.durationMs?.let { append("${it} ms") }
                            if (card.added != 0 || card.removed != 0) append("  +${card.added} -${card.removed}")
                        }
                        if (stats.isNotBlank()) Text(stats, style = MaterialTheme.typography.labelSmall)
                    }
                    if (card.changeSetId != null && workspaceId != null) TextButton({ nav.navigate("diff/$workspaceId/${card.changeSetId}") }) { Text("Diff") }
                }
            }
        }
    }
}

@Composable
fun AgentPermissionDialog(vm: ChatViewModel) {
    val request by vm.pendingPermission.collectAsState()
    val pending = request ?: return
    AlertDialog(
        onDismissRequest = vm::denyPermission,
        modifier = Modifier.testTag("permission_dialog"),
        title = { Text("Agent permission required") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tool: ${pending.definition.name}")
                pending.preview?.path?.let { Text(it, fontFamily = FontFamily.Monospace) }
                pending.reason?.let { Text("Reason: $it") }
                Text("Risk: ${pending.definition.riskLevel.name}")
                pending.preview?.summary?.let { Text(it) }
                pending.preview?.diff?.takeIf { it.isNotBlank() }?.let { diff ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
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
                    TextButton({ vm.allowPermission(PermissionScope.ALWAYS) }) { Text("Always allow") }
                    TextButton(vm::denyPermission, Modifier.testTag("permission_deny")) { Text("Deny") }
                }
            }
        }
    )
}
