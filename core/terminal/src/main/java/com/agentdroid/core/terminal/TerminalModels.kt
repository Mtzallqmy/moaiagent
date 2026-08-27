package com.agentdroid.core.terminal

import kotlinx.coroutines.flow.StateFlow

data class TerminalSessionMetadata(
    val sessionId: String,
    val workspaceId: String,
    val title: String,
    val cwd: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val running: Boolean = true,
    val exitCode: Int? = null
)

data class TerminalSessionState(
    val sessionId: String,
    val workspaceId: String,
    val title: String,
    val cwd: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val running: Boolean,
    val pid: Int? = null,
    val exitCode: Int? = null,
    val transcript: String = ""
)

interface TerminalSession {
    val state: StateFlow<TerminalSessionState>
    fun write(text: String)
    fun writeCodePoint(codePoint: Int, altDown: Boolean = false)
    fun resize(columns: Int, rows: Int, cellWidthPixels: Int = 0, cellHeightPixels: Int = 0)
    fun clear()
    fun close()
    fun kill()
    fun transcript(): String
}

interface TerminalManager {
    val sessions: StateFlow<List<TerminalSessionState>>
    fun create(workspaceId: String, cwd: String = ".", title: String? = null, columns: Int = 80, rows: Int = 24): TerminalSession
    fun get(sessionId: String): TerminalSession?
    fun rename(sessionId: String, title: String): Boolean
    fun close(sessionId: String, force: Boolean = false): Boolean
    fun clear(sessionId: String): Boolean
}

interface TerminalSessionMetadataStore {
    suspend fun save(metadata: TerminalSessionMetadata)
    suspend fun get(sessionId: String): TerminalSessionMetadata?
    suspend fun list(workspaceId: String? = null): List<TerminalSessionMetadata>
    suspend fun markPreviouslyRunningStale(now: Long = System.currentTimeMillis())

    companion object {
        val NOOP = object : TerminalSessionMetadataStore {
            override suspend fun save(metadata: TerminalSessionMetadata) = Unit
            override suspend fun get(sessionId: String) = null
            override suspend fun list(workspaceId: String?) = emptyList<TerminalSessionMetadata>()
            override suspend fun markPreviouslyRunningStale(now: Long) = Unit
        }
    }
}

interface TerminalClipboard {
    fun copy(text: String)
    fun paste(): String?

    companion object {
        val NONE = object : TerminalClipboard {
            override fun copy(text: String) = Unit
            override fun paste(): String? = null
        }
    }
}
