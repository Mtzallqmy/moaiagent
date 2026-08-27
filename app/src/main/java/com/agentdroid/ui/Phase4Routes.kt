package com.agentdroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.agentdroid.viewmodel.ArtifactsViewModel
import com.agentdroid.viewmodel.BrowserViewModel
import com.agentdroid.viewmodel.ContainerViewModelFactory
import com.agentdroid.viewmodel.SubagentTimelineViewModel
import com.agentdroid.viewmodel.TasksViewModel
import com.agentdroid.R

@Composable
fun Phase4BrowserRoute(nav: NavHostController, factory: ContainerViewModelFactory, workspaceId: String, conversationId: String) {
    val vm: BrowserViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(workspaceId, conversationId) { vm.open(workspaceId, conversationId) }
    val value = state
    if (value == null) Column(Modifier.fillMaxSize()) { Text(error ?: stringResource(R.string.phase4_loading)) }
    else Phase4BrowserScreen(
        state = value,
        onNavigate = vm::navigate,
        onBack = vm::back,
        onForward = vm::forward,
        onRefresh = vm::refresh,
        onStop = vm::stop,
        onNewTab = vm::newTab,
        onSelectTab = vm::selectTab,
        onCloseTab = vm::closeTab,
        onOpenExternal = { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        },
        onAgentLinkChanged = vm::setLinked
    )
}

@Composable
fun Phase4TasksRoute(nav: NavHostController, factory: ContainerViewModelFactory, workspaceId: String) {
    val vm: TasksViewModel = viewModel(factory = factory)
    val tasks by vm.tasks.collectAsState()
    val selected by vm.selectedTaskId.collectAsState()
    LaunchedEffect(workspaceId) { vm.open(workspaceId) }
    Phase4TasksScreen(
        tasks = tasks,
        selectedTaskId = selected,
        onSelectTask = { vm.select(it) },
        onPause = vm::pause,
        onCancel = vm::cancel,
        onRetry = vm::retry,
        onOpenConversation = { nav.navigate("agent/$it") },
        onOpenArtifact = { nav.navigate("artifacts/$workspaceId?artifactId=${Uri.encode(it)}") },
        onBackToList = { vm.select(null) }
    )
}

@Composable
fun Phase4ArtifactsRoute(
    factory: ContainerViewModelFactory,
    workspaceId: String,
    initialArtifactId: String? = null
) {
    val vm: ArtifactsViewModel = viewModel(factory = factory)
    val artifacts by vm.artifacts.collectAsState()
    val selected by vm.selectedArtifactId.collectAsState()
    val content by vm.content.collectAsState()
    val truncated by vm.truncated.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(workspaceId, initialArtifactId) {
        vm.open(workspaceId)
        initialArtifactId?.let(vm::select)
    }
    Phase4ArtifactViewer(
        artifacts = artifacts,
        selectedArtifactId = selected,
        content = content,
        contentTruncated = truncated,
        onSelect = vm::select,
        onOpen = { vm.select(it.id) },
        onRename = vm::rename,
        onShare = { artifact -> shareArtifact(context, artifact.title, content) },
        onCopy = { value ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("artifact", value))
        },
        onDelete = vm::delete,
        onExport = vm::export,
        onBackToList = { vm.select(null) }
    )
}

@Composable
fun Phase4SubagentRoute(factory: ContainerViewModelFactory) {
    val vm: SubagentTimelineViewModel = viewModel(factory = factory)
    val items by vm.items.collectAsState()
    SubagentTimeline(mainAgentLabel = stringResource(R.string.phase4_main_agent), items = items, modifier = Modifier.fillMaxSize())
}

private fun shareArtifact(context: Context, title: String, content: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, content ?: title)
    }
    context.startActivity(Intent.createChooser(send, title))
}
