package com.agentdroid.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.agentdroid.AppContainer
import com.agentdroid.core.ai.ProviderAgentModelClient
import com.agentdroid.core.ai.ProviderTestResult
import com.agentdroid.core.agent.*
import com.agentdroid.core.artifacts.ArtifactListFilter
import com.agentdroid.core.model.*
import com.agentdroid.core.permissions.PermissionRule
import com.agentdroid.core.workspace.*
import com.agentdroid.data.database.*
import com.agentdroid.integration.ModelPlanningCoordinator
import com.agentdroid.integration.toolRegistryWithSubagents
import com.agentdroid.integration.SubagentTimelineHub
import com.agentdroid.settings.AppLanguage
import com.agentdroid.settings.AppSettings
import com.agentdroid.settings.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

class ProvidersViewModel(private val container: AppContainer) : ViewModel() {
    val providers = container.providers.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val testResult = MutableStateFlow<ProviderTestResult?>(null)
    val models = MutableStateFlow<List<AiModel>>(emptyList())
    val loadingModels = MutableStateFlow(false)
    val revealedSecret = MutableStateFlow<Pair<String, String>?>(null)

    fun save(config: ProviderConfig, secret: String, onSaved: () -> Unit = {}) = viewModelScope.launch {
        config.secretAlias?.let { alias -> if (secret.isNotBlank()) container.secretStore.put(alias, secret) }
        container.providers.save(config.toEntity())
        onSaved()
    }
    fun create(name: String, kind: ProviderKind, secret: String, baseUrl: String?, model: String?, organization: String? = null, appName: String? = null, siteUrl: String? = null, headers: Map<String, String> = emptyMap(), onSaved: () -> Unit = {}) {
        if (name.isBlank() || (kind != ProviderKind.COMPATIBLE && secret.isBlank()) || (kind == ProviderKind.COMPATIBLE && baseUrl.isNullOrBlank())) return
        val id = UUID.randomUUID().toString()
        save(ProviderConfig(id, name, kind, baseUrl, model, "secret_$id", organizationId = organization?.ifBlank { null }, appName = appName?.ifBlank { null }, siteUrl = siteUrl?.ifBlank { null }, customHeaders = headers), secret, onSaved)
    }
    fun test(config: ProviderConfigEntity, onResult: (ProviderTestResult) -> Unit = {}) = viewModelScope.launch { val impl = container.providerRegistry.get(ProviderKind.valueOf(config.kind)) ?: return@launch; val outcome = impl.testConnection(config.toModel(), config.secretAlias?.let(container.secretStore::get).orEmpty()); testResult.value = outcome; onResult(outcome) }
    fun loadModels(config: ProviderConfigEntity) = viewModelScope.launch { loadingModels.value = true; val impl = container.providerRegistry.get(ProviderKind.valueOf(config.kind)); models.value = impl?.listModels(config.toModel(), config.secretAlias?.let(container.secretStore::get).orEmpty())?.getOrDefault(emptyList()).orEmpty(); loadingModels.value = false }
    fun selectModel(providerId: String, model: String) = viewModelScope.launch { container.providers.setModel(providerId, model) }
    fun toggle(config: ProviderConfigEntity) = viewModelScope.launch { container.providers.setEnabled(config.id, !config.enabled) }
    fun reveal(config: ProviderConfigEntity) { config.secretAlias?.let { alias -> container.secretStore.get(alias)?.let { value -> revealedSecret.value = alias to value } } }
    fun hideSecret() { revealedSecret.value = null }
    fun masked(config: ProviderConfigEntity) = config.secretAlias?.let(container.secretStore::mask).orEmpty()
    fun replaceSecret(config: ProviderConfigEntity, secret: String) = viewModelScope.launch { if (secret.isBlank()) return@launch; val alias = config.secretAlias ?: "secret_${config.id}"; container.secretStore.put(alias, secret); container.providers.save(config.copy(secretAlias = alias)) }
    fun deleteSecret(config: ProviderConfigEntity) = viewModelScope.launch { config.secretAlias?.let(container.secretStore::delete); container.providers.save(config.copy(secretAlias = null)) }
    fun delete(config: ProviderConfigEntity) = viewModelScope.launch { config.secretAlias?.let(container.secretStore::delete); container.providers.delete(config.id) }
}

data class ToolCardUi(
    val callId: String,
    val toolName: String,
    val path: String? = null,
    val status: String = "RUNNING",
    val summary: String = "",
    val durationMs: Long? = null,
    val changeSetId: String? = null,
    val added: Int = 0,
    val removed: Int = 0
)

class ChatViewModel(private val container: AppContainer) : ViewModel() {
    val conversations = container.conversations.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val providers = container.providers.observeAll().map { it.filter(ProviderConfigEntity::enabled) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val workspaces = container.workspaces.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val skills = container.skills.observeAll().map { it.filter(SkillEntity::enabled) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedConversationId = MutableStateFlow<String?>(null)
    val selectedProviderId = MutableStateFlow<String?>(null)
    val selectedModelId = MutableStateFlow<String?>(null)
    val selectedWorkspaceId = MutableStateFlow<String?>(null)
    val mode = MutableStateFlow(AgentMode.CHAT)
    val activeConversationSkillIds = MutableStateFlow<Set<String>>(emptySet())
    val phase = MutableStateFlow(ChatPhase.IDLE)
    val error = MutableStateFlow<AppError?>(null)
    val timeline = MutableStateFlow<List<AgentStep>>(emptyList())
    val toolCards = MutableStateFlow<List<ToolCardUi>>(emptyList())
    val pendingPermission = container.permissionCoordinator.pending
    val providerSupportsTools: StateFlow<Boolean> = combine(providers, selectedProviderId) { rows, selected ->
        val config = rows.firstOrNull { it.id == selected } ?: rows.firstOrNull()
        config?.let { container.providerRegistry.get(ProviderKind.valueOf(it.kind))?.capabilities?.toolCalling } == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var activeJob: Job? = null
    private var partialText = ""

    fun messages(id: String): Flow<List<MessageEntity>> = container.messages.observe(id)

    fun openConversation(id: String) = viewModelScope.launch {
        val conversation = container.conversations.get(id) ?: return@launch
        selectedConversationId.value = conversation.id
        selectedProviderId.value = conversation.providerId
        selectedModelId.value = conversation.modelId
        selectedWorkspaceId.value = conversation.workspaceId
    }

    fun newConversation(onCreated: (String) -> Unit = {}) = viewModelScope.launch {
        val provider = providers.value.firstOrNull()
        val workspace = workspaces.value.firstOrNull()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        container.conversations.save(ConversationEntity(id, "New conversation", now, now, provider?.id, provider?.modelId, workspace?.id))
        selectedConversationId.value = id
        selectedProviderId.value = provider?.id
        selectedModelId.value = provider?.modelId
        selectedWorkspaceId.value = workspace?.id
        mode.value = AgentMode.CHAT
        onCreated(id)
    }

    fun chooseProvider(id: String) { selectedProviderId.value = id; selectedModelId.value = providers.value.firstOrNull { it.id == id }?.modelId; if (!supportsSelectedProviderTools() && mode.value != AgentMode.CHAT) mode.value = AgentMode.CHAT; persistSelection() }
    fun chooseModel(id: String) { selectedModelId.value = id; persistSelection() }
    fun chooseWorkspace(id: String?) { selectedWorkspaceId.value = id; persistSelection() }
    fun chooseMode(value: AgentMode) {
        if (value != AgentMode.CHAT && !supportsSelectedProviderTools()) {
            error.value = AppError.Provider("Provider lacks tool calling", "This provider does not support Agent/Plan tool calling", false)
            mode.value = AgentMode.CHAT
        } else mode.value = value
    }
    fun toggleConversationSkill(id: String) { activeConversationSkillIds.value = activeConversationSkillIds.value.toMutableSet().also { if (!it.add(id)) it.remove(id) } }

    private fun supportsSelectedProviderTools(): Boolean {
        val config = providers.value.firstOrNull { it.id == selectedProviderId.value } ?: providers.value.firstOrNull() ?: return false
        return container.providerRegistry.get(ProviderKind.valueOf(config.kind))?.capabilities?.toolCalling == true
    }

    private fun persistSelection() = viewModelScope.launch {
        val id = selectedConversationId.value ?: return@launch
        val old = container.conversations.get(id) ?: return@launch
        container.conversations.save(old.copy(providerId = selectedProviderId.value, modelId = selectedModelId.value, workspaceId = selectedWorkspaceId.value, updatedAt = System.currentTimeMillis()))
    }

    fun send(text: String) {
        val id = selectedConversationId.value ?: return
        val provider = providers.value.firstOrNull { it.id == selectedProviderId.value } ?: providers.value.firstOrNull()
        if (provider == null) {
            error.value = AppError.Provider("No enabled provider", "Configure an AI provider first", false)
            phase.value = ChatPhase.FAILED
            return
        }
        activeJob?.cancel()
        activeJob = viewModelScope.launch { sendInternal(id, text, provider, true) }
    }

    fun retry() {
        val id = selectedConversationId.value ?: return
        val provider = providers.value.firstOrNull { it.id == selectedProviderId.value } ?: providers.value.firstOrNull() ?: return
        viewModelScope.launch {
            val user = container.messages.observe(id).first().lastOrNull { it.role == "USER" } ?: return@launch
            sendInternal(id, user.content, provider, false)
        }
    }
    fun regenerate() = retry()
    fun editUserMessage(message: MessageEntity, replacement: String) = viewModelScope.launch { if (replacement.isBlank()) return@launch; container.messages.save(message.copy(content = replacement, updatedAt = System.currentTimeMillis())); container.messages.deleteAfter(message.conversationId, message.createdAt); phase.value = ChatPhase.IDLE }

    private suspend fun sendInternal(id: String, text: String, provider: ProviderConfigEntity, addUser: Boolean) {
        if (text.isBlank() || phase.value == ChatPhase.STREAMING) return
        error.value = null
        timeline.value = emptyList()
        toolCards.value = emptyList()
        if (addUser) container.messages.save(MessageEntity(UUID.randomUUID().toString(), id, "USER", text, MessageStatus.COMPLETED.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, provider.modelId))
        val assistantId = UUID.randomUUID().toString()
        partialText = ""
        container.messages.save(MessageEntity(assistantId, id, "ASSISTANT", "", MessageStatus.STREAMING.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, provider.modelId))
        phase.value = ChatPhase.SUBMITTING
        try {
            if (mode.value == AgentMode.CHAT) sendChat(id, assistantId, provider) else sendAgent(id, assistantId, text, provider)
        } catch (cancelled: CancellationException) {
            phase.value = ChatPhase.CANCELLED
            container.messages.save(MessageEntity(assistantId, id, "ASSISTANT", partialText, MessageStatus.CANCELLED.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, selectedModelId.value ?: provider.modelId))
            throw cancelled
        } catch (failure: Throwable) {
            val mapped = AppError.Unknown(failure.message ?: "unknown")
            error.value = mapped
            phase.value = ChatPhase.FAILED
            container.messages.save(MessageEntity(assistantId, id, "ASSISTANT", partialText, MessageStatus.FAILED.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, selectedModelId.value ?: provider.modelId, errorJson = mapped.technicalMessage))
        }
    }

    private suspend fun sendChat(id: String, assistantId: String, provider: ProviderConfigEntity) {
        val history = container.messages.observe(id).first().filter { it.content.isNotBlank() && it.status == MessageStatus.COMPLETED.name }.map { ChatMessage(if (it.role == "USER") MessageRole.USER else MessageRole.ASSISTANT, it.content) }
        val impl = container.providerRegistry.get(ProviderKind.valueOf(provider.kind)) ?: throw IllegalStateException("provider implementation missing")
        impl.streamChat(ChatRequest(history, selectedModelId.value ?: provider.modelId.orEmpty()), provider.toModel(), provider.secretAlias?.let(container.secretStore::get).orEmpty()).collect { event ->
            when (event) {
                AiStreamEvent.Started -> phase.value = ChatPhase.STREAMING
                is AiStreamEvent.TextDelta -> appendAssistant(assistantId, id, provider, event.text)
                AiStreamEvent.Completed -> completeAssistant(assistantId, id, provider)
                is AiStreamEvent.Error -> failAssistant(assistantId, id, provider, event.error)
                else -> Unit
            }
        }
    }

    private suspend fun sendAgent(id: String, assistantId: String, userRequest: String, provider: ProviderConfigEntity) {
        val workspaceId = selectedWorkspaceId.value ?: throw IllegalStateException("Select a workspace for Plan or Agent mode")
        val impl = container.providerRegistry.get(ProviderKind.valueOf(provider.kind)) ?: throw IllegalStateException("provider implementation missing")
        if (!impl.capabilities.toolCalling) throw IllegalStateException("Selected provider does not support tool calling")
        val session = AgentSession(UUID.randomUUID().toString(), id, workspaceId, mode.value, provider.id, selectedModelId.value ?: provider.modelId.orEmpty())
        val model = ProviderAgentModelClient(impl, provider.toModel(), provider.secretAlias?.let(container.secretStore::get).orEmpty())
        val registry = toolRegistryWithSubagents(
            container.toolRegistry, model, session.modelId, container.permissionEngine, container.auditSink
        ) { items ->
            items.forEach { item ->
                container.subagentEvents.save(
                    SubagentDelegationEvent(item.subagentId, item, item.taskId, item.startedAt ?: System.currentTimeMillis())
                )
            }
        }

        val planningContext = buildContextSnapshot(workspaceId, id, userRequest)
        val planningOutcome = ModelPlanningCoordinator(container, model, registry).ensurePlan(session, userRequest, planningContext)
        planningOutcome.planning?.let { planning ->
            val label = when (planning.source) {
                com.agentdroid.core.tasks.PlannerSource.MODEL -> "Plan created by model"
                com.agentdroid.core.tasks.PlannerSource.REPAIRED_MODEL -> "Model plan repaired and validated"
                com.agentdroid.core.tasks.PlannerSource.DETERMINISTIC_FALLBACK -> "Fallback plan created after model planning failed"
            }
            timeline.value = (timeline.value + AgentStep(label, AgentStepStatus.SUCCEEDED)).takeLast(100)
        }

        val source = ContextSource { current -> buildContextSnapshot(current.workspaceId, id, userRequest) }
        val loop = AgentLoop(registry, container.permissionEngine, ContextManager(source), container.auditSink)
        var failed = false
        loop.run(session, userRequest, model).collect { event ->
            when (event) {
                is AgentEvent.TextDelta -> appendAssistant(assistantId, id, provider, event.text)
                is AgentEvent.Timeline -> timeline.value = (timeline.value + event.step).takeLast(100)
                is AgentEvent.ToolCallStarted -> upsertToolCard(ToolCardUi(event.call.id, event.call.name, event.call.input["path"]?.jsonPrimitive?.contentOrNull))
                is AgentEvent.ToolCallCompleted -> upsertToolCard(toolCards.value.firstOrNull { it.callId == event.call.id }?.copy(path = event.call.input["path"]?.jsonPrimitive?.contentOrNull) ?: ToolCardUi(event.call.id, event.call.name, event.call.input["path"]?.jsonPrimitive?.contentOrNull))
                is AgentEvent.PermissionRequired -> upsertToolCard(ToolCardUi(event.toolCallId(), event.request.definition.name, event.request.preview?.path, "WAITING_PERMISSION", event.request.preview?.summary.orEmpty()))
                is AgentEvent.ToolFinished -> {
                    val added = event.result.output["added"]?.jsonPrimitive?.intOrNull ?: 0
                    val removed = event.result.output["removed"]?.jsonPrimitive?.intOrNull ?: 0
                    upsertToolCard(ToolCardUi(event.call.id, event.call.name, event.call.input["path"]?.jsonPrimitive?.contentOrNull, if (event.result.success) "SUCCEEDED" else "FAILED", event.result.summary, event.durationMs, event.result.changeSetId, added, removed))
                }
                is AgentEvent.FinalAnswer -> {
                    if (partialText.isBlank() && event.text.isNotBlank()) {
                        partialText = event.text
                        container.messages.save(MessageEntity(assistantId, id, "ASSISTANT", partialText, MessageStatus.STREAMING.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, selectedModelId.value ?: provider.modelId))
                    }
                }
                is AgentEvent.Failed -> {
                    failed = true
                    failAssistant(assistantId, id, provider, AppError.Provider(event.error.technicalMessage, event.error.userMessage, event.error.recoverable))
                }
                AgentEvent.Done -> if (!failed) completeAssistant(assistantId, id, provider)
                else -> Unit
            }
        }
    }

    private suspend fun buildContextSnapshot(workspaceId: String, conversationId: String, currentRequest: String): ContextSnapshot {
        var history = container.messages.observe(conversationId).first()
            .filter { it.status == MessageStatus.COMPLETED.name && it.content.isNotBlank() }
            .map { AgentMessage(if (it.role == "USER") AgentMessageRole.USER else AgentMessageRole.ASSISTANT, it.content) }
        if (history.lastOrNull()?.role == AgentMessageRole.USER && history.lastOrNull()?.content == currentRequest) history = history.dropLast(1)
        val workspace = container.workspaces.get(workspaceId)
        val fileSystem = container.workspaceFileSystem(workspaceId)
        val topLevel = runCatching { fileSystem.list("", recursive = false, maxResults = 120) }.getOrDefault(emptyList())
        val workspaceSummary = buildString {
            append("Workspace: ").append(workspace?.name ?: workspaceId).append('\n')
            topLevel.forEach { append(if (it.directory) "[dir] " else "[file] ").append(it.path).append('\n') }
        }
        val selectedFiles = workspace?.lastOpenedFile?.let { path ->
            runCatching { fileSystem.read(path, 1, 400) }.getOrNull()?.takeIf { !it.binary }?.let { listOf(ContextFile(path, it.content.orEmpty(), it.truncated)) }
        }.orEmpty()
        val memories = container.memory.observeAll().first().filter { it.enabled && (it.scope == MemoryScope.GLOBAL.name || it.workspaceId == workspaceId) }.map { "${it.title}: ${it.content}" }
        val conversationSkills = activeConversationSkillIds.value
        val skillInstructions = container.skills.observeAll().first().filter { it.enabled && (it.scope == "GLOBAL" || it.workspaceId == workspaceId || it.id in conversationSkills) }.map { "${it.name}\n${it.instructions}" }
        val task = container.taskEngine.list(workspaceId, conversationId).firstOrNull { !it.status.isTerminal }
        val artifacts = container.artifactRepository.list(ArtifactListFilter(workspaceId, conversationId = conversationId))
        val browser = container.browserEngine.sessions().firstOrNull {
            it.metadata.value.workspaceId == workspaceId && it.metadata.value.conversationId == conversationId
        }
        val browserSummary = browser?.let {
            val state = it.state.value
            "${state.title}\n${state.currentUrl.orEmpty()}\nloading=${state.loading} tabs=${it.metadata.value.tabs.size}"
        }.orEmpty()
        val subagents = SubagentTimelineHub.items.value.takeLast(12).map {
            "${it.role}: ${it.status} — ${it.label}${it.failureSummary?.let { failure -> " — $failure" }.orEmpty()}"
        }
        return ContextSnapshot(
            conversation = history,
            workspaceSummary = workspaceSummary,
            selectedFiles = selectedFiles,
            memories = memories,
            skills = skillInstructions,
            activeTaskSummary = task?.let { "${it.title}: ${it.status} (${it.progress}%)" }.orEmpty(),
            taskSteps = task?.plan?.steps?.map { "${it.status}: ${it.title}" }.orEmpty(),
            browserStateSummary = browserSummary,
            artifactReferences = artifacts.take(20).map { "${it.id}: ${it.title} (${it.type})" },
            subagentResults = subagents
        )
    }

    private suspend fun appendAssistant(assistantId: String, conversationId: String, provider: ProviderConfigEntity, delta: String) {
        partialText += delta
        phase.value = ChatPhase.STREAMING
        container.messages.save(MessageEntity(assistantId, conversationId, "ASSISTANT", partialText, MessageStatus.STREAMING.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, selectedModelId.value ?: provider.modelId))
    }

    private suspend fun completeAssistant(assistantId: String, conversationId: String, provider: ProviderConfigEntity) {
        phase.value = ChatPhase.COMPLETED
        container.messages.save(MessageEntity(assistantId, conversationId, "ASSISTANT", partialText, MessageStatus.COMPLETED.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, selectedModelId.value ?: provider.modelId))
    }

    private suspend fun failAssistant(assistantId: String, conversationId: String, provider: ProviderConfigEntity, mapped: AppError) {
        error.value = mapped
        phase.value = ChatPhase.FAILED
        container.messages.save(MessageEntity(assistantId, conversationId, "ASSISTANT", partialText, MessageStatus.FAILED.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, selectedModelId.value ?: provider.modelId, errorJson = mapped.technicalMessage))
    }

    private fun upsertToolCard(card: ToolCardUi) {
        val mutable = toolCards.value.toMutableList()
        val index = mutable.indexOfFirst { it.callId == card.callId }
        if (index >= 0) mutable[index] = card else mutable += card
        toolCards.value = mutable
    }

    fun allowPermission(scope: PermissionScope) = container.permissionCoordinator.resolve(PermissionDecision.ALLOW, scope)
    fun denyPermission() = container.permissionCoordinator.resolve(PermissionDecision.DENY, PermissionScope.ONCE)
    fun stop() { container.permissionCoordinator.denyPending(); activeJob?.cancel(); phase.value = ChatPhase.CANCELLED }

    private fun AgentEvent.PermissionRequired.toolCallId() = request.toolCall.id
}

class ConversationsViewModel(private val container: AppContainer) : ViewModel() { val conversations = container.conversations.observeIncludingArchived().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()); fun search(query: String): Flow<List<ConversationEntity>> = container.conversations.observeSearch(query); fun rename(id: String, title: String) = viewModelScope.launch { if (title.isNotBlank()) container.conversations.rename(id, title) }; fun archive(id: String) = viewModelScope.launch { container.conversations.archive(id, true) }; fun unarchive(id: String) = viewModelScope.launch { container.conversations.archive(id, false) }; fun delete(id: String) = viewModelScope.launch { container.conversations.delete(id) } }

class WorkspacesViewModel(private val container: AppContainer) : ViewModel() {
    val items = container.workspaces.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun save(item: WorkspaceEntity) = viewModelScope.launch {
        val root = container.workspaceRoot(item.id)
        if (!root.exists()) root.mkdirs()
        container.workspaces.save(item.copy(rootPath = root.canonicalPath, updatedAt = System.currentTimeMillis()))
    }
    fun delete(id: String) = viewModelScope.launch { container.deleteWorkspaceFiles(id); container.workspaces.delete(id) }
}

class WorkspaceFilesViewModel(private val container: AppContainer) : ViewModel() {
    val workspace = MutableStateFlow<WorkspaceEntity?>(null)
    val currentPath = MutableStateFlow("")
    val entries = MutableStateFlow<List<WorkspaceFileInfo>>(emptyList())
    val editorPath = MutableStateFlow<String?>(null)
    val editorText = MutableStateFlow("")
    val originalText = MutableStateFlow("")
    val editorError = MutableStateFlow<String?>(null)
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    fun openWorkspace(id: String) = viewModelScope.launch {
        val item = container.workspaces.get(id) ?: return@launch
        container.workspaceFileSystem(id)
        workspace.value = item
        currentPath.value = ""
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val id = workspace.value?.id ?: return@launch
        entries.value = runCatching { container.workspaceFileSystem(id).list(currentPath.value) }.getOrElse { editorError.value = it.message; emptyList() }
    }

    fun openDirectory(path: String) { currentPath.value = path; refresh() }
    fun goUp() { currentPath.value = currentPath.value.substringBeforeLast('/', ""); refresh() }

    fun openFile(path: String) = viewModelScope.launch {
        val id = workspace.value?.id ?: return@launch
        val result = runCatching { container.workspaceFileSystem(id).read(path) }.getOrElse { editorError.value = it.message; return@launch }
        if (result.binary) { editorError.value = "Binary files cannot be opened in the text editor"; return@launch }
        editorPath.value = path
        editorText.value = result.content.orEmpty()
        originalText.value = result.content.orEmpty()
        undoStack.clear(); redoStack.clear()
        container.workspaces.setLastOpenedFile(id, path)
    }

    fun updateEditor(value: String) {
        if (value == editorText.value) return
        undoStack.addLast(editorText.value)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        editorText.value = value
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(editorText.value)
        editorText.value = previous
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(editorText.value)
        editorText.value = next
    }

    fun saveEditor() = viewModelScope.launch {
        val id = workspace.value?.id ?: return@launch
        val path = editorPath.value ?: return@launch
        val fs = container.workspaceFileSystem(id)
        val (before, hash) = runCatching { fs.readTextForMutation(path) }.getOrElse { editorError.value = it.message; return@launch }
        val after = editorText.value
        if (before == after) return@launch
        val change = FileChange(path, beforeHash = hash, afterHash = hashText(after), beforeContent = before, afterContent = after, diff = container.diffEngine.diff(path, before, after).unifiedDiff, changeType = FileChangeType.MODIFY)
        runCatching { container.changeSetManager(id).propose(listOf(change)).let { container.changeSetManager(id).accept(it.id) } }.onSuccess {
            originalText.value = after
            refresh()
        }.onFailure { editorError.value = it.message }
    }

    fun createFile(name: String) = viewModelScope.launch {
        val id = workspace.value?.id ?: return@launch
        val path = childPath(name) ?: return@launch
        val change = FileChange(path, afterHash = hashText(""), afterContent = "", diff = container.diffEngine.diff(path, "", "").unifiedDiff, changeType = FileChangeType.CREATE)
        applyDirect(id, change) { openFile(path) }
    }

    fun createFolder(name: String) = viewModelScope.launch {
        val id = workspace.value?.id ?: return@launch
        val path = childPath(name) ?: return@launch
        applyDirect(id, FileChange(path, changeType = FileChangeType.CREATE_DIRECTORY)) { refresh() }
    }

    fun move(path: String, destination: String) = viewModelScope.launch {
        val id = workspace.value?.id ?: return@launch
        val fs = container.workspaceFileSystem(id)
        val change = runCatching { FileChange(path, destinationPath = destination, beforeHash = fs.fingerprint(path), changeType = FileChangeType.MOVE) }.getOrElse { editorError.value = it.message; return@launch }
        applyDirect(id, change) { refresh() }
    }

    fun delete(path: String) = viewModelScope.launch {
        val id = workspace.value?.id ?: return@launch
        val fs = container.workspaceFileSystem(id)
        val change = runCatching { FileChange(path, beforeHash = fs.fingerprint(path), changeType = FileChangeType.DELETE) }.getOrElse { editorError.value = it.message; return@launch }
        applyDirect(id, change) { if (editorPath.value == path) editorPath.value = null; refresh() }
    }

    private suspend fun applyDirect(id: String, change: FileChange, onSuccess: () -> Unit) {
        runCatching {
            val proposal = container.changeSetManager(id).propose(listOf(change))
            container.changeSetManager(id).accept(proposal.id)
        }.onSuccess { onSuccess() }.onFailure { editorError.value = it.message }
    }

    private fun childPath(name: String): String? {
        val clean = name.trim()
        if (clean.isBlank() || clean.contains('/') || clean.contains('\\') || clean == "." || clean == "..") { editorError.value = "Invalid file name"; return null }
        return listOf(currentPath.value, clean).filter { it.isNotBlank() }.joinToString("/")
    }
}

class ChangeSetsViewModel(private val container: AppContainer) : ViewModel() {
    private val workspaceId = MutableStateFlow<String?>(null)
    val items = workspaceId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else container.changeSetStore.observe(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selected = MutableStateFlow<ChangeSet?>(null)
    val error = MutableStateFlow<String?>(null)

    fun setWorkspace(id: String) { workspaceId.value = id }
    fun select(id: String) = viewModelScope.launch { selected.value = workspaceId.value?.let { container.changeSetManager(it).get(id) } }
    fun accept(id: String) = mutate(id) { manager, value -> manager.accept(value) }
    fun reject(id: String) = mutate(id) { manager, value -> manager.reject(value) }
    fun revert(id: String) = mutate(id) { manager, value -> manager.revert(value) }
    fun edit(id: String, path: String, content: String) = viewModelScope.launch {
        val workspace = workspaceId.value ?: return@launch
        runCatching { container.changeSetManager(workspace).edit(id, path, content) }.onSuccess { selected.value = it }.onFailure { error.value = it.message }
    }
    private fun mutate(id: String, operation: suspend (ChangeSetManager, String) -> ChangeSet) = viewModelScope.launch {
        val workspace = workspaceId.value ?: return@launch
        runCatching { operation(container.changeSetManager(workspace), id) }.onSuccess { selected.value = it }.onFailure { error.value = it.message }
    }
}

class PermissionsViewModel(private val container: AppContainer) : ViewModel() {
    val rules: StateFlow<List<PermissionRule>> = container.permissionRuleStore.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val audit = container.auditRepository.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun deleteRule(id: String) = viewModelScope.launch { container.permissionEngine.removeRule(id) }
}

class MemoryViewModel(private val container: AppContainer) : ViewModel() { val items = container.memory.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()); fun save(item: MemoryEntryEntity) = viewModelScope.launch { container.memory.save(item) }; fun toggle(item: MemoryEntryEntity) = viewModelScope.launch { container.memory.setEnabled(item.id, !item.enabled) }; fun delete(id: String) = viewModelScope.launch { container.memory.delete(id) } }
class SkillsViewModel(private val container: AppContainer) : ViewModel() { val items = container.skills.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()); fun save(item: SkillEntity) = viewModelScope.launch { container.skills.save(item) }; fun toggle(item: SkillEntity) = viewModelScope.launch { container.skills.setEnabled(item.id, !item.enabled) }; fun delete(id: String) = viewModelScope.launch { container.skills.delete(id) } }
class SettingsViewModel(private val container: AppContainer) : ViewModel() { val settings = container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings()); fun language(value: AppLanguage) = viewModelScope.launch { container.settings.setLanguage(value) }; fun theme(value: AppTheme) = viewModelScope.launch { container.settings.setTheme(value) }; fun dynamicColor(value: Boolean) = viewModelScope.launch { container.settings.setDynamicColor(value) }; fun developerMode(value: Boolean) = viewModelScope.launch { container.settings.setDeveloperMode(value) } }

internal fun ProviderConfigEntity.toModel() = ProviderConfig(id, name, ProviderKind.valueOf(kind), baseUrl, modelId, secretAlias, organizationId, appName, siteUrl, runCatching { Json.decodeFromString<Map<String, String>>(customHeadersJson) }.getOrDefault(emptyMap()), enabled)
private fun ProviderConfig.toEntity() = ProviderConfigEntity(id, name, kind.name, baseUrl, modelId, secretAlias, organizationId, appName, siteUrl, Json.encodeToString(customHeaders), enabled)

class ContainerViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val created: ViewModel = when {
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(container)
            modelClass.isAssignableFrom(ProvidersViewModel::class.java) -> ProvidersViewModel(container)
            modelClass.isAssignableFrom(ConversationsViewModel::class.java) -> ConversationsViewModel(container)
            modelClass.isAssignableFrom(WorkspacesViewModel::class.java) -> WorkspacesViewModel(container)
            modelClass.isAssignableFrom(WorkspaceFilesViewModel::class.java) -> WorkspaceFilesViewModel(container)
            modelClass.isAssignableFrom(ChangeSetsViewModel::class.java) -> ChangeSetsViewModel(container)
            modelClass.isAssignableFrom(PermissionsViewModel::class.java) -> PermissionsViewModel(container)
            modelClass.isAssignableFrom(MemoryViewModel::class.java) -> MemoryViewModel(container)
            modelClass.isAssignableFrom(SkillsViewModel::class.java) -> SkillsViewModel(container)
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(container)
            modelClass.isAssignableFrom(BrowserViewModel::class.java) -> BrowserViewModel(container)
            modelClass.isAssignableFrom(TasksViewModel::class.java) -> TasksViewModel(container)
            modelClass.isAssignableFrom(ArtifactsViewModel::class.java) -> ArtifactsViewModel(container)
            modelClass.isAssignableFrom(SubagentTimelineViewModel::class.java) -> SubagentTimelineViewModel()
            else -> error("Unknown ViewModel ${modelClass.name}")
        }
        return created as T
    }
}
