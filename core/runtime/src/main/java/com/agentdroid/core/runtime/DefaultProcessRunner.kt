package com.agentdroid.core.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class DefaultProcessRunner(
    private val limits: RuntimeLimits = RuntimeLimits(),
    private val shellResolver: () -> String = ::defaultShellPath
) : ProcessRunner {

    override suspend fun run(request: ProcessRequest): ProcessResult {
        val running = start(request)
        return try {
            running.await()
        } catch (cancelled: CancellationException) {
            running.kill()
            throw cancelled
        }
    }

    override suspend fun start(request: ProcessRequest): RunningProcess = withContext(Dispatchers.IO) {
        require(request.cwd.exists() && request.cwd.isDirectory) { "Working directory does not exist: ${request.cwd}" }
        val command = if (request.command != null) listOf(shellResolver(), "-c", request.command) else request.argv
        val builder = ProcessBuilder(command)
            .directory(request.cwd)
            .redirectErrorStream(false)
        builder.environment().putAll(request.environment)
        val process = builder.start()
        LocalRunningProcess(
            process = process,
            timeoutMs = (request.timeoutMs ?: limits.defaultTimeoutMs).coerceIn(1, limits.maxRuntimeMs),
            stdoutLimit = request.maxStdoutBytes ?: limits.maxStdoutBytes,
            stderrLimit = request.maxStderrBytes ?: limits.maxStderrBytes
        )
    }
}

private class LocalRunningProcess(
    private val process: Process,
    private val timeoutMs: Long,
    stdoutLimit: Int,
    stderrLimit: Int
) : RunningProcess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stdoutBuffer = BoundedTextBuffer(stdoutLimit)
    private val stderrBuffer = BoundedTextBuffer(stderrLimit)
    private val _status = MutableStateFlow(ProcessStatus.STARTING)
    private val _stdout = MutableStateFlow("")
    private val _stderr = MutableStateFlow("")
    private val _exitCode = MutableStateFlow<Int?>(null)
    private val completion = CompletableDeferred<ProcessResult>()
    private val timedOut = AtomicBoolean(false)
    private val terminationRequested = AtomicBoolean(false)
    private val killedRequested = AtomicBoolean(false)
    override val startedAt: Long = System.currentTimeMillis()
    override val status: StateFlow<ProcessStatus> = _status.asStateFlow()
    override val stdout: StateFlow<String> = _stdout.asStateFlow()
    override val stderr: StateFlow<String> = _stderr.asStateFlow()
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val stdoutJob: Job
    private val stderrJob: Job

    init {
        _status.value = ProcessStatus.RUNNING
        stdoutJob = scope.launch { consume(process.inputStream, stdoutBuffer, _stdout) }
        stderrJob = scope.launch { consume(process.errorStream, stderrBuffer, _stderr) }
        scope.launch { monitor() }
    }

    override suspend fun await(): ProcessResult = completion.await()

    override suspend fun sendInput(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!process.isAlive) return@withContext Result.failure(IllegalStateException("Process is not running"))
        runCatching {
            process.outputStream.write(text.toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
        }
    }

    override fun terminate() {
        if (process.isAlive) {
            terminationRequested.set(true)
            process.destroy()
        }
    }

    override fun kill() {
        if (process.isAlive) {
            killedRequested.set(true)
            process.destroyForcibly()
        }
    }

    private suspend fun monitor() {
        try {
            while (process.isAlive) {
                if (System.currentTimeMillis() - startedAt >= timeoutMs) {
                    timedOut.set(true)
                    process.destroyForcibly()
                    break
                }
                delay(25)
            }
            runCatching { withContext(Dispatchers.IO) { process.waitFor() } }
            val code = runCatching { process.exitValue() }.getOrNull()
            _exitCode.value = code
            val finalStatus = when {
                timedOut.get() -> ProcessStatus.TIMED_OUT
                killedRequested.get() -> ProcessStatus.KILLED
                terminationRequested.get() -> ProcessStatus.TERMINATED
                else -> ProcessStatus.EXITED
            }
            _status.value = finalStatus
            // Publish process termination before draining inherited pipes. A child spawned by a shell
            // may temporarily keep stdout/stderr descriptors open even after the managed process exits.
            stdoutJob.join(); stderrJob.join()
            val result = ProcessResult(
                stdout = LogRedactor.redact(stdoutBuffer.text()),
                stderr = LogRedactor.redact(stderrBuffer.text()),
                exitCode = code,
                durationMs = System.currentTimeMillis() - startedAt,
                timedOut = timedOut.get(),
                stdoutTruncated = stdoutBuffer.truncated,
                stderrTruncated = stderrBuffer.truncated,
                status = finalStatus
            )
            completion.complete(result)
        } catch (failure: Throwable) {
            _status.value = ProcessStatus.FAILED
            completion.complete(
                ProcessResult(
                    stdout = LogRedactor.redact(stdoutBuffer.text()),
                    stderr = LogRedactor.redact(stderrBuffer.text()),
                    exitCode = null,
                    durationMs = System.currentTimeMillis() - startedAt,
                    timedOut = timedOut.get(),
                    stdoutTruncated = stdoutBuffer.truncated,
                    stderrTruncated = stderrBuffer.truncated,
                    status = ProcessStatus.FAILED,
                    error = failure.message ?: failure::class.java.simpleName
                )
            )
        }
    }

    private fun consume(stream: java.io.InputStream, buffer: BoundedTextBuffer, state: MutableStateFlow<String>) {
        InputStreamReader(stream, Charsets.UTF_8).use { reader ->
            val chars = CharArray(4096)
            while (true) {
                val read = reader.read(chars)
                if (read < 0) break
                if (read == 0) continue
                buffer.append(String(chars, 0, read))
                state.value = LogRedactor.redact(buffer.text())
            }
        }
    }
}

private class BoundedTextBuffer(private val maxBytes: Int) {
    private val builder = StringBuilder()
    private var byteCount = 0
    @Volatile var truncated: Boolean = false
        private set

    @Synchronized fun append(value: String) {
        if (truncated || value.isEmpty()) return
        val bytes = value.toByteArray(Charsets.UTF_8)
        val remaining = maxBytes - byteCount
        if (remaining <= 0) { truncated = true; return }
        if (bytes.size <= remaining) {
            builder.append(value); byteCount += bytes.size; return
        }
        var low = 0
        var high = value.length
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (value.substring(0, mid).toByteArray(Charsets.UTF_8).size <= remaining) low = mid else high = mid - 1
        }
        if (low > 0) {
            val piece = value.substring(0, low)
            builder.append(piece); byteCount += piece.toByteArray(Charsets.UTF_8).size
        }
        truncated = true
    }

    @Synchronized fun text(): String = builder.toString()
}

fun defaultShellPath(): String = sequenceOf("/system/bin/sh", "/bin/sh", "/usr/bin/sh")
    .map(::File)
    .firstOrNull { it.exists() && it.canExecute() }
    ?.absolutePath ?: "sh"
