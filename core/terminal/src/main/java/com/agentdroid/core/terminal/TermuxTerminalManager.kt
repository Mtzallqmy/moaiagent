package com.agentdroid.core.terminal

import com.agentdroid.core.runtime.defaultShellPath
import com.termux.terminal.TerminalSession as NativeTerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TermuxTerminalManager(
    private val workspaceRootProvider: (String) -> File,
    private val metadataStore: TerminalSessionMetadataStore = TerminalSessionMetadataStore.NOOP,
    private val clipboard: TerminalClipboard = TerminalClipboard.NONE,
    private val shellPath: String = defaultShellPath(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val transcriptRows: Int = 4_000
) : TerminalManager {
    private val entries = ConcurrentHashMap<String, TermuxTerminalSession>()
    private val _sessions = MutableStateFlow<List<TerminalSessionState>>(emptyList())
    override val sessions: StateFlow<List<TerminalSessionState>> = _sessions.asStateFlow()

    init { scope.launch { metadataStore.markPreviouslyRunningStale() } }

    override fun create(workspaceId: String, cwd: String, title: String?, columns: Int, rows: Int): TerminalSession {
        val root = workspaceRootProvider(workspaceId).canonicalFile
        val resolvedCwd = resolveCwd(root, cwd)
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val session = TermuxTerminalSession(
            id = id,
            workspaceId = workspaceId,
            initialTitle = title?.takeIf { it.isNotBlank() } ?: "Terminal ${entries.size + 1}",
            initialCwd = resolvedCwd,
            shellPath = shellPath,
            environment = terminalEnvironment(root, resolvedCwd),
            transcriptRows = transcriptRows,
            clipboard = clipboard,
            onChanged = ::onSessionChanged
        )
        entries[id] = session
        // TerminalSession creates its PTY subprocess when the first size is known.
        session.resize(columns.coerceAtLeast(2), rows.coerceAtLeast(2), 0, 0)
        onSessionChanged(session.state.value)
        return session
    }

    override fun get(sessionId: String): TerminalSession? = entries[sessionId]

    fun nativeSession(sessionId: String): NativeTerminalSession? = entries[sessionId]?.native

    override fun rename(sessionId: String, title: String): Boolean {
        val session = entries[sessionId] ?: return false
        if (title.isBlank()) return false
        session.rename(title.trim())
        return true
    }

    override fun close(sessionId: String, force: Boolean): Boolean {
        val session = entries[sessionId] ?: return false
        if (force) session.kill() else session.close()
        return true
    }

    fun remove(sessionId: String): Boolean {
        val removed = entries.remove(sessionId) ?: return false
        removed.kill()
        publish()
        return true
    }

    override fun clear(sessionId: String): Boolean {
        val session = entries[sessionId] ?: return false
        session.clear()
        return true
    }

    private fun onSessionChanged(state: TerminalSessionState) {
        publish()
        scope.launch {
            metadataStore.save(
                TerminalSessionMetadata(
                    state.sessionId,
                    state.workspaceId,
                    state.title,
                    state.cwd,
                    state.createdAt,
                    state.lastUsedAt,
                    state.running,
                    state.exitCode
                )
            )
        }
    }

    private fun publish() {
        _sessions.value = entries.values.map { it.state.value }.sortedBy { it.createdAt }
    }

    private fun resolveCwd(root: File, cwd: String): File {
        require(!File(cwd).isAbsolute) { "Terminal cwd must be workspace-relative" }
        require(cwd.replace('\\', '/').split('/').none { it == ".." }) { "Terminal cwd traversal is not allowed" }
        val target = if (cwd.isBlank() || cwd == ".") root else File(root, cwd).canonicalFile
        require(target == root || target.path.startsWith(root.path + File.separator)) { "Terminal cwd escapes workspace" }
        require(target.exists() && target.isDirectory) { "Terminal cwd does not exist" }
        return target
    }

    private fun terminalEnvironment(root: File, cwd: File): Array<String> {
        val path = listOf(System.getenv("PATH").orEmpty(), "/system/bin", "/system/xbin", "/vendor/bin", "/product/bin")
            .filter { it.isNotBlank() }.joinToString(":")
        val tmp = File(root, ".agentdroid/tmp").apply { mkdirs() }
        return arrayOf(
            "HOME=${root.canonicalPath}",
            "PWD=${cwd.canonicalPath}",
            "TMPDIR=${tmp.canonicalPath}",
            "PATH=$path",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8"
        )
    }
}

class TermuxTerminalSession internal constructor(
    private val id: String,
    private val workspaceId: String,
    initialTitle: String,
    initialCwd: File,
    shellPath: String,
    environment: Array<String>,
    transcriptRows: Int,
    clipboard: TerminalClipboard,
    private val onChanged: (TerminalSessionState) -> Unit
) : TerminalSession {
    private val createdAt = System.currentTimeMillis()
    private val _state = MutableStateFlow(
        TerminalSessionState(id, workspaceId, initialTitle, initialCwd.canonicalPath, createdAt, createdAt, running = true)
    )
    override val state: StateFlow<TerminalSessionState> = _state.asStateFlow()

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: NativeTerminalSession) = refresh(changedSession)
        override fun onTitleChanged(changedSession: NativeTerminalSession) {
            changedSession.title?.takeIf { it.isNotBlank() }?.let { nativeTitle ->
                _state.value = _state.value.copy(title = nativeTitle, lastUsedAt = System.currentTimeMillis())
            }
            refresh(changedSession)
        }
        override fun onSessionFinished(finishedSession: NativeTerminalSession) = refresh(finishedSession, finished = true)
        override fun onCopyTextToClipboard(session: NativeTerminalSession, text: String) = clipboard.copy(text)
        override fun onPasteTextFromClipboard(session: NativeTerminalSession) { clipboard.paste()?.let { text -> session.emulator?.paste(text) } }
        override fun onBell(session: NativeTerminalSession) = Unit
        override fun onColorsChanged(session: NativeTerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) = Unit
        override fun logWarn(tag: String, message: String) = Unit
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
        override fun logStackTrace(tag: String, e: Exception) = Unit
    }

    internal val native = NativeTerminalSession(
        shellPath,
        initialCwd.canonicalPath,
        arrayOf(shellPath),
        environment,
        transcriptRows,
        client
    ).also { it.mSessionName = initialTitle }

    override fun write(text: String) {
        if (!native.isRunning) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        native.write(bytes, 0, bytes.size)
        touch()
    }

    override fun writeCodePoint(codePoint: Int, altDown: Boolean) {
        if (!native.isRunning) return
        native.writeCodePoint(altDown, codePoint)
        touch()
    }

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        // Termux 0.118.x exposes PTY resizing in character cells; pixel metrics remain in the abstraction for future engines.
        native.updateSize(columns.coerceAtLeast(2), rows.coerceAtLeast(2))
        refresh(native)
    }

    override fun clear() {
        native.reset()
        native.emulator?.screen?.clearTranscript()
        refresh(native)
    }

    override fun close() {
        if (native.isRunning) write("exit\r")
        touch()
    }

    override fun kill() {
        native.finishIfRunning()
        touch()
    }

    override fun transcript(): String = native.emulator?.screen?.transcriptText.orEmpty()

    internal fun rename(title: String) {
        native.mSessionName = title
        _state.value = _state.value.copy(title = title, lastUsedAt = System.currentTimeMillis())
        onChanged(_state.value)
    }

    private fun touch() {
        _state.value = _state.value.copy(lastUsedAt = System.currentTimeMillis())
        onChanged(_state.value)
    }

    private fun refresh(session: NativeTerminalSession, finished: Boolean = false) {
        val running = !finished && session.isRunning
        val currentCwd = session.cwd ?: _state.value.cwd
        val exit = if (running) null else runCatching { session.exitStatus }.getOrNull()
        _state.value = _state.value.copy(
            cwd = currentCwd,
            lastUsedAt = System.currentTimeMillis(),
            running = running,
            pid = session.pid.takeIf { it > 0 },
            exitCode = exit,
            transcript = transcript()
        )
        onChanged(_state.value)
    }
}
