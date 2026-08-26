package com.agentdroid.ui

import android.content.Context
import android.os.Build
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
    val systemIsArabic = original.resources.configuration.locales[0].language.equals("ar", ignoreCase = true) || original.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
    val isRtl = when (settings.language) { AppLanguage.ARABIC -> true; AppLanguage.ENGLISH -> false; AppLanguage.SYSTEM -> systemIsArabic }
    val colors = when { settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(localized); settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(localized); dark -> darkColorScheme(); else -> lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF6750A4)) }
    CompositionLocalProvider(LocalContext provides localized, LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
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
            composable("chat") { ChatScreen(nav, factory, null) }
            composable("chat/{conversationId}", arguments = listOf(navArgument("conversationId") { type = NavType.StringType })) { entry -> ChatScreen(nav, factory, entry.arguments?.getString("conversationId")) }
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
    val vm: ConversationsViewModel = viewModel(factory = factory); val conversations by vm.conversations.collectAsState(); val providers: ProvidersViewModel = viewModel(factory = factory); val configured by providers.providers.collectAsState(); val chatVm: ChatViewModel = viewModel(factory = factory)
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium); Text(stringResource(R.string.greeting), style = MaterialTheme.typography.titleLarge); Text(stringResource(R.string.what_to_do), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button({ chatVm.newConversation { id -> nav.navigate("chat/$id") } }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.new_chat)) } }
        item { Text(stringResource(R.string.providers), style = MaterialTheme.typography.titleMedium); Text(if (configured.isEmpty()) stringResource(R.string.no_provider) else configured.first().name, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleMedium) }
        items(conversations.take(8), key = { it.id }) { conversation -> ElevatedCard({ nav.navigate("chat/${conversation.id}") }, Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(conversation.title) }, supportingContent = { Text(android.text.format.DateUtils.getRelativeTimeSpanString(conversation.updatedAt).toString()) }) } }
    }
}

@Composable private fun ChatScreen(nav: NavHostController, factory: ContainerViewModelFactory, conversationId: String?) {
    val vm: ChatViewModel = viewModel(factory = factory)
    LaunchedEffect(conversationId) { if (conversationId != null) vm.openConversation(conversationId) }
 val providers by vm.providers.collectAsState(); val id by vm.selectedConversationId.collectAsState(); val selectedProvider by vm.selectedProviderId.collectAsState(); val selectedModel by vm.selectedModelId.collectAsState(); val messages = if (id == null) emptyList() else vm.messages(id!!).collectAsState(initial = emptyList()).value; var providerMenu by remember { mutableStateOf(false) }; var modelMenu by remember { mutableStateOf(false) }
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
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Bottom) { OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.composer_hint)) }, maxLines = 5); Spacer(Modifier.width(8.dp)); if (vm.phase.collectAsState().value == ChatPhase.STREAMING) IconButton(vm::stop) { Icon(Icons.Default.Stop, stringResource(R.string.stop)) } else IconButton({ if (id == null) vm.newConversation { vm.send(input); input = "" } else { vm.send(input); input = "" } }) { Icon(Icons.Default.Send, stringResource(R.string.send)) } }
    }
}

@Composable private fun MessageBubble(message: MessageEntity, vm: ChatViewModel) {
    val user = message.role == "USER"; val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current; var editing by remember(message.id) { mutableStateOf(false) }
    Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(if (user) stringResource(R.string.you) else stringResource(R.string.agent), style = MaterialTheme.typography.labelMedium); MarkdownView(message.content); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { IconButton({ clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.content)) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy)) }; if (user) IconButton({ editing = true }) { Icon(Icons.Default.Edit, stringResource(R.string.edit_message)) }; if (!user && message.status == MessageStatus.FAILED.name) TextButton(onClick = vm::retry) { Text(stringResource(R.string.retry)) }; if (!user && message.status == MessageStatus.COMPLETED.name) TextButton(onClick = vm::regenerate) { Text(stringResource(R.string.regenerate)) } } } }
    if (editing) MessageEditDialog(message.content, { replacement -> vm.editUserMessage(message, replacement); editing = false }, { editing = false })
}

@Composable private fun MarkdownView(source: String) {
    val parser = remember { org.commonmark.parser.Parser.builder().extensions(listOf(org.commonmark.ext.gfm.tables.TablesExtension.create())).build() }; val document = remember(source) { parser.parse(source) }; val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.foundation.text.selection.SelectionContainer { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { var node = document.firstChild; while (node != null) { val current = node; when (current) { is org.commonmark.node.FencedCodeBlock -> CodeBlock(current.info.orEmpty(), current.literal.orEmpty(), clipboard); is org.commonmark.node.IndentedCodeBlock -> CodeBlock("", current.literal.orEmpty(), clipboard); is org.commonmark.node.Heading -> Text(inlineMarkdown(current), style = when (current.level) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleMedium }); is org.commonmark.node.BulletList -> Text("• " + markdownText(current).replace("\n", "\n• "), Modifier.fillMaxWidth()); is org.commonmark.node.OrderedList -> Text(numberedMarkdown(current), Modifier.fillMaxWidth()); is org.commonmark.node.BlockQuote -> Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) { Text(androidx.compose.ui.text.buildAnnotatedString { append("▌ "); append(inlineMarkdown(current)) }, Modifier.padding(10.dp)) }; is org.commonmark.ext.gfm.tables.TableBlock -> Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) { Text(tableMarkdown(current), Modifier.padding(10.dp), fontFamily = FontFamily.Monospace) }; else -> if (markdownText(current).isNotBlank()) StyledMarkdownText(current) }; node = current.next } } }
}

@Composable private fun StyledMarkdownText(node: org.commonmark.node.Node) { Text(inlineMarkdown(node), Modifier.fillMaxWidth()) }
@Composable private fun CodeBlock(language: String, code: String, clipboard: androidx.compose.ui.platform.ClipboardManager) { Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(8.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(language.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall); TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(code)) }) { Text(stringResource(R.string.copy)) } }; Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { Text(code, Modifier.padding(6.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface) } } } }
private fun inlineMarkdown(node: org.commonmark.node.Node): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString { appendInline(this, node, androidx.compose.ui.text.SpanStyle()) }
private fun appendInline(out: androidx.compose.ui.text.AnnotatedString.Builder, node: org.commonmark.node.Node, style: androidx.compose.ui.text.SpanStyle) {
    when (node) {
        is org.commonmark.node.Text -> out.withStyle(style) { out.append(node.literal) }
        is org.commonmark.node.Code -> out.withStyle(style.copy(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(0x22000000))) { out.append(node.literal) }
        is org.commonmark.node.Link -> {
            val child: org.commonmark.node.Node? = node.firstChild
            if (child != null) { val linkStyle = style.copy(color = androidx.compose.ui.graphics.Color(0xFF6750A4), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline); out.pushStringAnnotation("URL", node.destination); out.withStyle(linkStyle) { appendInline(out, child, linkStyle) }; out.pop() }
        }
        is org.commonmark.node.Emphasis -> { val child: org.commonmark.node.Node? = node.firstChild; if (child != null) appendInline(out, child, style.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) }
        is org.commonmark.node.StrongEmphasis -> { val child: org.commonmark.node.Node? = node.firstChild; if (child != null) appendInline(out, child, style.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) }
        is org.commonmark.node.SoftLineBreak, is org.commonmark.node.HardLineBreak -> out.append("\n")
        else -> { var child: org.commonmark.node.Node? = node.firstChild; while (child != null) { appendInline(out, child, style); child = child.next } }
    }
}
private fun markdownText(node: org.commonmark.node.Node): String = buildString { var child = node.firstChild; while (child != null) { when (child) { is org.commonmark.node.Text -> append(child.literal); is org.commonmark.node.Code -> append(child.literal); is org.commonmark.node.SoftLineBreak, is org.commonmark.node.HardLineBreak -> append('\n'); else -> append(markdownText(child)) }; child = child.next }; if (isEmpty() && node is org.commonmark.node.Text) append(node.literal) }
private fun numberedMarkdown(node: org.commonmark.node.Node): String { var number = if (node is org.commonmark.node.OrderedList) node.startNumber else 1; return buildString { var child = node.firstChild; while (child != null) { append(number++).append(". ").append(markdownText(child)).append('\n'); child = child.next } }.trimEnd() }
private fun tableMarkdown(node: org.commonmark.ext.gfm.tables.TableBlock): String = buildString { var row = node.firstChild; while (row != null) { var cell = row.firstChild; var first = true; while (cell != null) { if (!first) append(" | "); append(markdownText(cell)); first = false; cell = cell.next }; append('\n'); row = row.next } }.trimEnd()

@Composable private fun MoreScreen(nav: NavHostController) { LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text(stringResource(R.string.more), style = MaterialTheme.typography.headlineSmall) }; item { MenuTile(stringResource(R.string.providers), Icons.Default.Cloud) { nav.navigate("providers") } }; item { MenuTile(stringResource(R.string.models), Icons.Default.List) { nav.navigate("models") } }; item { MenuTile(stringResource(R.string.conversations), Icons.Default.History) { nav.navigate("conversations") } }; item { MenuTile(stringResource(R.string.memory), Icons.Default.Memory) { nav.navigate("memory") } }; item { MenuTile(stringResource(R.string.skills), Icons.Default.Star) { nav.navigate("skills") } }; item { MenuTile(stringResource(R.string.settings), Icons.Default.Settings) { nav.navigate("settings") } } } }
@Composable private fun MenuTile(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) { ElevatedCard(onClick = onClick, Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(title) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }) } }

@Composable private fun ProvidersScreen(nav: NavHostController, factory: ContainerViewModelFactory) {
    val vm: ProvidersViewModel = viewModel(factory = factory); val providers by vm.providers.collectAsState(); val revealed by vm.revealedSecret.collectAsState(); var result by remember { mutableStateOf<ProviderTestResult?>(null) }; var pendingDelete by remember { mutableStateOf<ProviderConfigEntity?>(null) }; var replace by remember { mutableStateOf<ProviderConfigEntity?>(null) }; val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column {
        TopBar(stringResource(R.string.providers), nav) { nav.navigate("addProvider") }
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (providers.isEmpty()) item { EmptyState(stringResource(R.string.no_provider)) }
            items(providers, key = { it.id }) { provider ->
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(provider.name, style = MaterialTheme.typography.titleLarge); Text(provider.kind, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(provider.modelId ?: stringResource(R.string.not_configured))
                    if (provider.secretAlias != null) { val revealedValue = revealed?.takeIf { it.first == provider.secretAlias }?.second; Text(revealedValue ?: vm.masked(provider), fontFamily = FontFamily.Monospace); Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { TextButton({ if (revealedValue == null) vm.reveal(provider) else vm.hideSecret() }) { Text(stringResource(if (revealedValue == null) R.string.show_key else R.string.hide_key)) }; TextButton({ replace = provider }) { Text(stringResource(R.string.replace_key)) }; TextButton({ revealedValue?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) } }) { Text(stringResource(R.string.copy_key)) }; TextButton({ vm.deleteSecret(provider) }) { Text(stringResource(R.string.delete_key)) } } } else Text(stringResource(R.string.no_key), color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ vm.test(provider) { outcome -> result = outcome } }) { Text(stringResource(R.string.test_connection)) }; OutlinedButton({ vm.toggle(provider) }) { Text(if (provider.enabled) stringResource(R.string.disable) else stringResource(R.string.enable)) }; IconButton({ pendingDelete = provider }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } }
                } }
            }
        }
    }
    result?.let { outcome -> TestResultDialog(outcome) { result = null } }
    replace?.let { item -> SecretDialog(stringResource(R.string.replace_key), { secret -> vm.replaceSecret(item, secret); replace = null }, { replace = null }) }
    pendingDelete?.let { item -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text(stringResource(R.string.delete)) }, text = { Text(stringResource(R.string.delete_confirmation)) }, confirmButton = { Button({ vm.delete(item); pendingDelete = null }) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton({ pendingDelete = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable private fun TestResultDialog(result: ProviderTestResult, close: () -> Unit) { AlertDialog(onDismissRequest = close, title = { Text(if (result.success) stringResource(R.string.connection_success) else stringResource(R.string.connection_failed)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Provider: ${result.provider}"); result.latencyMs?.let { Text("Latency: ${it} ms") }; result.modelCount?.let { Text("Models: $it") }; Text(if (result.streamingSupported) stringResource(R.string.streaming_supported) else stringResource(R.string.streaming_unknown)); result.error?.let { Text(it.userMessage, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(onClick = close) { Text(stringResource(R.string.close)) } }) }

@Composable private fun AddProviderScreen(nav: NavHostController, factory: ContainerViewModelFactory) {
    val vm: ProvidersViewModel = viewModel(factory = factory)
    var name by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }; var base by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }; var organization by remember { mutableStateOf("") }; var appName by remember { mutableStateOf("") }; var siteUrl by remember { mutableStateOf("") }; var headers by remember { mutableStateOf(listOf(HeaderRow())) }; var kind by remember { mutableStateOf(ProviderKind.OPENAI) }
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
        item { HeaderRowsEditor(headers) { headers = it } }
        item { Button({ vm.create(name, kind, secret, base.ifBlank { null }, model.ifBlank { null }, organization, appName, siteUrl, headers.filter { it.key.isNotBlank() }.associate { it.key to it.value }) { nav.popBackStack() } }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) } }
    }
}

@Composable private fun ModelsScreen(factory: ContainerViewModelFactory) { val vm: ProvidersViewModel = viewModel(factory = factory); val providers by vm.providers.collectAsState(); var selected by remember { mutableStateOf<ProviderConfigEntity?>(null) }; var query by remember { mutableStateOf("") }; Column(Modifier.fillMaxSize().padding(16.dp)) { Text(stringResource(R.string.models), style = MaterialTheme.typography.headlineSmall); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { providers.forEach { AssistChip(onClick = { selected = it; vm.loadModels(it) }, label = { Text(it.name) }) } }; OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.search_models)) }); if (vm.loadingModels.collectAsState().value) LinearProgressIndicator(Modifier.fillMaxWidth()); val visible = vm.models.collectAsState().value.filter { it.id.contains(query, true) || it.displayName.contains(query, true) }; LazyColumn { items(visible, key = { it.id }) { model -> ListItem(headlineContent = { Text(model.displayName) }, supportingContent = { Text(model.id) }, leadingContent = { RadioButton(selected?.modelId == model.id, { selected?.let { vm.selectModel(it.id, model.id); selected = it.copy(modelId = model.id) } }) }) } } } }

@Composable private fun ConversationsScreen(nav: NavHostController, factory: ContainerViewModelFactory) {
    val vm: ConversationsViewModel = viewModel(factory = factory); val chatVm: ChatViewModel = viewModel(factory = factory); val conversations by vm.conversations.collectAsState(); var query by remember { mutableStateOf("") }; var deleteId by remember { mutableStateOf<String?>(null) }; var rename by remember { mutableStateOf<ConversationEntity?>(null) }
    val shown = if (query.isBlank()) conversations else conversations.filter { it.title.contains(query, true) }
    Column {
        TopBar(stringResource(R.string.conversations), nav) { chatVm.newConversation { id -> nav.navigate("chat/$id") } }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), label = { Text(stringResource(R.string.search)) })
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { item ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    ListItem(headlineContent = { Text(item.title) }, supportingContent = { Text(if (item.archived) stringResource(R.string.archived) else android.text.format.DateUtils.getRelativeTimeSpanString(item.updatedAt).toString()) }, leadingContent = { IconButton({ nav.navigate("chat/${item.id}") }) { Icon(Icons.Default.OpenInNew, stringResource(R.string.open)) } }, trailingContent = { Row { IconButton({ rename = item }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }; IconButton({ if (item.archived) vm.unarchive(item.id) else vm.archive(item.id) }) { Icon(if (item.archived) Icons.Default.Unarchive else Icons.Default.Archive, stringResource(if (item.archived) R.string.unarchive else R.string.archive)) }; IconButton({ deleteId = item.id }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } } })
                }
            }
        }
    }
    rename?.let { item -> SimpleCreateDialog(stringResource(R.string.rename), stringResource(R.string.title), stringResource(R.string.content), { title, _ -> vm.rename(item.id, title); rename = null }, { rename = null }) }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.delete)) }, text = { Text(stringResource(R.string.delete_confirmation)) }, confirmButton = { Button({ vm.delete(id); deleteId = null }) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton({ deleteId = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable private fun WorkspacesScreen(factory: ContainerViewModelFactory) {
    val vm: WorkspacesViewModel = viewModel(factory = factory); val items by vm.items.collectAsState(); var editor by remember { mutableStateOf<WorkspaceEntity?>(null) }; var deleting by remember { mutableStateOf<WorkspaceEntity?>(null) }; var opened by remember { mutableStateOf<WorkspaceEntity?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.workspaces), style = MaterialTheme.typography.headlineSmall); IconButton({ editor = WorkspaceEntity(UUID.randomUUID().toString(), "", "", System.currentTimeMillis(), System.currentTimeMillis()) }) { Icon(Icons.Default.Add, stringResource(R.string.create_workspace)) } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(items, key = { it.id }) { item -> ElevatedCard({ opened = item }, Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(item.description) }, trailingContent = { Row { IconButton({ editor = item }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }; IconButton({ deleting = item }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } } }) } } }
    }
    editor?.let { item -> TextEditorDialog(stringResource(if (item.name.isBlank()) R.string.create_workspace else R.string.edit), item.name, item.description, stringResource(R.string.workspace_name), stringResource(R.string.description), { name, description, _ -> vm.save(item.copy(name = name, description = description, updatedAt = System.currentTimeMillis())); editor = null }, { editor = null }) }
    opened?.let { item -> AlertDialog(onDismissRequest = { opened = null }, title = { Text(item.name) }, text = { Text(item.description.ifBlank { stringResource(R.string.no_description) }) }, confirmButton = { Button({ opened = null }) { Text(stringResource(R.string.close)) } }) }
    deleting?.let { item -> ConfirmDeleteDialog { vm.delete(item.id); deleting = null } }
}
@Composable private fun MemoryScreen(factory: ContainerViewModelFactory) {
    val vm: MemoryViewModel = viewModel(factory = factory); val workspaceVm: WorkspacesViewModel = viewModel(factory = factory); val items by vm.items.collectAsState(); val workspaces by workspaceVm.items.collectAsState(); var editor by remember { mutableStateOf<MemoryEntryEntity?>(null) }; var deleting by remember { mutableStateOf<MemoryEntryEntity?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.memory), style = MaterialTheme.typography.headlineSmall); IconButton({ editor = MemoryEntryEntity(UUID.randomUUID().toString(), MemoryScope.GLOBAL.name, null, "", "", true, System.currentTimeMillis(), System.currentTimeMillis()) }) { Icon(Icons.Default.Add, stringResource(R.string.add_memory)) } }; LazyColumn { items(items, key = { it.id }) { item -> ListItem(headlineContent = { Text(item.title) }, supportingContent = { Text("${item.scope}: ${item.content}") }, trailingContent = { Row { Switch(item.enabled, { vm.toggle(item) }); IconButton({ editor = item }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }; IconButton({ deleting = item }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } } }) } } }
    editor?.let { item -> MemoryEditorDialog(item, workspaces, { updated -> vm.save(updated); editor = null }, { editor = null }) }; deleting?.let { item -> ConfirmDeleteDialog { vm.delete(item.id); deleting = null } }
}
@Composable private fun SkillsScreen(factory: ContainerViewModelFactory) {
    val vm: SkillsViewModel = viewModel(factory = factory); val workspaceVm: WorkspacesViewModel = viewModel(factory = factory); val items by vm.items.collectAsState(); val workspaces by workspaceVm.items.collectAsState(); var editor by remember { mutableStateOf<SkillEntity?>(null) }; var deleting by remember { mutableStateOf<SkillEntity?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(stringResource(R.string.skills), style = MaterialTheme.typography.headlineSmall); IconButton({ editor = SkillEntity(UUID.randomUUID().toString(), "", "", "", true, "GLOBAL", null, System.currentTimeMillis(), System.currentTimeMillis()) }) { Icon(Icons.Default.Add, stringResource(R.string.add_skill)) } }; LazyColumn { items(items, key = { it.id }) { item -> ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(item.description) }, trailingContent = { Row { Switch(item.enabled, { vm.toggle(item) }); IconButton({ editor = item }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }; IconButton({ deleting = item }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } } }) } } }
    editor?.let { item -> SkillEditorDialog(item, workspaces, { updated -> vm.save(updated); editor = null }, { editor = null }) }; deleting?.let { item -> ConfirmDeleteDialog { vm.delete(item.id); deleting = null } }
}


@Composable private fun CrudList(title: String, values: List<Pair<String, Pair<String, String>>>, add: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(title, style = MaterialTheme.typography.headlineSmall); IconButton(onClick = add) { Icon(Icons.Default.Add, null) } }; LazyColumn { items(values) { ListItem(headlineContent = { Text(it.second.first) }, supportingContent = { Text(it.second.second) }) } } }
}

@Composable private fun SimpleCreateDialog(title: String, firstLabel: String, secondLabel: String, save: (String, String) -> Unit, cancel: () -> Unit) {
    var first by remember { mutableStateOf("") }; var second by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(first, { first = it }, label = { Text(firstLabel) }); OutlinedTextField(second, { second = it }, label = { Text(secondLabel) }) } }, confirmButton = { Button(onClick = { if (first.isNotBlank()) save(first, second) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun EmptyState(text: String) { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall); Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium); ThemeRow(stringResource(R.string.system), settings.theme == AppTheme.SYSTEM) { vm.theme(AppTheme.SYSTEM) }; ThemeRow(stringResource(R.string.light), settings.theme == AppTheme.LIGHT) { vm.theme(AppTheme.LIGHT) }; ThemeRow(stringResource(R.string.dark), settings.theme == AppTheme.DARK) { vm.theme(AppTheme.DARK) }; Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium); ThemeRow(stringResource(R.string.system), settings.language == AppLanguage.SYSTEM) { vm.language(AppLanguage.SYSTEM) }; ThemeRow(stringResource(R.string.arabic), settings.language == AppLanguage.ARABIC) { vm.language(AppLanguage.ARABIC) }; ThemeRow(stringResource(R.string.english), settings.language == AppLanguage.ENGLISH) { vm.language(AppLanguage.ENGLISH) }; ListItem(headlineContent = { Text(stringResource(R.string.dynamic_color)) }, trailingContent = { Switch(settings.dynamicColor, vm::dynamicColor) }); ListItem(headlineContent = { Text(stringResource(R.string.developer_mode)) }, trailingContent = { Switch(settings.developerMode, vm::developerMode) }) }
}
@Composable private fun ThemeRow(label: String, selected: Boolean, choose: () -> Unit) { ListItem(headlineContent = { Text(label) }, leadingContent = { RadioButton(selected, choose) }) }

@Composable private fun TextEditorDialog(title: String, initialFirst: String, initialSecond: String, firstLabel: String, secondLabel: String, save: (String, String, String) -> Unit, cancel: () -> Unit) {
    var first by remember(title, initialFirst) { mutableStateOf(initialFirst) }; var second by remember(title, initialSecond) { mutableStateOf(initialSecond) }
    AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(first, { first = it }, label = { Text(firstLabel) }); OutlinedTextField(second, { second = it }, label = { Text(secondLabel) }) } }, confirmButton = { Button(onClick = { if (first.isNotBlank()) save(first, second, "") }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun ScopeSelector(scope: String, workspaceId: String?, workspaces: List<WorkspaceEntity>, onScopeChanged: (String, String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(scope == "GLOBAL", { onScopeChanged("GLOBAL", null) }, label = { Text(stringResource(R.string.global)) }); FilterChip(scope == "WORKSPACE", { onScopeChanged("WORKSPACE", workspaceId) }, label = { Text(stringResource(R.string.workspace_scope)) }) }
    if (scope == "WORKSPACE") { Box { OutlinedButton(onClick = { expanded = true }) { Text(workspaces.firstOrNull { it.id == workspaceId }?.name ?: stringResource(R.string.select_workspace)) }; DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { workspaces.forEach { workspace -> DropdownMenuItem(text = { Text(workspace.name) }, onClick = { onScopeChanged("WORKSPACE", workspace.id); expanded = false }) } } } }
}

@Composable private fun MemoryEditorDialog(item: MemoryEntryEntity, workspaces: List<WorkspaceEntity>, save: (MemoryEntryEntity) -> Unit, cancel: () -> Unit) {
    var title by remember(item.id) { mutableStateOf(item.title) }; var content by remember(item.id) { mutableStateOf(item.content) }; var scope by remember(item.id) { mutableStateOf(item.scope) }; var workspaceId by remember(item.id) { mutableStateOf(item.workspaceId) }
    AlertDialog(onDismissRequest = cancel, title = { Text(if (item.title.isBlank()) stringResource(R.string.add_memory) else stringResource(R.string.edit)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }); OutlinedTextField(content, { content = it }, label = { Text(stringResource(R.string.content)) }); ScopeSelector(scope, workspaceId, workspaces) { newScope, newWorkspace -> scope = newScope; workspaceId = newWorkspace } } }, confirmButton = { Button(onClick = { if (title.isNotBlank()) save(item.copy(title = title, content = content, scope = scope, workspaceId = if (scope == "WORKSPACE") workspaceId else null, updatedAt = System.currentTimeMillis())) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun SkillEditorDialog(item: SkillEntity, workspaces: List<WorkspaceEntity>, save: (SkillEntity) -> Unit, cancel: () -> Unit) {
    var name by remember(item.id) { mutableStateOf(item.name) }; var description by remember(item.id) { mutableStateOf(item.description) }; var instructions by remember(item.id) { mutableStateOf(item.instructions) }; var scope by remember(item.id) { mutableStateOf(item.scope) }; var workspaceId by remember(item.id) { mutableStateOf(item.workspaceId) }
    AlertDialog(onDismissRequest = cancel, title = { Text(if (item.name.isBlank()) stringResource(R.string.add_skill) else stringResource(R.string.edit)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.skill_name)) }); OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.description)) }); OutlinedTextField(instructions, { instructions = it }, label = { Text(stringResource(R.string.instructions)) }, minLines = 4); ScopeSelector(scope, workspaceId, workspaces) { newScope, newWorkspace -> scope = newScope; workspaceId = newWorkspace } } }, confirmButton = { Button(onClick = { if (name.isNotBlank()) save(item.copy(name = name, description = description, instructions = instructions, scope = scope, workspaceId = if (scope == "WORKSPACE") workspaceId else null, updatedAt = System.currentTimeMillis())) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun ConfirmDeleteDialog(cancel: () -> Unit = {}, confirm: () -> Unit) { AlertDialog(onDismissRequest = cancel, title = { Text(stringResource(R.string.delete)) }, text = { Text(stringResource(R.string.delete_confirmation)) }, confirmButton = { Button(onClick = confirm) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } }) }

private data class HeaderRow(val key: String = "", val value: String = "")
@Composable private fun HeaderRowsEditor(rows: List<HeaderRow>, onChange: (List<HeaderRow>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(stringResource(R.string.custom_headers), style = MaterialTheme.typography.titleSmall); rows.forEachIndexed { index, row -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(value = row.key, onValueChange = { value -> onChange(rows.toMutableList().also { it[index] = row.copy(key = value) }) }, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.header_key)) }); OutlinedTextField(value = row.value, onValueChange = { value -> onChange(rows.toMutableList().also { it[index] = row.copy(value = value) }) }, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.header_value)) }); IconButton(onClick = { if (rows.size > 1) onChange(rows.toMutableList().also { it.removeAt(index) }) }) { Icon(Icons.Default.Remove, stringResource(R.string.remove_header)) } } }; TextButton(onClick = { onChange(rows + HeaderRow()) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.add_header)) } }
}

@Composable private fun SecretDialog(title: String, save: (String) -> Unit, cancel: () -> Unit) { var value by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.api_key)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)) }, confirmButton = { Button(onClick = { if (value.isNotBlank()) save(value) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } }) }

@Composable private fun MessageEditDialog(initial: String, save: (String) -> Unit, cancel: () -> Unit) { var value by remember(initial) { mutableStateOf(initial) }; AlertDialog(onDismissRequest = cancel, title = { Text(stringResource(R.string.edit_message)) }, text = { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text(stringResource(R.string.content)) }) }, confirmButton = { Button(onClick = { if (value.isNotBlank()) save(value) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } }) }
