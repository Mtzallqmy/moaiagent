package com.agentdroid.data.database

import com.agentdroid.core.runtime.ProcessMetadata
import com.agentdroid.core.runtime.ProcessMetadataStore
import com.agentdroid.core.runtime.ProcessStatus
import com.agentdroid.core.terminal.TerminalSessionMetadata
import com.agentdroid.core.terminal.TerminalSessionMetadataStore

class RoomProcessMetadataStore(private val dao: ProcessMetadataDao) : ProcessMetadataStore {
    override suspend fun save(metadata: ProcessMetadata) = dao.upsert(metadata.toEntity())
    override suspend fun get(processId: String): ProcessMetadata? = dao.get(processId)?.toModel()
    override suspend fun list(workspaceId: String?): List<ProcessMetadata> = dao.list(workspaceId).map(ProcessMetadataEntity::toModel)
    override suspend fun markPreviouslyRunningStale(now: Long) = dao.markRunningStale(now)
}

class RoomTerminalSessionMetadataStore(private val dao: TerminalSessionDao) : TerminalSessionMetadataStore {
    override suspend fun save(metadata: TerminalSessionMetadata) = dao.upsert(metadata.toEntity())
    override suspend fun get(sessionId: String): TerminalSessionMetadata? = dao.get(sessionId)?.toModel()
    override suspend fun list(workspaceId: String?): List<TerminalSessionMetadata> = dao.list(workspaceId).map(TerminalSessionEntity::toModel)
    override suspend fun markPreviouslyRunningStale(now: Long) = dao.markRunningStale(now)
}

private fun ProcessMetadata.toEntity() = ProcessMetadataEntity(processId, sessionId, workspaceId, command, cwd, status.name, exitCode, startedAt, finishedAt, background)
private fun ProcessMetadataEntity.toModel() = ProcessMetadata(processId, sessionId, workspaceId, command, cwd, ProcessStatus.valueOf(status), exitCode, startedAt, finishedAt, background)
private fun TerminalSessionMetadata.toEntity() = TerminalSessionEntity(sessionId, workspaceId, title, cwd, createdAt, lastUsedAt, running, exitCode)
private fun TerminalSessionEntity.toModel() = TerminalSessionMetadata(sessionId, workspaceId, title, cwd, createdAt, lastUsedAt, running, exitCode)
