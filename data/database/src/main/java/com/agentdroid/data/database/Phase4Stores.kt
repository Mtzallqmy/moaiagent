package com.agentdroid.data.database

import androidx.room.withTransaction
import com.agentdroid.core.artifacts.Artifact
import com.agentdroid.core.artifacts.ArtifactStorage
import com.agentdroid.core.artifacts.ArtifactType
import com.agentdroid.core.artifacts.SourceReference
import com.agentdroid.core.browser.BrowserSessionMetadata
import com.agentdroid.core.browser.BrowserTabMetadata
import com.agentdroid.core.research.ResearchFinding
import com.agentdroid.core.research.ResearchReport
import com.agentdroid.core.research.ResearchSession
import com.agentdroid.core.research.ResearchSessionRepository
import com.agentdroid.core.research.ResearchSource
import com.agentdroid.core.subagents.SubagentStatus
import com.agentdroid.core.subagents.SubagentTimelineItem
import com.agentdroid.core.tasks.ArtifactRef
import com.agentdroid.core.tasks.PersistedTaskRecord
import com.agentdroid.core.tasks.Task
import com.agentdroid.core.tasks.TaskEvent
import com.agentdroid.core.tasks.TaskEventType
import com.agentdroid.core.tasks.TaskPersistence
import com.agentdroid.core.tasks.TaskPlan
import com.agentdroid.core.tasks.TaskStatus
import com.agentdroid.core.tasks.TaskStep
import com.agentdroid.core.tasks.TaskWaitReason
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

private val phase4Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Transactional adapter used by [com.agentdroid.core.tasks.InMemoryTaskRepository]. */
class RoomTaskPersistence(
    private val database: AgentDatabase,
    private val dao: TaskDao = database.tasks(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val eventId: () -> String = { UUID.randomUUID().toString() }
) : TaskPersistence {
    override suspend fun loadAll(): List<PersistedTaskRecord> {
        recoverInterruptedExecution()
        return database.withTransaction {
            dao.all().mapNotNull { entity -> entity.toRecordOrNull(dao.steps(entity.id), dao.events(entity.id)) }
        }
    }

    override suspend fun save(record: PersistedTaskRecord) = database.withTransaction {
        dao.upsertTask(record.task.toEntity())
        dao.deleteSteps(record.task.id)
        dao.upsertSteps(record.task.plan.steps.map { it.toEntity(record.task.id) })
        dao.deleteEvents(record.task.id)
        dao.upsertEvents(record.events.map(TaskEvent::toEntity))
    }

    override suspend fun delete(taskId: String) = database.withTransaction {
        dao.deleteSteps(taskId)
        dao.deleteEvents(taskId)
        dao.deleteTask(taskId)
    }

    /**
     * OS work is never claimed to have survived process death. Interrupted work becomes explicitly
     * retryable and gets a durable, user-visible recovery event.
     */
    suspend fun recoverInterruptedExecution() = database.withTransaction {
        val interrupted = dao.interrupted()
        if (interrupted.isEmpty()) return@withTransaction
        val now = clock()
        dao.markInterruptedStepsRetryable()
        dao.markInterruptedTasksForRecovery(now)
        dao.upsertEvents(interrupted.map { task ->
            TaskEventEntity(
                id = eventId(), taskId = task.id, type = TaskEventType.RECOVERY_REQUIRED.name,
                timestamp = now, stepId = task.currentStepId,
                message = "Task execution was interrupted and must be resumed explicitly",
                taskRevision = task.revision + 1
            )
        })
    }
}

class RoomArtifactMetadataStore(private val dao: ArtifactMetadataDao) {
    suspend fun save(artifact: Artifact) = dao.upsert(artifact.toEntity())
    suspend fun get(id: String): Artifact? = dao.get(id)?.toModelOrNull()
    suspend fun list(workspaceId: String, conversationId: String? = null, taskId: String? = null): List<Artifact> =
        dao.list(workspaceId, conversationId, taskId).mapNotNull(ArtifactEntity::toModelOrNull)
    suspend fun delete(id: String) = dao.delete(id)
}

class RoomResearchSessionRepository(
    private val database: AgentDatabase,
    private val dao: ResearchDao = database.research()
) : ResearchSessionRepository {
    override suspend fun create(session: ResearchSession) = database.withTransaction {
        dao.insertSession(session.toEntity())
        dao.upsertSources(session.sources.map { it.toEntity(session.id) })
        dao.upsertFindings(session.findings.map { it.toEntity(session.id) })
    }

    override suspend fun get(sessionId: String): ResearchSession? = database.withTransaction {
        dao.session(sessionId)?.toModelOrNull(dao.sources(sessionId), dao.findings(sessionId))
    }

    override suspend fun update(session: ResearchSession) = database.withTransaction {
        check(dao.session(session.id) != null) { "Unknown research session: ${session.id}" }
        dao.upsertSession(session.toEntity())
        dao.deleteSources(session.id)
        dao.deleteFindings(session.id)
        dao.upsertSources(session.sources.map { it.toEntity(session.id) })
        dao.upsertFindings(session.findings.map { it.toEntity(session.id) })
    }
}

class RoomBrowserMetadataStore(
    private val database: AgentDatabase,
    private val dao: BrowserMetadataDao = database.browserSessions()
) {
    suspend fun save(metadata: BrowserSessionMetadata) = database.withTransaction {
        val safeTabs = metadata.tabs.map { tab ->
            BrowserTabEntity(metadata.sessionId, tab.tabId, tab.title.take(MAX_BROWSER_TITLE), sanitizePersistedUrl(tab.currentUrl), tab.lastUsedAt)
        }
        dao.upsertSession(
            BrowserSessionEntity(
                metadata.sessionId, metadata.workspaceId, metadata.conversationId, metadata.activeTabId,
                sanitizePersistedUrl(metadata.currentUrl), metadata.lastUsedAt
            )
        )
        dao.deleteTabs(metadata.sessionId)
        dao.upsertTabs(safeTabs)
    }

    suspend fun get(sessionId: String): BrowserSessionMetadata? = database.withTransaction {
        dao.session(sessionId)?.toModel(dao.tabs(sessionId))
    }

    suspend fun list(workspaceId: String? = null): List<BrowserSessionMetadata> = database.withTransaction {
        dao.sessions(workspaceId).map { it.toModel(dao.tabs(it.sessionId)) }
    }

    suspend fun delete(sessionId: String) = database.withTransaction {
        dao.deleteTabs(sessionId)
        dao.deleteSession(sessionId)
    }

    companion object { private const val MAX_BROWSER_TITLE = 1_000 }
}

data class SubagentDelegationEvent(
    val id: String,
    val item: SubagentTimelineItem,
    val rootTaskId: String?,
    val createdAt: Long
)

class RoomSubagentDelegationEventStore(private val dao: SubagentDelegationEventDao) {
    suspend fun save(event: SubagentDelegationEvent) = dao.upsert(event.toEntity())
    suspend fun list(rootTaskId: String? = null): List<SubagentDelegationEvent> =
        dao.list(rootTaskId).mapNotNull(SubagentDelegationEventEntity::toModelOrNull)
    suspend fun recoverInterrupted(now: Long = System.currentTimeMillis()): Int = dao.markInterruptedFailed(now)
}

private fun Task.toEntity() = TaskEntity(
    id, title, workspaceId, conversationId, plan.summary, plan.revision, plan.updatedAt,
    status.name, waitReason.name, progress, currentStepId, phase4Json.encodeToString(artifacts),
    createdAt, startedAt, updatedAt, finishedAt, failure, recoveryRequired, revision
)

private fun TaskStep.toEntity(taskId: String) = TaskStepEntity(
    taskId, id, title, description, position, status.name, retryCount, maxRetries, startedAt, finishedAt, error
)

private fun TaskEvent.toEntity() = TaskEventEntity(id, taskId, type.name, timestamp, stepId, message, taskRevision)

private fun TaskEntity.toRecordOrNull(steps: List<TaskStepEntity>, events: List<TaskEventEntity>): PersistedTaskRecord? = runCatching {
    val taskSteps = steps.map { it.toModel() }
    val task = Task(
        id, title, workspaceId, conversationId,
        TaskPlan(planSummary, taskSteps, planRevision, planUpdatedAt),
        TaskStatus.valueOf(status), TaskWaitReason.valueOf(waitReason), progress, currentStepId,
        phase4Json.decodeFromString<List<ArtifactRef>>(artifactRefsJson), createdAt, startedAt, updatedAt,
        finishedAt, failure, recoveryRequired, revision
    )
    PersistedTaskRecord(task, events.map { it.toModel() })
}.getOrNull()

private fun TaskStepEntity.toModel() = TaskStep(
    id, title, description, position, TaskStatus.valueOf(status), retryCount, maxRetries, startedAt, finishedAt, error
)

private fun TaskEventEntity.toModel() = TaskEvent(id, taskId, TaskEventType.valueOf(type), timestamp, stepId, message, taskRevision)

private fun Artifact.toEntity() = ArtifactEntity(
    id, taskId, conversationId, workspaceId, type.name, title, filePath, mimeType, createdAt, updatedAt,
    phase4Json.encodeToString(sourceReferences.map { it.copy(url = sanitizeResearchUrl(it.url)) }), storage.name, sizeBytes
)

private fun ArtifactEntity.toModelOrNull(): Artifact? = runCatching {
    Artifact(
        id, taskId, conversationId, workspaceId, ArtifactType.valueOf(type), title, filePath, mimeType,
        createdAt, updatedAt, phase4Json.decodeFromString<List<SourceReference>>(sourceReferencesJson),
        ArtifactStorage.valueOf(storage), sizeBytes
    )
}.getOrNull()

private fun ResearchSession.toEntity() = ResearchSessionEntity(
    id, query, createdAt, updatedAt, comparison, report?.let { phase4Json.encodeToString(it.sanitized()) }
)

private fun ResearchSource.toEntity(sessionId: String) = ResearchSourceEntity(
    sessionId, id, sanitizeResearchUrl(url), title, domain, retrievedAt, excerpt, relevance
)

private fun ResearchFinding.toEntity(sessionId: String) = ResearchFindingEntity(
    sessionId, id, text, phase4Json.encodeToString(sourceIds), relevance, createdAt
)

private fun ResearchSessionEntity.toModelOrNull(
    sourceRows: List<ResearchSourceEntity>, findingRows: List<ResearchFindingEntity>
): ResearchSession? = runCatching {
    ResearchSession(
        id, query, createdAt, updatedAt, sourceRows.map { it.toModel() }, findingRows.map { it.toModel() },
        comparison, reportJson?.let { phase4Json.decodeFromString<ResearchReport>(it) }
    )
}.getOrNull()

private fun ResearchSourceEntity.toModel() = ResearchSource(id, url, title, domain, retrievedAt, excerpt, relevance)
private fun ResearchFindingEntity.toModel() = ResearchFinding(
    id, text, phase4Json.decodeFromString<List<String>>(sourceIdsJson), relevance, createdAt
)

private fun BrowserSessionEntity.toModel(tabs: List<BrowserTabEntity>) = BrowserSessionMetadata(
    sessionId, workspaceId, conversationId,
    tabs.map { BrowserTabMetadata(it.tabId, it.title, it.currentUrl, it.lastUsedAt) },
    activeTabId, currentUrl, lastUsedAt
)

private fun SubagentDelegationEvent.toEntity(): SubagentDelegationEventEntity {
    val safeLabel = redactSensitiveSummary(item.label)
    val safeFailure = item.failureSummary?.let(::redactSensitiveSummary)
    return SubagentDelegationEventEntity(
        id, item.subagentId, item.taskId, rootTaskId, item.parentSubagentId, item.role.name,
        item.status.name, safeLabel, item.startedAt, item.finishedAt, safeFailure, createdAt
    )
}

private fun SubagentDelegationEventEntity.toModelOrNull(): SubagentDelegationEvent? = runCatching {
    SubagentDelegationEvent(
        id,
        SubagentTimelineItem(
            subagentId, parentSubagentId, taskId, com.agentdroid.core.subagents.SubagentRole.valueOf(role),
            label, SubagentStatus.valueOf(status), startedAt, finishedAt, failureSummary
        ),
        rootTaskId,
        createdAt
    )
}.getOrNull()

/** Drops URL credentials/fragments and secret-looking query values before browser metadata is persisted. */
internal fun sanitizePersistedUrl(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    if (raw == "about:blank") return raw
    return runCatching {
        val uri = URI(raw)
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val safeQuery = uri.rawQuery?.split('&')?.mapNotNull { pair ->
            val key = pair.substringBefore('=').lowercase()
            if (SENSITIVE_QUERY_KEYS.any { key == it || key.contains(it) }) null else pair
        }?.joinToString("&")?.takeIf(String::isNotBlank)
        val path = uri.rawPath?.takeIf(String::isNotBlank) ?: "/"
        URI(uri.scheme.lowercase(), null, uri.host, uri.port, path, safeQuery, null).toASCIIString()
    }.getOrNull()
}

private fun sanitizeResearchUrl(raw: String): String = sanitizePersistedUrl(raw)
    ?: throw IllegalArgumentException("Research URL must be a safe HTTP(S) URL")

private fun ResearchReport.sanitized(): ResearchReport = copy(
    sources = sources.map { it.copy(url = sanitizeResearchUrl(it.url)) },
    markdown = sanitizeUrlsInText(markdown)
)

private fun sanitizeUrlsInText(value: String): String = URL_IN_TEXT.replace(value) { match ->
    val core = match.value.trimEnd('.', ',', ';', ')', ']')
    val suffix = match.value.removePrefix(core)
    (sanitizePersistedUrl(core) ?: "[unsafe URL removed]") + suffix
}

internal fun redactSensitiveSummary(value: String): String = value
    .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED]")
    .replace(Regex("(?i)(authorization|password|passwd|api[_-]?key|access[_-]?token|refresh[_-]?token)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
    .take(2_000)

private val SENSITIVE_QUERY_KEYS = setOf(
    "token", "access_token", "refresh_token", "auth", "authorization", "password", "passwd",
    "api_key", "apikey", "key", "code", "session", "signature", "sig"
)
private val URL_IN_TEXT = Regex("https?://[^\\s<>{}\\\"]+")

/** One startup hook for the process-local Phase 4 runtimes. */
class Phase4RecoveryCoordinator(
    private val tasks: RoomTaskPersistence,
    private val subagents: RoomSubagentDelegationEventStore
) {
    suspend fun recover(now: Long = System.currentTimeMillis()) {
        tasks.recoverInterruptedExecution()
        subagents.recoverInterrupted(now)
    }
}
