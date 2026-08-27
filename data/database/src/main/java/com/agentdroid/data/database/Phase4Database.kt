package com.agentdroid.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Phase 4 task header. Steps and events are normalized to allow atomic progress updates. */
@Entity(tableName = "tasks", indices = [Index("workspaceId"), Index("conversationId"), Index("status"), Index("updatedAt")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val workspaceId: String,
    val conversationId: String,
    val planSummary: String,
    val planRevision: Int,
    val planUpdatedAt: Long,
    val status: String,
    val waitReason: String,
    val progress: Int,
    val currentStepId: String?,
    val artifactRefsJson: String,
    val createdAt: Long,
    val startedAt: Long?,
    val updatedAt: Long,
    val finishedAt: Long?,
    val failure: String?,
    val recoveryRequired: Boolean,
    val revision: Long
)

@Entity(tableName = "task_steps", primaryKeys = ["taskId", "id"], indices = [Index("taskId"), Index("status")])
data class TaskStepEntity(
    val taskId: String,
    val id: String,
    val title: String,
    val description: String?,
    val position: Int,
    val status: String,
    val retryCount: Int,
    val maxRetries: Int,
    val startedAt: Long?,
    val finishedAt: Long?,
    val error: String?
)

@Entity(tableName = "task_events", indices = [Index("taskId"), Index("timestamp")])
data class TaskEventEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val type: String,
    val timestamp: Long,
    val stepId: String?,
    val message: String?,
    val taskRevision: Long
)

@Entity(tableName = "artifacts", indices = [Index("taskId"), Index("conversationId"), Index("workspaceId"), Index("updatedAt")])
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val conversationId: String,
    val workspaceId: String,
    val type: String,
    val title: String,
    val filePath: String,
    val mimeType: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceReferencesJson: String,
    val storage: String,
    val sizeBytes: Long
)

@Entity(tableName = "research_sessions", indices = [Index("updatedAt")])
data class ResearchSessionEntity(
    @PrimaryKey val id: String,
    val query: String,
    val createdAt: Long,
    val updatedAt: Long,
    val comparison: String?,
    val reportJson: String?
)

@Entity(tableName = "research_sources", primaryKeys = ["sessionId", "id"], indices = [Index("sessionId"), Index("domain"), Index("retrievedAt")])
data class ResearchSourceEntity(
    val sessionId: String,
    val id: String,
    val url: String,
    val title: String,
    val domain: String,
    val retrievedAt: Long,
    val excerpt: String,
    val relevance: Double
)

@Entity(tableName = "research_findings", primaryKeys = ["sessionId", "id"], indices = [Index("sessionId"), Index("createdAt")])
data class ResearchFindingEntity(
    val sessionId: String,
    val id: String,
    val text: String,
    val sourceIdsJson: String,
    val relevance: Double,
    val createdAt: Long
)

/** Browser metadata only. Cookies, headers, page bodies, form values and screenshots never enter Room. */
@Entity(tableName = "browser_sessions", indices = [Index("workspaceId"), Index("conversationId"), Index("lastUsedAt")])
data class BrowserSessionEntity(
    @PrimaryKey val sessionId: String,
    val workspaceId: String,
    val conversationId: String,
    val activeTabId: String,
    val currentUrl: String?,
    val lastUsedAt: Long
)

@Entity(tableName = "browser_tabs", primaryKeys = ["sessionId", "tabId"], indices = [Index("sessionId"), Index("lastUsedAt")])
data class BrowserTabEntity(
    val sessionId: String,
    val tabId: String,
    val title: String,
    val currentUrl: String?,
    val lastUsedAt: Long
)

/** UI/audit-safe delegation event. It deliberately excludes prompts, context, tool inputs and model output. */
@Entity(tableName = "subagent_delegation_events", indices = [Index("subagentId"), Index("taskId"), Index("rootTaskId"), Index("createdAt")])
data class SubagentDelegationEventEntity(
    @PrimaryKey val id: String,
    val subagentId: String,
    val taskId: String,
    val rootTaskId: String?,
    val parentSubagentId: String?,
    val role: String,
    val status: String,
    val label: String,
    val startedAt: Long?,
    val finishedAt: Long?,
    val failureSummary: String?,
    val createdAt: Long
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC") suspend fun all(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE status IN ('RUNNING','WAITING_PERMISSION')") suspend fun interrupted(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE id = :taskId") suspend fun get(taskId: String): TaskEntity?
    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY position") suspend fun steps(taskId: String): List<TaskStepEntity>
    @Query("SELECT * FROM task_events WHERE taskId = :taskId ORDER BY timestamp, id") suspend fun events(taskId: String): List<TaskEventEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTask(task: TaskEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSteps(steps: List<TaskStepEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEvents(events: List<TaskEventEntity>)
    @Query("DELETE FROM task_steps WHERE taskId = :taskId") suspend fun deleteSteps(taskId: String)
    @Query("DELETE FROM task_events WHERE taskId = :taskId") suspend fun deleteEvents(taskId: String)
    @Query("DELETE FROM tasks WHERE id = :taskId") suspend fun deleteTask(taskId: String)
    @Query("UPDATE tasks SET status = 'WAITING_USER', waitReason = 'RECOVERY_REQUIRED', recoveryRequired = 1, currentStepId = NULL, updatedAt = :now, revision = revision + 1 WHERE status IN ('RUNNING','WAITING_PERMISSION')")
    suspend fun markInterruptedTasksForRecovery(now: Long): Int
    @Query("UPDATE task_steps SET status = 'PENDING', error = 'Interrupted; explicit retry required', startedAt = NULL, finishedAt = NULL WHERE status IN ('RUNNING','WAITING_PERMISSION') AND taskId IN (SELECT id FROM tasks WHERE status IN ('RUNNING','WAITING_PERMISSION'))")
    suspend fun markInterruptedStepsRetryable(): Int
}

@Dao
interface ArtifactMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: ArtifactEntity)
    @Query("SELECT * FROM artifacts WHERE id = :id") suspend fun get(id: String): ArtifactEntity?
    @Query("SELECT * FROM artifacts WHERE workspaceId = :workspaceId AND (:conversationId IS NULL OR conversationId = :conversationId) AND (:taskId IS NULL OR taskId = :taskId) ORDER BY updatedAt DESC")
    suspend fun list(workspaceId: String, conversationId: String?, taskId: String?): List<ArtifactEntity>
    @Query("DELETE FROM artifacts WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface ResearchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSession(item: ResearchSessionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSession(item: ResearchSessionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSources(items: List<ResearchSourceEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFindings(items: List<ResearchFindingEntity>)
    @Query("SELECT * FROM research_sessions WHERE id = :id") suspend fun session(id: String): ResearchSessionEntity?
    @Query("SELECT * FROM research_sources WHERE sessionId = :id ORDER BY retrievedAt, id") suspend fun sources(id: String): List<ResearchSourceEntity>
    @Query("SELECT * FROM research_findings WHERE sessionId = :id ORDER BY createdAt, id") suspend fun findings(id: String): List<ResearchFindingEntity>
    @Query("DELETE FROM research_sources WHERE sessionId = :id") suspend fun deleteSources(id: String)
    @Query("DELETE FROM research_findings WHERE sessionId = :id") suspend fun deleteFindings(id: String)
}

@Dao
interface BrowserMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSession(item: BrowserSessionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTabs(items: List<BrowserTabEntity>)
    @Query("SELECT * FROM browser_sessions WHERE sessionId = :id") suspend fun session(id: String): BrowserSessionEntity?
    @Query("SELECT * FROM browser_sessions WHERE (:workspaceId IS NULL OR workspaceId = :workspaceId) ORDER BY lastUsedAt DESC") suspend fun sessions(workspaceId: String?): List<BrowserSessionEntity>
    @Query("SELECT * FROM browser_tabs WHERE sessionId = :id ORDER BY lastUsedAt DESC") suspend fun tabs(id: String): List<BrowserTabEntity>
    @Query("DELETE FROM browser_tabs WHERE sessionId = :id") suspend fun deleteTabs(id: String)
    @Query("DELETE FROM browser_sessions WHERE sessionId = :id") suspend fun deleteSession(id: String)
}

@Dao
interface SubagentDelegationEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: SubagentDelegationEventEntity)
    @Query("SELECT * FROM subagent_delegation_events WHERE (:rootTaskId IS NULL OR rootTaskId = :rootTaskId) ORDER BY createdAt, id")
    suspend fun list(rootTaskId: String?): List<SubagentDelegationEventEntity>
    @Query("UPDATE subagent_delegation_events SET status = 'FAILED', finishedAt = :now, failureSummary = 'Interrupted; retry or fallback required' WHERE status IN ('QUEUED','RUNNING')")
    suspend fun markInterruptedFailed(now: Long): Int
}
