package com.agentdroid.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.agentdroid.R
import com.agentdroid.viewmodel.ContainerViewModelFactory
import com.agentdroid.viewmodel.WorkspaceFilesViewModel

@Composable
fun Phase3WorkspaceBrowserHost(nav: NavHostController, factory: ContainerViewModelFactory, workspaceId: String) {
    val vm: WorkspaceFilesViewModel = viewModel(factory = factory)
    val path by vm.currentPath.collectAsState()
    Box(Modifier.fillMaxSize()) {
        WorkspaceBrowserScreen(nav, factory, workspaceId)
        Column(Modifier.align(Alignment.BottomEnd).padding(18.dp), horizontalAlignment = Alignment.End) {
            SmallFloatingActionButton(
                onClick = { nav.navigate("browser/$workspaceId/workspace-$workspaceId") },
                modifier = Modifier.padding(bottom = 10.dp).testTag("workspace_open_browser")
            ) { Icon(Icons.Default.Language, stringResource(R.string.phase4_open_browser), Modifier.size(20.dp)) }
            SmallFloatingActionButton(
                onClick = { nav.navigate("tasks/$workspaceId") },
                modifier = Modifier.padding(bottom = 10.dp).testTag("workspace_open_tasks")
            ) { Icon(Icons.Default.Checklist, stringResource(R.string.phase4_open_tasks), Modifier.size(20.dp)) }
            SmallFloatingActionButton(
                onClick = { nav.navigate("artifacts/$workspaceId") },
                modifier = Modifier.padding(bottom = 10.dp).testTag("workspace_open_artifacts")
            ) { Icon(Icons.Default.Description, stringResource(R.string.phase4_open_artifacts), Modifier.size(20.dp)) }
            SmallFloatingActionButton(
                onClick = { nav.navigate("git/$workspaceId") },
                modifier = Modifier.padding(bottom = 10.dp).testTag("workspace_open_git")
            ) { Icon(Icons.Default.AccountTree, stringResource(R.string.open_git), Modifier.size(20.dp)) }
            SmallFloatingActionButton(
                onClick = { nav.navigate("terminal/$workspaceId?cwd=${Uri.encode(path.ifBlank { "." })}") },
                modifier = Modifier.testTag("workspace_open_terminal")
            ) { Icon(Icons.Default.Terminal, stringResource(R.string.open_terminal), Modifier.size(20.dp)) }
        }
    }
}
