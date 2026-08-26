package com.agentdroid.ui

import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.agentdroid.AgentDroidApplication
import com.agentdroid.AppContainer
import com.agentdroid.R
import com.agentdroid.core.ai.ProviderTestResult
import com.agentdroid.core.model.*
import com.agentdroid.data.database.*
import com.agentdroid.settings.*
import com.agentdroid.viewmodel.*
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDroidRoot() {
    val original = LocalContext.current
    val application = original.applicationContext as AgentDroidApplication
    val factory = remember(application) { ContainerViewModelFactory(application.container) }
    val settingsVm: SettingsViewModel = viewModel(factory = factory)
    val settings by settingsVm.settings.collectAsState()
    val localized = remember(settings.language, original) { localizedContext(original, settings.language) }
    val dark = when (settings.theme) { AppTheme.DARK -> true; AppTheme.LIGHT -> false; AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme() }
    val colors = if (dark) darkColorScheme() else lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF6750A4))
    CompositionLocalProvider(LocalContext provides localized, LocalLayoutDirection provides if (settings.language == AppLanguage.ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography) { AgentDroidNavigation(application.container, factory, settingsVm) }
    }
}

private fun localizedContext(context: Context, language: AppLanguage): Context {
    if (language == AppLanguage.SYSTEM) return context
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(if (language == AppLanguage.ARABIC) Locale("ar") else Locale.ENGLISH)
    return context.createConfigurationContext(configuration)
}

@Composable
private fun AgentDroidNavigation(container: AppContainer, factory: ContainerViewModelFactory, settingsVm: SettingsViewModel) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("home" to Icons.Default.Home to R.string.home, "chat" to Icons.Default.Chat to R.string.chat, "workspaces" to Icons.Default.Folder to R.string.workspaces, "more" to Icons.Default.MoreHoriz to R.string.more).forEach { (pair, label) ->
                val (itemRoute, icon) = pair
                NavigationBarItem(selected = route == itemRoute, onClick = { nav.navigate(itemRoute) { launchSingleTop = true } }, icon = { Icon(icon, contentDescription = stringResource(label)) }, label = { Text(stringResource(label)) })
            }
        }
    }) { padding ->
        NavHost(nav, "home", Modifier.padding(padding)) {
            composable("home") { HomeScreen(nav, container, factory) }
            composable("chat") { ChatScreen(nav, factory) }
            composable("workspaces") { WorkspacesScreen(factory) }
            composable("more") { MoreScreen(nav) }
            composable("providers") { ProvidersScreen(nav, factory) }
            composable("models") { ModelsScreen(factory) }
            composable("conversations") { ConversationsScreen(nav, factory) }
            composable("memory") { MemoryScreen(factory) }
            composable("skills") { SkillsScreen(factory) }
            composable("settings") { SettingsScreen(settingsVm) }
            composable("addProvider") { AddProviderScreen(nav, factory) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TopBar(title: String, nav: NavHostController? = null, onAction: (() -> Unit)? = null) {
    TopAppBar(title = { Text(title) }, navigationIcon = { if (nav != null) IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, contentDescription = null) } }, actions = { if (onAction != null) IconButton(onAction) { Icon(Icons.Default.Add, contentDescription = null) } })
}

@Composable private fun HomeScreen(nav: NavHostController, container: AppContainer, factory: ContainerViewModelFactory) {
    val vm: ConversationsViewModel = viewModel(factory = factory); val conversations by vm.conversations.collectAsState(); val providers: ProvidersViewModel = viewModel(factory = factory); val configured by providers.providers.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium); Text(stringResource(R.string.greeting), style = MaterialTheme.typography.titleLarge); Text(stringResource(R.string.what_to_do), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button({ nav.navigate("chat") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.new_chat)) } }
        item { Text(stringResource(R.string.providers), style = MaterialTheme.typography.titleMedium); Text(if (configured.isEmpty()) stringResource(R.string.no_provider) else configured.first().name, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleMedium) }
        items(conversations.take(8), key = { it.id }) { conversation -> ElevatedCard({ nav.navigate("chat"); }, Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(conversation.title) }, supportingContent = { Text(android.text.format.DateUtils.getRelativeTimeSpanString(conversation.updatedAt).toString()) }) } }
    }
}

@Composable private fun ChatScreen(nav: NavHostController, factory: ContainerViewModelFactory) {
    val vm: ChatViewModel = viewModel(factory = factory); val providers by vm.providers.collectAsState(); val id by vm.selectedConversationId.collectAsState(); val selectedProvider by vm.selectedProviderId.collectAsState(); val selectedModel by vm.selectedModelId.collectAsState(); val messages = if (id == null) emptyList() else vm.messages(id!!).collectAsState(initial = emptyList()).value; var providerMenu by remember { mutableStateOf(false) }; var modelMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.chat), nav) { vm.newConversation() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box { AssistChip(onClick = { providerMenu = true }, label = { Text(providers.firstOrNull { it.id == selectedProvider }?.name ?: stringResource(R.string.select_provider)) }, leadingIcon = { Icon(Icons.Default.Cloud, null) }); DropdownMenu(providerMenu, { providerMenu = false }) { providers.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { vm.chooseProvider(p.id); providerMenu = false }) } } }
            Box { AssistChip(onClick = { modelMenu = true }, label = { Text(selectedModel ?: stringResource(R.string.select_model)) }); DropdownMenu(modelMenu, { modelMenu = false }) { providers.firstOrNull { it.id == selectedProvider }?.modelId?.let { model -> DropdownMenuItem(text = { Text(model) }, onClick = { vm.chooseModel(model); modelMenu = false }) } } }
        }
        if (id == null) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(stringResource(R.string.no_messages)); Button({ vm.newConversation() }) { Text(stringResource(R.string.new_chat)) } } }
        else LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(messages, key = { it.id }) { MessageBubble(it, vm) } }
        vm.error.collectAsState().value?.let { Text(it.userMessage, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        var input by remember { mutableStateOf("") }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Bottom) { OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.composer_hint)) }, maxLines = 5); Spacer(Modifier.width(8.dp)); if (vm.phase.collectAsState().value == ChatPhase.STREAMING) IconButton(vm::stop) { Icon(Icons.Default.Stop, stringResource(R.string.stop)) } else IconButton({ if (id == null) vm.newConversation { vm.userMessage.value = input; vm.send(input); input = "" } else { vm.send(input); input = "" } }) { Icon(Icons.Default.Send, stringResource(R.string.send)) } }
    }
}

@Composable private fun MessageBubble(message: MessageEntity, vm: ChatViewModel) {
    val user = message.role == "USER"; val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(if (user) stringResource(R.string.you) else stringResource(R.string.agent), style = MaterialTheme.typography.labelMedium); MarkdownView(message.content); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { IconButton({ clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.content)) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy)) }; if (!user && message.status == MessageStatus.FAILED.name) TextButton(vm::retry) { Text(stringResource(R.string.retry)) }; if (!user && message.status == MessageStatus.COMPLETED.name) TextButton({ vm.retry() }) { Text(stringResource(R.string.regenerate)) } } } }
}

@Composable private fun MarkdownView(source: String) { val parser = remember { org.commonmark.parser.Parser.builder().extensions(listOf(org.commonmark.ext.gfm.tables.TablesExtension.create())).build() }; val document = remember(source) { parser.parse(source) }; androidx.compose.foundation.text.selection.SelectionContainer { Column(Modifier.fillMaxWidth()) { var node = document.firstChild; while (node != null) { val current = node; when (current) { is org.commonmark.node.FencedCodeBlock -> Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) { Text(current.literal.orEmpty(), Modifier.padding(12.dp).fillMaxWidth(), fontFamily = FontFamily.Monospace) }; is org.commonmark.node.IndentedCodeBlock -> Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) { Text(current.literal.orEmpty(), Modifier.padding(12.dp).fillMaxWidth(), fontFamily = FontFamily.Monospace) }; else -> { val rendered = markdownText(current); if (rendered.isNotBlank()) Text(rendered, Modifier.fillMaxWidth()) } }; node = current.next } } } }
private fun markdownText(node: org.commonmark.node.Node): String { val text = StringBuilder(); if (node is org.commonmark.node.Text) text.append(node.literal); if (node is org.commonmark.node.Code) text.append(node.literal); var child = node.firstChild; while (child != null) { when (child) { is org.commonmark.node.Text -> text.append(child.literal); is org.commonmark.node.Code -> text.append(child.literal); is org.commonmark.node.SoftLineBreak, is org.commonmark.node.HardLineBreak -> text.append('\n'); else -> text.append(markdownText(child)) }; child = child.next }; return text.toString() }

@Composable private fun MoreScreen(nav: NavHostController) { LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text(stringResource(R.string.more), style = MaterialTheme.typography.headlineSmall) }; item { MenuTile(stringResource(R.string.providers), Icons.Default.Cloud) { nav.navigate("providers") } }; item { MenuTile(stringResource(R.string.models), Icons.Default.List) { nav.navigate("models") } }; item { MenuTile(stringResource(R.string.conversations), Icons.Default.History) { nav.navigate("conversations") } }; item { MenuTile(stringResource(R.string.memory), Icons.Default.Memory) { nav.navigate("memory") } }; item { MenuTile(stringResource(R.string.skills), Icons.Default.Star) { nav.navigate("skills") } }; item { MenuTile(stringResource(R.string.settings), Icons.Default.Settings) { nav.navigate("settings") } } } }
@Composable private fun MenuTile(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) { ElevatedCard(onClick = onClick, Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(title) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }) } }

@Composable private fun ProvidersScreen(nav: NavHostController, factory: ContainerViewModelFactory) {
    val vm: ProvidersViewModel = viewModel(factory = factory)
    val providers by vm.providers.collectAsState()
    var result by remember { mutableStateOf<ProviderTestResult?>(null) }
    var pendingDelete by remember { mutableStateOf<ProviderConfigEntity?>(null) }
    Column {
        TopBar(stringResource(R.string.providers), nav) { nav.navigate("addProvider") }
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (providers.isEmpty()) item { EmptyState(stringResource(R.string.no_provider)) }
            items(providers, key = { it.id }) { provider ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(provider.name, style = MaterialTheme.typography.titleLarge)
                        Text(provider.kind, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(provider.modelId ?: stringResource(R.string.not_configured))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ vm.test(provider) { outcome -> result = outcome } }) { Text(stringResource(R.string.test_connection)) }
                            OutlinedButton({ vm.toggle(provider) }) { Text(if (provider.enabled) stringResource(R.string.disable) else stringResource(R.string.enable)) }
                            IconButton({ pendingDelete = provider }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
    result?.let { outcome -> TestResultDialog(outcome) { result = null } }
    pendingDelete?.let { item ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text(stringResource(R.string.delete)) }, text = { Text(stringResource(R.string.delete_confirmation)) }, confirmButton = { Button({ vm.delete(item); pendingDelete = null }) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton({ pendingDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable private fun TestResultDialog(result: ProviderTestResult, close: () -> Unit) { AlertDialog(onDismissRequest = close, title = { Text(if (result.success) stringResource(R.string.connection_success) else stringResource(R.string.connection_failed)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Provider: ${result.provider}"); result.latencyMs?.let { Text("Latency: ${it} ms") }; result.modelCount?.let { Text("Models: $it") }; Text(if (result.streamingSupported) stringResource(R.string.streaming_supported) else stringResource(R.string.streaming_unknown)); result.error?.let { Text(it.userMessage, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(onClick = close) { Text(stringResource(R.string.close)) } }) }

@Composable private fun AddProviderScreen(nav: NavHostController, factory: ContainerViewModelFactory) {
    val vm: ProvidersViewModel = viewModel(factory = factory)
    var name by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }; var base by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }; var organization by remember { mutableStateOf("") }; var appName by remember { mutableStateOf("") }; var siteUrl by remember { mutableStateOf("") }; var headersText by remember { mutableStateOf("") }; var kind by remember { mutableStateOf(ProviderKind.OPENAI) }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TopBar(stringResource(R.string.add_provider), nav) }
        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.provider_name)) }) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { ProviderKind.values().filter { it != ProviderKind.FAKE }.forEach { value -> FilterChip(kind == value, { kind = value }, label = { Text(value.name.lowercase()) }) } } }
        item { OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.api_key)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)) }
        item { OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.base_url)) }) }
        item { OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.model_id)) }) }
        item { OutlinedTextField(organization, { organization = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.organization_id)) }) }
        item { OutlinedTextField(appName, { appName = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.app_name_header)) }) }
        item { OutlinedTextField(siteUrl, { siteUrl = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.site_url)) }) }
        item { OutlinedTextField(headersText, { headersText = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.custom_headers)) }, minLines = 3) }
        item { Button({ val headers = headersText.lineSequence().mapNotNull { line -> line.substringBefore(':', "").trim().takeIf { it.isNotBlank() }?.let { key -> key to line.substringAfter(':', "").trim() } }.toMap(); vm.create(name, kind, secret, base.ifBlank { null }, model.ifBlank { null }, organization, appName, siteUrl, headers) { nav.popBackStack() } }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) } }
    }
}

@Composable private fun ModelsScreen(factory: ContainerViewModelFactory) { val vm: ProvidersViewModel = viewModel(factory = factory); val providers by vm.providers.collectAsState(); var selected by remember { mutableStateOf<ProviderConfigEntity?>(null) }; var query by remember { mutableStateOf("") }; Column(Modifier.fillMaxSize().padding(16.dp)) { Text(stringResource(R.string.models), style = MaterialTheme.typography.headlineSmall); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { providers.forEach { AssistChip(onClick = { selected = it; vm.loadModels(it) }, label = { Text(it.name) }) } }; OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.search_models)) }); if (vm.loadingModels.collectAsState().value) LinearProgressIndicator(Modifier.fillMaxWidth()); val visible = vm.models.collectAsState().value.filter { it.id.contains(query, true) || it.displayName.contains(query, true) }; LazyColumn { items(visible, key = { it.id }) { model -> ListItem(headlineContent = { Text(model.displayName) }, supportingContent = { Text(model.id) }, leadingContent = { RadioButton(selected?.modelId == model.id, { selected?.let { vm.selectModel(it.id, model.id); selected = it.copy(modelId = model.id) } }) }) } } } }

@Composable private fun ConversationsScreen(nav: NavHostController, factory: ContainerViewModelFactory) { val vm: ConversationsViewModel = viewModel(factory = factory); val conversations by vm.conversations.collectAsState(); var query by remember { mutableStateOf("") }; var deleteId by remember { mutableStateOf<String?>(null) }; val shown = if (query.isBlank()) conversations else conversations.filter { it.title.contains(query, true) }; Column { TopBar(stringResource(R.string.conversations), nav); OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), label = { Text(stringResource(R.string.search)) }); LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(shown, key = { it.id }) { item -> ElevatedCard(Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(item.title) }, supportingContent = { Text(android.text.format.DateUtils.getRelativeTimeSpanString(item.updatedAt).toString()) }, trailingContent = { IconButton({ deleteId = item.id }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } }) } } } }; deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.delete)) }, text = { Text(stringResource(R.string.delete_confirmation)) }, confirmButton = { Button({ vm.delete(id); deleteId = null }) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton({ deleteId = null }) { Text(stringResource(R.string.cancel)) } }) } }

@Composable private fun WorkspacesScreen(factory: ContainerViewModelFactory) { val vm: WorkspacesViewModel = viewModel(factory = factory); val items by vm.items.collectAsState(); var dialog by remember { mutableStateOf(false) }; if (dialog) SimpleCreateDialog(stringResource(R.string.create_workspace), stringResource(R.string.workspace_name), stringResource(R.string.description), { name, description -> vm.save(WorkspaceEntity(UUID.randomUUID().toString(), name, description, System.currentTimeMillis(), System.currentTimeMillis())); dialog = false }, { dialog = false }); CrudList(stringResource(R.string.workspaces), items.map { it.id to (it.name to it.description) }, { dialog = true }, {}) }
@Composable private fun MemoryScreen(factory: ContainerViewModelFactory) { val vm: MemoryViewModel = viewModel(factory = factory); val items by vm.items.collectAsState(); var dialog by remember { mutableStateOf(false) }; if (dialog) SimpleCreateDialog(stringResource(R.string.add_memory), stringResource(R.string.title), stringResource(R.string.content), { name, content -> vm.save(MemoryEntryEntity(UUID.randomUUID().toString(), MemoryScope.GLOBAL.name, null, name, content, true, System.currentTimeMillis(), System.currentTimeMillis())); dialog = false }, { dialog = false }); Column(Modifier.fillMaxSize().padding(16.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.memory), style = MaterialTheme.typography.headlineSmall); IconButton({ dialog = true }) { Icon(Icons.Default.Add, null) } }; LazyColumn { items(items, key = { it.id }) { item -> ListItem(headlineContent = { Text(item.title) }, supportingContent = { Text(item.content) }, trailingContent = { Switch(item.enabled, { vm.toggle(item) }) }) } } } }
@Composable private fun SkillsScreen(factory: ContainerViewModelFactory) { val vm: SkillsViewModel = viewModel(factory = factory); val items by vm.items.collectAsState(); var dialog by remember { mutableStateOf(false) }; if (dialog) SimpleCreateDialog(stringResource(R.string.add_skill), stringResource(R.string.skill_name), stringResource(R.string.instructions), { name, instructions -> vm.save(SkillEntity(UUID.randomUUID().toString(), name, instructions, instructions, true, "GLOBAL", null, System.currentTimeMillis(), System.currentTimeMillis())); dialog = false }, { dialog = false }); Column(Modifier.fillMaxSize().padding(16.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.skills), style = MaterialTheme.typography.headlineSmall); IconButton({ dialog = true }) { Icon(Icons.Default.Add, null) } }; LazyColumn { items(items, key = { it.id }) { item -> ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(item.description) }, trailingContent = { Switch(item.enabled, { vm.toggle(item) }) }) } } } }

@Composable private fun CrudList(title: String, values: List<Pair<String, Pair<String, String>>>, add: () -> Unit, unused: () -> Unit) { Column(Modifier.fillMaxSize().padding(16.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(title, style = MaterialTheme.typography.headlineSmall); IconButton(add) { Icon(Icons.Default.Add, null) } }; LazyColumn { items(values) { ListItem(headlineContent = { Text(it.second.first) }, supportingContent = { Text(it.second.second) }) } } } }
@Composable private fun SimpleCreateDialog(title: String, firstLabel: String, secondLabel: String, save: (String, String) -> Unit, cancel: () -> Unit) { var first by remember { mutableStateOf("") }; var second by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(first, { first = it }, label = { Text(firstLabel) }); OutlinedTextField(second, { second = it }, label = { Text(secondLabel) }) } }, confirmButton = { Button({ if (first.isNotBlank()) save(first, second) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(cancel) { Text(stringResource(R.string.cancel)) } }) }
@Composable private fun EmptyState(text: String) { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun SettingsScreen(vm: SettingsViewModel) { val settings by vm.settings.collectAsState(); Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall); Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium); ThemeRow(stringResource(R.string.system), settings.theme == AppTheme.SYSTEM) { vm.theme(AppTheme.SYSTEM) }; ThemeRow(stringResource(R.string.light), settings.theme == AppTheme.LIGHT) { vm.theme(AppTheme.LIGHT) }; ThemeRow(stringResource(R.string.dark), settings.theme == AppTheme.DARK) { vm.theme(AppTheme.DARK) }; Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium); ThemeRow(stringResource(R.string.system), settings.language == AppLanguage.SYSTEM) { vm.language(AppLanguage.SYSTEM) }; ThemeRow(stringResource(R.string.arabic), settings.language == AppLanguage.ARABIC) { vm.language(AppLanguage.ARABIC) }; ThemeRow(stringResource(R.string.english), settings.language == AppLanguage.ENGLISH) { vm.language(AppLanguage.ENGLISH) }; ListItem(headlineContent = { Text(stringResource(R.string.dynamic_color)) }, trailingContent = { Switch(settings.dynamicColor, vm::dynamicColor) }); ListItem(headlineContent = { Text(stringResource(R.string.developer_mode)) }, trailingContent = { Switch(settings.developerMode, vm::developerMode) }) } }
@Composable private fun ThemeRow(label: String, selected: Boolean, choose: () -> Unit) { ListItem(headlineContent = { Text(label) }, leadingContent = { RadioButton(selected, choose) }) }
