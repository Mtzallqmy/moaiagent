package com.agentdroid

import android.app.Application
import com.agentdroid.integration.EmbeddedPythonRuntime
import com.agentdroid.integration.createPythonRuntimeTools

class AgentDroidApplication : Application() {
    val container: AppContainer by lazy {
        AppContainer(this).also { built ->
            val python = EmbeddedPythonRuntime(this, built::workspaceRoot)
            built.toolRegistry.registerAll(createPythonRuntimeTools(python))
        }
    }
}
