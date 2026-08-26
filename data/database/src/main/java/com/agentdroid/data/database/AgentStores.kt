package com.agentdroid.data.database

import com.agentdroid.core.agent.AuditEntry
import com.agentdroid.core.agent.AuditSink
import com.agentdroid.core.agent.PermissionDecision
import com.agentdroid.core.agent.PermissionScope
import com.agentdroid.core.permissions.PermissionRule
import com.agentdroid.core.permissions.PermissionRuleStore
import com.agentdroid.core.workspace.ChangeSet
import com.agentdroid.core.workspace.ChangeSetStatus
import com.agentdroid.core.workspace.ChangeSetStore
import com.agentdroid.core.workspace.FileChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val agentStoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class RoomPermissionRuleStore(private val dao: PermissionRuleDao) : PermissionRuleStore {
    override suspend fun list(): List<PermissionRule> = dao.list().map { it.toDomain() }
    override suspend fun save(rule: PermissionRule) = dao.upsert(rule.toEntity())
    override suspend fun delete(id: String) = dao.deleteById(id)
    fun observe(): Flow<List<PermissionRule>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    private fun PermissionRuleEntity.toDomain() = PermissionRule(id, toolName, workspaceId, PermissionDecision.valueOf(decision), PermissionScope.valueOf(scope), createdAt)
    private fun PermissionRule.toEntity() = PermissionRuleEntity(id, toolName, workspaceId, decision.name, scope.name, createdAt)
}

class RoomAuditSink(private val dao: AuditLogDao) : AuditSink {
    override suspend fun record(entry: AuditEntry) {
        dao.insert(AuditLogEntity(UUID.randomUUID().toString(), entry.toolCallId, entry.toolName, entry.inputSummary, entry.resultSummary, entry.durationMs, entry.status, entry.permissionDecision.name, entry.timestamp, entry.workspaceId, entry.conversationId))
    }
}

class AuditRepository(private val dao: AuditLogDao) {
    fun observeRecent(limit: Int = 200): Flow<List<AuditLogEntity>> = dao.observeRecent(limit)
}

class RoomChangeSetStore(private val dao: ChangeSetDao) : ChangeSetStore {
    override suspend fun save(changeSet: ChangeSet) = dao.upsert(changeSet.toEntity())
    override suspend fun get(id: String): ChangeSet? = dao.get(id)?.toDomain()
    override suspend fun list(workspaceId: String?): List<ChangeSet> = dao.list(workspaceId).map { it.toDomain() }
    fun observe(workspaceId: String): Flow<List<ChangeSet>> = dao.observe(workspaceId).map { rows -> rows.map { it.toDomain() } }

    private fun ChangeSet.toEntity() = ChangeSetEntity(id, workspaceId, agentStoreJson.encodeToString(files), createdAt, status.name, originatingToolCallId, appliedAt, revertedAt)
    private fun ChangeSetEntity.toDomain() = ChangeSet(
        id = id,
        workspaceId = workspaceId,
        files = runCatching { agentStoreJson.decodeFromString<List<FileChange>>(filesJson) }.getOrDefault(emptyList()),
        createdAt = createdAt,
        status = runCatching { ChangeSetStatus.valueOf(status) }.getOrDefault(ChangeSetStatus.CONFLICTED),
        originatingToolCallId = originatingToolCallId,
        appliedAt = appliedAt,
        revertedAt = revertedAt
    )
}
