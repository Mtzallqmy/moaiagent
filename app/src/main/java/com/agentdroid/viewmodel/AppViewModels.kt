package com.agentdroid.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.agentdroid.AppContainer
import com.agentdroid.core.ai.ProviderTestResult
import com.agentdroid.core.model.*
import com.agentdroid.data.database.*
import com.agentdroid.settings.AppLanguage
import com.agentdroid.settings.AppSettings
import com.agentdroid.settings.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

class ChatViewModel(private val container: AppContainer) : ViewModel() {
    val conversations = container.conversations.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val providers = container.providers.observeAll().map { it.filter(ProviderConfigEntity::enabled) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedConversationId = MutableStateFlow<String?>(null)
    val selectedProviderId = MutableStateFlow<String?>(null)
    val selectedModelId = MutableStateFlow<String?>(null)
    val phase = MutableStateFlow(ChatPhase.IDLE)
    val error = MutableStateFlow<AppError?>(null)
    private var activeJob: Job? = null
    private var partialText = ""

    fun messages(id: String): Flow<List<MessageEntity>> = container.messages.observe(id)
    fun openConversation(id: String) = viewModelScope.launch { val conversation = container.conversations.get(id) ?: return@launch; selectedConversationId.value = conversation.id; selectedProviderId.value = conversation.providerId; selectedModelId.value = conversation.modelId }
    fun newConversation(onCreated: (String) -> Unit = {}) = viewModelScope.launch { val provider = providers.value.firstOrNull(); val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis(); container.conversations.save(ConversationEntity(id, "New conversation", now, now, provider?.id, provider?.modelId)); selectedConversationId.value = id; selectedProviderId.value = provider?.id; selectedModelId.value = provider?.modelId; onCreated(id) }
    fun chooseProvider(id: String) { selectedProviderId.value = id; selectedModelId.value = providers.value.firstOrNull { it.id == id }?.modelId; persistSelection() }
    fun chooseModel(id: String) { selectedModelId.value = id; persistSelection() }
    private fun persistSelection() = viewModelScope.launch { val id = selectedConversationId.value ?: return@launch; val old = container.conversations.get(id) ?: return@launch; container.conversations.save(old.copy(providerId = selectedProviderId.value, modelId = selectedModelId.value, updatedAt = System.currentTimeMillis())) }
    fun send(text: String) { val id = selectedConversationId.value ?: return; val provider = providers.value.firstOrNull { it.id == selectedProviderId.value } ?: providers.value.firstOrNull(); if (provider == null) { error.value = AppError.Provider("No enabled provider", "Configure an AI provider first", false); phase.value = ChatPhase.FAILED; return }; activeJob?.cancel(); activeJob = viewModelScope.launch { sendInternal(id, text, provider, true) } }
    fun retry() { val id = selectedConversationId.value ?: return; val provider = providers.value.firstOrNull { it.id == selectedProviderId.value } ?: providers.value.firstOrNull() ?: return; viewModelScope.launch { val user = container.messages.observe(id).first().lastOrNull { it.role == "USER" } ?: return@launch; sendInternal(id, user.content, provider, false) } }
    fun regenerate() = retry()
    fun editUserMessage(message: MessageEntity, replacement: String) = viewModelScope.launch { if (replacement.isBlank()) return@launch; container.messages.save(message.copy(content = replacement, updatedAt = System.currentTimeMillis())); container.messages.deleteAfter(message.conversationId, message.createdAt); phase.value = ChatPhase.IDLE }
    private suspend fun sendInternal(id: String, text: String, provider: ProviderConfigEntity, addUser: Boolean) {
        if (text.isBlank() || phase.value == ChatPhase.STREAMING) return
        error.value = null
        if (addUser) container.messages.save(MessageEntity(UUID.randomUUID().toString(), id, "USER", text, MessageStatus.COMPLETED.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, provider.modelId))
        val assistantId = UUID.randomUUID().toString(); partialText = ""; container.messages.save(MessageEntity(assistantId, id, "ASSISTANT", "", MessageStatus.STREAMING.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, provider.modelId)); phase.value = ChatPhase.SUBMITTING
        try {
            val history = container.messages.observe(id).first().filter { it.content.isNotBlank() && it.status == MessageStatus.COMPLETED.name }.map { ChatMessage(if (it.role == "USER") MessageRole.USER else MessageRole.ASSISTANT, it.content) }
            val impl = container.providerRegistry.get(ProviderKind.valueOf(provider.kind)) ?: throw IllegalStateException("provider implementation missing")
            impl.streamChat(ChatRequest(history, selectedModelId.value ?: provider.modelId.orEmpty()), provider.toModel(), provider.secretAlias?.let(container.secretStore::get).orEmpty()).collect { event ->
                when (event) {
                    AiStreamEvent.Started -> phase.value = ChatPhase.STREAMING
                    is AiStreamEvent.TextDelta -> { partialText += event.text; phase.value = ChatPhase.STREAMING; container.messages.save(MessageEntity(assistantId, id, "ASSISTANT", partialText, MessageStatus.STREAMING.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, selectedModelId.value ?: provider.modelId)) }
                    AiStreamEvent.Completed -> { phase.value = ChatPhase.COMPLETED; container.messages.save(MessageEntity(assistantId, id, "ASSISTANT", partialText, MessageStatus.COMPLETED.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, selectedModelId.value ?: provider.modelId)) }
                    is AiStreamEvent.Error -> { error.value = event.error; phase.value = ChatPhase.FAILED; container.messages.save(MessageEntity(assistantId, id, "ASSISTANT", partialText, MessageStatus.FAILED.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, selectedModelId.value ?: provider.modelId, errorJson = event.error.technicalMessage)) }
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) { phase.value = ChatPhase.CANCELLED; container.messages.save(MessageEntity(assistantId, id, "ASSISTANT", partialText, MessageStatus.CANCELLED.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, selectedModelId.value ?: provider.modelId)); throw cancelled
        } catch (failure: Throwable) { val mapped = AppError.Unknown(failure.message ?: "unknown"); error.value = mapped; phase.value = ChatPhase.FAILED; container.messages.save(MessageEntity(assistantId, id, "ASSISTANT", partialText, MessageStatus.FAILED.name, System.currentTimeMillis(), System.currentTimeMillis(), provider.id, selectedModelId.value ?: provider.modelId, errorJson = mapped.technicalMessage)) }
    }
    fun stop() { activeJob?.cancel(); phase.value = ChatPhase.CANCELLED }
}

class ConversationsViewModel(private val container: AppContainer) : ViewModel() { val conversations = container.conversations.observeIncludingArchived().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()); fun search(query: String): Flow<List<ConversationEntity>> = container.conversations.observeSearch(query); fun rename(id: String, title: String) = viewModelScope.launch { if (title.isNotBlank()) container.conversations.rename(id, title) }; fun archive(id: String) = viewModelScope.launch { container.conversations.archive(id, true) }; fun unarchive(id: String) = viewModelScope.launch { container.conversations.archive(id, false) }; fun delete(id: String) = viewModelScope.launch { container.conversations.delete(id) } }
class WorkspacesViewModel(private val container: AppContainer) : ViewModel() { val items = container.workspaces.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()); fun save(item: WorkspaceEntity) = viewModelScope.launch { container.workspaces.save(item) }; fun delete(id: String) = viewModelScope.launch { container.workspaces.delete(id) } }
class MemoryViewModel(private val container: AppContainer) : ViewModel() { val items = container.memory.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()); fun save(item: MemoryEntryEntity) = viewModelScope.launch { container.memory.save(item) }; fun toggle(item: MemoryEntryEntity) = viewModelScope.launch { container.memory.setEnabled(item.id, !item.enabled) }; fun delete(id: String) = viewModelScope.launch { container.memory.delete(id) } }
class SkillsViewModel(private val container: AppContainer) : ViewModel() { val items = container.skills.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()); fun save(item: SkillEntity) = viewModelScope.launch { container.skills.save(item) }; fun toggle(item: SkillEntity) = viewModelScope.launch { container.skills.setEnabled(item.id, !item.enabled) }; fun delete(id: String) = viewModelScope.launch { container.skills.delete(id) } }
class SettingsViewModel(private val container: AppContainer) : ViewModel() { val settings = container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings()); fun language(value: AppLanguage) = viewModelScope.launch { container.settings.setLanguage(value) }; fun theme(value: AppTheme) = viewModelScope.launch { container.settings.setTheme(value) }; fun dynamicColor(value: Boolean) = viewModelScope.launch { container.settings.setDynamicColor(value) }; fun developerMode(value: Boolean) = viewModelScope.launch { container.settings.setDeveloperMode(value) } }

private fun ProviderConfigEntity.toModel() = ProviderConfig(id, name, ProviderKind.valueOf(kind), baseUrl, modelId, secretAlias, organizationId, appName, siteUrl, runCatching { Json.decodeFromString<Map<String, String>>(customHeadersJson) }.getOrDefault(emptyMap()), enabled)
private fun ProviderConfig.toEntity() = ProviderConfigEntity(id, name, kind.name, baseUrl, modelId, secretAlias, organizationId, appName, siteUrl, Json.encodeToString(customHeaders), enabled)

class ContainerViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val created: ViewModel = when { modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(container); modelClass.isAssignableFrom(ProvidersViewModel::class.java) -> ProvidersViewModel(container); modelClass.isAssignableFrom(ConversationsViewModel::class.java) -> ConversationsViewModel(container); modelClass.isAssignableFrom(WorkspacesViewModel::class.java) -> WorkspacesViewModel(container); modelClass.isAssignableFrom(MemoryViewModel::class.java) -> MemoryViewModel(container); modelClass.isAssignableFrom(SkillsViewModel::class.java) -> SkillsViewModel(container); modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(container); else -> error("Unknown ViewModel ${modelClass.name}") }
        return created as T
    }
}
