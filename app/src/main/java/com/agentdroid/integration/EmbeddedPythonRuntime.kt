package com.agentdroid.integration

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PythonExecutionResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val error: String? = null,
    val timedOut: Boolean = false
)

class EmbeddedPythonRuntime(
    context: Context,
    private val workspaceRoot: (String) -> File,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val appContext = context.applicationContext
    private val lock = Mutex()

    suspend fun version(): String = withContext(Dispatchers.IO) {
        lock.withLock { module().callAttr("version").toString() }
    }

    suspend fun runCode(workspaceId: String, code: String, timeoutMs: Long = 30_000): PythonExecutionResult = withContext(Dispatchers.IO) {
        require(code.isNotBlank()) { "Python code must not be blank" }
        require(code.toByteArray().size <= 512 * 1024) { "Python code exceeds 512 KiB" }
        val root = workspaceRoot(workspaceId).canonicalFile
        require(root.exists() && root.isDirectory) { "Workspace does not exist" }
        val boundedTimeout = timeoutMs.coerceIn(50, 60_000)
        lock.withLock {
            json.decodeFromString(module().callAttr("run_code", code, root.canonicalPath, boundedTimeout / 1000.0).toString())
        }
    }

    suspend fun runFile(workspaceId: String, relativePath: String, timeoutMs: Long = 30_000): PythonExecutionResult = withContext(Dispatchers.IO) {
        val normalized = relativePath.replace('\\', '/')
        require(normalized.isNotBlank() && !File(normalized).isAbsolute && ".." !in normalized.split('/')) { "Unsafe Python script path" }
        val root = workspaceRoot(workspaceId).canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target == root || target.path.startsWith(root.path + File.separator)) { "Python script escapes workspace" }
        require(target.isFile && target.length() <= 512 * 1024) { "Python script is missing or too large" }
        lock.withLock {
            json.decodeFromString(module().callAttr("run_file", normalized, root.canonicalPath, timeoutMs.coerceIn(50, 60_000) / 1000.0).toString())
        }
    }

    suspend fun installPackage(workspaceId: String, packageSpec: String): PythonExecutionResult = withContext(Dispatchers.IO) {
        require(packageSpec.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*(?:\\[[A-Za-z0-9_,.-]+])?(?:==[A-Za-z0-9._+-]+)?"))) { "Unsafe Python package spec" }
        val root = workspaceRoot(workspaceId).canonicalFile
        lock.withLock {
            json.decodeFromString(module().callAttr("install_package", packageSpec, root.canonicalPath).toString())
        }
    }

    private fun module(): com.chaquo.python.PyObject {
        if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
        return Python.getInstance().getModule("agentdroid_runtime")
    }
}
