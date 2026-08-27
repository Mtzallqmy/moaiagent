package com.agentdroid.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdroid.AppContainer
import com.agentdroid.core.artifacts.Artifact
import com.agentdroid.core.artifacts.ArtifactListFilter
import com.agentdroid.core.browser.BrowserSession
import com.agentdroid.core.browser.BrowserSurfaceProvider
import com.agentdroid.integration.SubagentTimelineHub
import com.agentdroid.ui.BrowserUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class BrowserViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow<BrowserUiState?>(null)
    val state: StateFlow<BrowserUiState?> = mutableState.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    private var session: BrowserSession? = null
    private var observation: Job? = null
    private var linked = false

    fun open(workspaceId: String, conversationId: String) {
        if (session?.metadata?.value?.let { it.workspaceId == workspaceId && it.conversationId == conversationId } == true) return
        viewModelScope.launch {
            runCatching { container.browserSessions.getOrCreate(workspaceId, conversationId) }
                .onSuccess { browser ->
                    session = browser
                    observation?.cancel()
                    observation = launch {
                        combine(browser.metadata, browser.state) { metadata, page ->
                            BrowserUiState(
                                sessionId = metadata.sessionId,
                                tabs = metadata.tabs,
                                activeTabId = metadata.activeTabId,
                                page = page,
                                surface = (container.browserEngine as BrowserSurfaceProvider).surface(metadata.sessionId, metadata.activeTabId),
                                linkedToAgent = linked
                            )
                        }.collect { mutableState.value = it }
                    }
                }
                .onFailure { error.value = it.message }
        }
    }

    fun navigate(url: String) = act { navigate(normalizeAddress(url)) }
    fun back() = act { goBack() }
    fun forward() = act { goForward() }
    fun refresh() = act { reloadPage() }
    fun stop() = act { stopLoading() }
    fun newTab() = act { createTab() }
    fun selectTab(tabId: String) = act { switchTab(tabId) }
    fun closeTab(tabId: String) = act { closeTab(tabId) }
    fun setLinked(value: Boolean) { linked = value; mutableState.value = mutableState.value?.copy(linkedToAgent = value) }

    private fun act(block: suspend BrowserSession.() -> Unit) = viewModelScope.launch {
        val current = session ?: return@launch
        runCatching { current.block() }.onFailure { error.value = it.message }
    }

    private fun normalizeAddress(raw: String): String {
        val value = raw.trim()
        return if (value.contains("://")) value else "https://$value"
    }
}

class TasksViewModel(private val container: AppContainer) : ViewModel() {
    val tasks = MutableStateFlow<List<com.agentdroid.core.tasks.Task>>(emptyList())
    val selectedTaskId = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    private var workspaceId: String? = null

    fun open(workspaceId: String) { this.workspaceId = workspaceId; refresh() }
    fun refresh() = viewModelScope.launch {
        val workspace = workspaceId ?: return@launch
        runCatching { container.taskEngine.list(workspace) }.onSuccess { tasks.value = it }.onFailure { error.value = it.message }
    }
    fun select(id: String?) { selectedTaskId.value = id }
    fun pause(id: String) = mutate { container.taskEngine.pause(id, requireWorkspace()) }
    fun cancel(id: String) = mutate { container.taskEngine.cancel(id, requireWorkspace(), "Cancelled by user") }
    fun retry(id: String) = mutate {
        val task = container.taskEngine.get(id, requireWorkspace()) ?: return@mutate
        val step = task.plan.steps.firstOrNull { it.status == com.agentdroid.core.tasks.TaskStatus.FAILED }
            ?: task.plan.steps.firstOrNull { it.id == task.currentStepId }
            ?: return@mutate
        container.taskEngine.retry(id, requireWorkspace(), step.id)
    }
    private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { refresh() }.onFailure { error.value = it.message }
    }
    private fun requireWorkspace() = checkNotNull(workspaceId) { "Workspace is not selected" }
}

class ArtifactsViewModel(private val container: AppContainer) : ViewModel() {
    val artifacts = MutableStateFlow<List<Artifact>>(emptyList())
    val selectedArtifactId = MutableStateFlow<String?>(null)
    val content = MutableStateFlow<String?>(null)
    val truncated = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var workspaceId: String? = null

    fun open(workspaceId: String) { this.workspaceId = workspaceId; refresh() }
    fun refresh() = viewModelScope.launch {
        val workspace = workspaceId ?: return@launch
        runCatching { container.artifactRepository.list(ArtifactListFilter(workspace)) }
            .onSuccess { artifacts.value = it }.onFailure { error.value = it.message }
    }
    fun select(id: String?) {
        selectedArtifactId.value = id
        content.value = null
        truncated.value = false
        val artifact = artifacts.value.firstOrNull { it.id == id } ?: return
        if (!artifact.type.textual) return
        viewModelScope.launch {
            runCatching { container.artifactRepository.read(requireWorkspace(), artifact.id) }
                .onSuccess { content.value = it.content; truncated.value = it.truncated }
                .onFailure { error.value = it.message }
        }
    }
    fun rename(artifact: Artifact, title: String) = mutate {
        container.artifactRepository.rename(requireWorkspace(), artifact.id, title)
    }
    fun delete(artifact: Artifact) = mutate {
        container.artifactRepository.delete(requireWorkspace(), artifact.id)
        if (selectedArtifactId.value == artifact.id) select(null)
    }
    fun export(artifact: Artifact) = mutate {
        container.artifactRepository.export(requireWorkspace(), artifact.id, "Exports/${artifact.filePath.substringAfterLast('/')}")
    }
    private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { refresh() }.onFailure { error.value = it.message }
    }
    private fun requireWorkspace() = checkNotNull(workspaceId) { "Workspace is not selected" }
}

class SubagentTimelineViewModel : ViewModel() {
    val items = SubagentTimelineHub.items
}
