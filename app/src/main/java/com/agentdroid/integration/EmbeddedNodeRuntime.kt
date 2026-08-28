package com.agentdroid.integration

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

 data class NodeExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMs: Long
)

class EmbeddedNodeRuntime(
    context: Context,
    private val workspaceRoot: (String) -> File
) {
    private val appContext = context.applicationContext

    fun executable(): File = File(appContext.applicationInfo.nativeLibraryDir, "libnode.so")
    fun isAvailable(): Boolean = executable().isFile

    suspend fun version(timeoutMs: Long = 5_000): String {
        val result = execute(null, listOf("--version"), timeoutMs)
        check(result.exitCode == 0 && !result.timedOut) { result.stderr.ifBlank { "Embedded Node.js version probe failed" } }
        return result.stdout.trim().removePrefix("v")
    }

    suspend fun runCode(workspaceId: String, code: String, timeoutMs: Long = 15_000): NodeExecutionResult =
        execute(workspaceId, listOf("-e", code), timeoutMs)

    suspend fun runScript(workspaceId: String, relativePath: String, args: List<String> = emptyList(), timeoutMs: Long = 30_000): NodeExecutionResult {
        val root = canonicalWorkspace(workspaceId)
        val script = File(root, relativePath).canonicalFile
        require(script.path == root.path || script.path.startsWith(root.path + File.separator)) { "Node script escapes workspace" }
        require(script.isFile) { "Node script does not exist: $relativePath" }
        return execute(workspaceId, listOf(script.absolutePath) + args, timeoutMs)
    }

    private suspend fun execute(workspaceId: String?, args: List<String>, timeoutMs: Long): NodeExecutionResult = coroutineScope {
        require(timeoutMs in 100..120_000) { "Node timeout must be bounded" }
        val node = executable()
        require(node.isFile) { "Embedded Node.js runtime is not packaged for this ABI" }
        val cwd = workspaceId?.let(::canonicalWorkspace) ?: appContext.filesDir
        val started = System.nanoTime()
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(listOf(node.absolutePath) + args)
                .directory(cwd)
                .redirectErrorStream(false)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = appContext.applicationInfo.nativeLibraryDir
                    environment()["HOME"] = cwd.absolutePath
                    environment()["TMPDIR"] = appContext.cacheDir.absolutePath
                    environment()["AGENTDROID_WORKSPACE"] = cwd.absolutePath
                }
                .start()
        }
        try {
            val stdout = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
            val stderr = async(Dispatchers.IO) { process.errorStream.bufferedReader().use { it.readText() } }
            val finished = withContext(Dispatchers.IO) { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            if (!finished) process.destroyForcibly()
            val out = stdout.await().take(1_000_000)
            val err = stderr.await().take(1_000_000)
            NodeExecutionResult(if (finished) process.exitValue() else -1, out, err, !finished, (System.nanoTime() - started) / 1_000_000)
        } catch (cancelled: CancellationException) {
            process.destroyForcibly()
            throw cancelled
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun canonicalWorkspace(workspaceId: String): File {
        require(workspaceId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid workspace id" }
        return workspaceRoot(workspaceId).apply { mkdirs() }.canonicalFile
    }
}
