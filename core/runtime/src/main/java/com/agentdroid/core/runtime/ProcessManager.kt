package com.agentdroid.core.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ProcessManager(
    private val runner: ProcessRunner,
    private val store: ProcessMetadataStore = ProcessMetadataStore.NOOP,
    private val limits: RuntimeLimits = RuntimeLimits(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private data class Entry(
        val processId: String,
        val sessionId: String?,
        val workspaceId: String,
        val command: String,
        val cwd: String,
        val background: Boolean,
        val running: RunningProcess
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val _snapshots = MutableStateFlow<List<ProcessSnapshot>>(emptyList())
    val snapshots: StateFlow<List<ProcessSnapshot>> = _snapshots.asStateFlow()

    init {
        scope.launch { store.markPreviouslyRunningStale() }
    }

    suspend fun runForeground(request: ProcessRequest, workspaceId: String, sessionId: String?): ProcessSnapshot {
        checkCapacity(background = false)
        val running = runner.start(request.copy(timeoutMs = (request.timeoutMs ?: limits.defaultTimeoutMs).coerceAtMost(limits.maxRuntimeMs)))
        val processId = UUID.randomUUID().toString()
        val entry = Entry(processId, sessionId, workspaceId, displayCommand(request), request.cwd.canonicalPath, false, running)
        entries[processId] = entry
        attach(entry)
        persist(entry, ProcessStatus.RUNNING, null, null)
        return try {
            running.await()
            currentSnapshot(entry)
        } catch (cancelled: CancellationException) {
            running.kill()
            throw cancelled
        }
    }

    suspend fun startBackground(request: ProcessRequest, workspaceId: String, sessionId: String?): ProcessSnapshot {
        checkCapacity(background = true)
        val effective = request.copy(timeoutMs = (request.timeoutMs ?: limits.maxRuntimeMs).coerceAtMost(limits.maxRuntimeMs))
        val running = runner.start(effective)
        val processId = UUID.randomUUID().toString()
        val entry = Entry(processId, sessionId, workspaceId, displayCommand(request), request.cwd.canonicalPath, true, running)
        entries[processId] = entry
        attach(entry)
        persist(entry, ProcessStatus.RUNNING, null, null)
        scope.launch {
            running.await()
            val snapshot = currentSnapshot(entry)
            persist(entry, snapshot.status, snapshot.exitCode, snapshot.finishedAt)
        }
        return currentSnapshot(entry)
    }

    suspend fun get(processId: String, workspaceId: String? = null): ProcessSnapshot? {
        val entry = entries[processId]
        if (entry != null) {
            if (workspaceId != null && entry.workspaceId != workspaceId) return null
            return currentSnapshot(entry)
        }
        val persisted = store.get(processId) ?: return null
        if (workspaceId != null && persisted.workspaceId != workspaceId) return null
        return persisted.toSnapshot()
    }

    suspend fun list(workspaceId: String? = null): List<ProcessSnapshot> {
        val inMemory = entries.values.asSequence()
            .filter { workspaceId == null || it.workspaceId == workspaceId }
            .map(::currentSnapshot)
            .associateBy { it.processId }
            .toMutableMap()
        store.list(workspaceId).forEach { metadata -> inMemory.putIfAbsent(metadata.processId, metadata.toSnapshot()) }
        return inMemory.values.sortedByDescending { it.startedAt }
    }

    suspend fun sendInput(processId: String, workspaceId: String, input: String): Result<Unit> {
        val entry = entries[processId] ?: return Result.failure(IllegalArgumentException("Process not found"))
        if (entry.workspaceId != workspaceId) return Result.failure(SecurityException("Process belongs to another workspace"))
        if (entry.running.status.value != ProcessStatus.RUNNING) return Result.failure(IllegalStateException("Process is not running"))
        return entry.running.sendInput(input)
    }

    fun stop(processId: String, workspaceId: String, force: Boolean = false): Boolean {
        val entry = entries[processId] ?: return false
        if (entry.workspaceId != workspaceId) return false
        if (force) entry.running.kill() else entry.running.terminate()
        return true
    }

    fun stopForegroundForSession(sessionId: String) {
        entries.values.filter { !it.background && it.sessionId == sessionId && it.running.status.value == ProcessStatus.RUNNING }.forEach { it.running.kill() }
    }

    private suspend fun checkCapacity(background: Boolean) {
        val active = entries.values.count { it.running.status.value in activeStatuses }
        if (active >= limits.maxConcurrentProcesses) throw ProcessLimitException("Maximum concurrent process count reached")
        if (background && entries.values.count { it.background && it.running.status.value in activeStatuses } >= limits.maxBackgroundProcesses) {
            throw ProcessLimitException("Maximum background process count reached")
        }
    }

    private fun attach(entry: Entry) {
        scope.launch {
            combine(entry.running.status, entry.running.stdout, entry.running.stderr, entry.running.exitCode) { _, _, _, _ -> currentSnapshot(entry) }
                .collect { snapshot ->
                    updateSnapshot(snapshot)
                    if (snapshot.status !in activeStatuses) persist(entry, snapshot.status, snapshot.exitCode, snapshot.finishedAt)
                }
        }
    }

    private fun currentSnapshot(entry: Entry): ProcessSnapshot {
        val status = entry.running.status.value
        val now = System.currentTimeMillis()
        return ProcessSnapshot(
            processId = entry.processId,
            sessionId = entry.sessionId,
            workspaceId = entry.workspaceId,
            command = entry.command,
            cwd = entry.cwd,
            status = status,
            stdout = entry.running.stdout.value,
            stderr = entry.running.stderr.value,
            exitCode = entry.running.exitCode.value,
            startedAt = entry.running.startedAt,
            finishedAt = if (status in activeStatuses) null else now,
            durationMs = now - entry.running.startedAt,
            timedOut = status == ProcessStatus.TIMED_OUT,
            truncated = false,
            background = entry.background
        )
    }

    private fun updateSnapshot(snapshot: ProcessSnapshot) {
        _snapshots.value = (_snapshots.value.filterNot { it.processId == snapshot.processId } + snapshot).sortedByDescending { it.startedAt }
    }

    private suspend fun persist(entry: Entry, status: ProcessStatus, exitCode: Int?, finishedAt: Long?) {
        store.save(
            ProcessMetadata(
                processId = entry.processId,
                sessionId = entry.sessionId,
                workspaceId = entry.workspaceId,
                command = entry.command,
                cwd = entry.cwd,
                status = status,
                exitCode = exitCode,
                startedAt = entry.running.startedAt,
                finishedAt = finishedAt,
                background = entry.background
            )
        )
    }

    private fun displayCommand(request: ProcessRequest): String = CommandRedactor.redact(request.command ?: request.argv.joinToString(" "))

    companion object {
        private val activeStatuses = setOf(ProcessStatus.STARTING, ProcessStatus.RUNNING)
    }
}

class ProcessLimitException(message: String) : IllegalStateException(message)

private fun ProcessMetadata.toSnapshot(): ProcessSnapshot = ProcessSnapshot(
    processId = processId,
    sessionId = sessionId,
    workspaceId = workspaceId,
    command = command,
    cwd = cwd,
    status = status,
    exitCode = exitCode,
    startedAt = startedAt,
    finishedAt = finishedAt,
    durationMs = (finishedAt ?: System.currentTimeMillis()) - startedAt,
    background = background
)
