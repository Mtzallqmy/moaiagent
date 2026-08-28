package com.agentdroid.integration

import android.content.Context
import com.agentdroid.AppContainer
import com.agentdroid.core.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.File
import java.io.InputStream

class AppRuntimePackController(
    context: Context,
    private val container: AppContainer,
    private val python: EmbeddedPythonRuntime,
    private val node: EmbeddedNodeRuntime
) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder().followRedirects(true).build()
    private val installer = VerifiedZipRuntimePackInstaller(
        installRoot = File(appContext.filesDir, "runtime-packs"),
        openSource = ::openTrustedSource
    )
    val manager = RuntimePackManager(
        manifests = listOf(RuntimePackManifests.baseShell, RuntimePackManifests.git, RuntimePackManifests.python, RuntimePackManifests.node),
        installer = installer,
        executionVerifier = RuntimePackExecutionVerifier(::verifyRuntime)
    )

    suspend fun list(): List<RuntimePackCapabilityState> = manager.list()
    suspend fun install(id: String) = manager.install(id)
    suspend fun uninstall(id: String) = manager.uninstall(id)
    suspend fun setEnabled(id: String, enabled: Boolean) = manager.setEnabled(id, enabled)

    private suspend fun verifyRuntime(state: RuntimePackState): Boolean = when (state.manifest.id) {
        "base-shell" -> {
            val probe = File(appContext.cacheDir, "runtime-probe").apply { mkdirs() }
            val result = runCatching {
                container.processRunner.run(ProcessRequest(argv = listOf("/system/bin/sh", "-c", "printf AGENTDROID_RUNTIME_OK"), cwd = probe, timeoutMs = 4_000))
            }.getOrNull()
            result?.exitCode == 0 && !result.timedOut && result.stdout == "AGENTDROID_RUNTIME_OK"
        }
        "git" -> true
        "python" -> {
            val workspaceId = "runtime_probe"
            container.workspaceRoot(workspaceId).mkdirs()
            val result = runCatching { python.runCode(workspaceId, "print('AGENTDROID_RUNTIME_OK')", 4_000) }.getOrNull()
            result?.exitCode == 0 && "AGENTDROID_RUNTIME_OK" in result.stdout
        }
        "node" -> {
            val workspaceId = "node_runtime_probe"
            container.workspaceRoot(workspaceId).mkdirs()
            val result = runCatching { node.runCode(workspaceId, "console.log('AGENTDROID_NODE_OK')", 6_000) }.getOrNull()
            result?.exitCode == 0 && !result.timedOut && "AGENTDROID_NODE_OK" in result.stdout
        }
        else -> false
    }

    private suspend fun openTrustedSource(url: String): InputStream = withContext(Dispatchers.IO) {
        val response = http.newCall(Request.Builder().url(url).get().build()).execute()
        if (!response.isSuccessful) {
            response.close(); error("Runtime download failed with HTTP ${response.code}")
        }
        val body = response.body ?: run { response.close(); error("Runtime download body is empty") }
        object : FilterInputStream(body.byteStream()) {
            override fun close() { try { super.close() } finally { response.close() } }
        }
    }
}
