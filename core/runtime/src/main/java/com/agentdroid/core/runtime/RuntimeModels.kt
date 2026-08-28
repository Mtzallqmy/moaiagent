package com.agentdroid.core.runtime

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class RuntimeLimits(
    val maxStdoutBytes: Int = 1_048_576,
    val maxStderrBytes: Int = 524_288,
    val defaultTimeoutMs: Long = 30_000,
    val maxRuntimeMs: Long = 30 * 60_000,
    val maxBackgroundProcesses: Int = 6,
    val maxConcurrentProcesses: Int = 8
) {
    init {
        require(maxStdoutBytes > 0 && maxStderrBytes > 0)
        require(defaultTimeoutMs in 1..maxRuntimeMs)
        require(maxBackgroundProcesses > 0 && maxConcurrentProcesses >= maxBackgroundProcesses)
    }
}

enum class ProcessStatus { STARTING, RUNNING, EXITED, FAILED, TIMED_OUT, TERMINATED, KILLED, STALE }

data class ProcessRequest(
    val command: String? = null,
    val argv: List<String> = emptyList(),
    val cwd: File,
    val environment: Map<String, String> = emptyMap(),
    val timeoutMs: Long? = null,
    val maxStdoutBytes: Int? = null,
    val maxStderrBytes: Int? = null
) {
    init { require((command != null) xor argv.isNotEmpty()) { "Provide command or argv, not both" } }
}

data class ProcessResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val durationMs: Long,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val status: ProcessStatus,
    val error: String? = null
) {
    val truncated: Boolean get() = stdoutTruncated || stderrTruncated
}

interface RunningProcess {
    val status: StateFlow<ProcessStatus>
    val stdout: StateFlow<String>
    val stderr: StateFlow<String>
    val exitCode: StateFlow<Int?>
    val startedAt: Long
    suspend fun await(): ProcessResult
    suspend fun sendInput(text: String): Result<Unit>
    fun terminate()
    fun kill()
}

interface ProcessRunner {
    suspend fun run(request: ProcessRequest): ProcessResult
    suspend fun start(request: ProcessRequest): RunningProcess
}

@Serializable
data class ProcessMetadata(
    val processId: String,
    val sessionId: String?,
    val workspaceId: String,
    val command: String,
    val cwd: String,
    val status: ProcessStatus,
    val exitCode: Int? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val background: Boolean = true
)

interface ProcessMetadataStore {
    suspend fun save(metadata: ProcessMetadata)
    suspend fun get(processId: String): ProcessMetadata?
    suspend fun list(workspaceId: String? = null): List<ProcessMetadata>
    suspend fun markPreviouslyRunningStale(now: Long = System.currentTimeMillis())

    companion object {
        val NOOP = object : ProcessMetadataStore {
            override suspend fun save(metadata: ProcessMetadata) = Unit
            override suspend fun get(processId: String) = null
            override suspend fun list(workspaceId: String?) = emptyList<ProcessMetadata>()
            override suspend fun markPreviouslyRunningStale(now: Long) = Unit
        }
    }
}

@Serializable
data class ProcessSnapshot(
    val processId: String,
    val sessionId: String?,
    val workspaceId: String,
    val command: String,
    val cwd: String,
    val status: ProcessStatus,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val background: Boolean
)

@Serializable
data class RuntimeComponent(
    val id: String,
    val label: String,
    val available: Boolean,
    val version: String? = null,
    val executable: String? = null
)

@Serializable
data class RuntimeSnapshot(
    val shell: RuntimeComponent,
    val git: RuntimeComponent,
    val python: RuntimeComponent,
    val node: RuntimeComponent,
    val rust: RuntimeComponent,
    val go: RuntimeComponent
)

/** Detection/inventory abstraction from Phase 3. Detection alone is never runtime capability evidence. */
interface RuntimePack {
    val id: String
    val displayName: String
    suspend fun detect(): RuntimeComponent
}

interface RuntimeInventory {
    suspend fun list(): List<RuntimeComponent>
    suspend fun get(id: String): RuntimeComponent?
}
