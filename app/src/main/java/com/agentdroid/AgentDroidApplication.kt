package com.agentdroid

import android.app.Application
import com.agentdroid.integration.AppMcpController
import com.agentdroid.integration.AppRuntimePackController
import com.agentdroid.integration.EmbeddedNodeRuntime
import com.agentdroid.integration.EmbeddedPythonRuntime
import com.agentdroid.integration.createNodeRuntimeTools
import com.agentdroid.integration.createPythonRuntimeTools

class AgentDroidApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    val pythonRuntime: EmbeddedPythonRuntime by lazy { EmbeddedPythonRuntime(this, container::workspaceRoot) }
    val nodeRuntime: EmbeddedNodeRuntime by lazy { EmbeddedNodeRuntime(this, container::workspaceRoot) }
    val mcpController: AppMcpController by lazy { AppMcpController(this, container) }
    val runtimePacks: AppRuntimePackController by lazy { AppRuntimePackController(this, container, pythonRuntime, nodeRuntime) }

    override fun onCreate() {
        super.onCreate()
        container.toolRegistry.registerAll(createPythonRuntimeTools(pythonRuntime))
        container.toolRegistry.registerAll(createNodeRuntimeTools(nodeRuntime))
    }
}
