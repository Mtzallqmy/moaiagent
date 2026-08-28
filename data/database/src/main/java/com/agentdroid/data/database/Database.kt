package com.agentdroid.data.database

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(@PrimaryKey val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val providerId: String? = null, val modelId: String? = null, val workspaceId: String? = null, val archived: Boolean = false)
@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val role: String, val content: String, val status: String, val createdAt: Long, val updatedAt: Long, val providerId: String? = null, val modelId: String? = null, val usageJson: String? = null, val errorJson: String? = null)
@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(@PrimaryKey val id: String, val name: String, val kind: String, val baseUrl: String?, val modelId: String?, val secretAlias: String?, val organizationId: String?, val appName: String?, val siteUrl: String?, val customHeadersJson: String, val enabled: Boolean)
@Entity(tableName = "workspaces")
data class WorkspaceEntity(@PrimaryKey val id: String, val name: String, val description: String, val createdAt: Long, val updatedAt: Long, val rootPath: String = "", val lastOpenedFile: String? = null)
@Entity(tableName = "memory_entries")
data class MemoryEntryEntity(@PrimaryKey val id: String, val scope: String, val workspaceId: String?, val title: String, val content: String, val enabled: Boolean, val createdAt: Long, val updatedAt: Long)
@Entity(tableName = "skills")
data class SkillEntity(@PrimaryKey val id: String, val name: String, val description: String, val instructions: String, val enabled: Boolean, val scope: String, val workspaceId: String?, val createdAt: Long, val updatedAt: Long)
@Entity(tableName = "app_settings")
data class AppSettingEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "permission_rules", indices = [Index("toolName"), Index("workspaceId")])
data class PermissionRuleEntity(@PrimaryKey val id: String, val toolName: String, val workspaceId: String?, val decision: String, val scope: String, val createdAt: Long)

@Entity(tableName = "audit_logs", indices = [Index("workspaceId"), Index("conversationId"), Index("timestamp")])
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val toolCallId: String,
    val toolName: String,
    val inputSummary: String,
    val resultSummary: String,
    val durationMs: Long,
    val status: String,
    val permissionDecision: String,
    val timestamp: Long,
    val workspaceId: String,
    val conversationId: String,
    val metadataJson: String = "{}"
)

@Entity(tableName = "change_sets", indices = [Index("workspaceId"), Index("createdAt")])
data class ChangeSetEntity(@PrimaryKey val id: String, val workspaceId: String, val filesJson: String, val createdAt: Long, val status: String, val originatingToolCallId: String?, val appliedAt: Long?, val revertedAt: Long?)

@Entity(tableName = "runtime_processes", indices = [Index("workspaceId"), Index("status"), Index("startedAt")])
data class ProcessMetadataEntity(
    @PrimaryKey val processId: String,
    val sessionId: String?,
    val workspaceId: String,
    val command: String,
    val cwd: String,
    val status: String,
    val exitCode: Int?,
    val startedAt: Long,
    val finishedAt: Long?,
    val background: Boolean
)

@Entity(tableName = "terminal_sessions", indices = [Index("workspaceId"), Index("lastUsedAt")])
data class TerminalSessionEntity(
    @PrimaryKey val sessionId: String,
    val workspaceId: String,
    val title: String,
    val cwd: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val running: Boolean,
    val exitCode: Int?
)

@Dao interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY updatedAt DESC") fun observeAll(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations ORDER BY archived ASC, updatedAt DESC") fun observeIncludingArchived(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' ORDER BY updatedAt DESC") fun search(query: String): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun get(id: String): ConversationEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: ConversationEntity)
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteById(id: String)
    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id") suspend fun rename(id: String, title: String, now: Long = System.currentTimeMillis())
    @Query("UPDATE conversations SET archived = :archived, updatedAt = :now WHERE id = :id") suspend fun setArchived(id: String, archived: Boolean, now: Long = System.currentTimeMillis())
}
@Dao interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :id AND status NOT IN ('FAILED') ORDER BY createdAt ASC") fun observe(id: String): Flow<List<MessageEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: MessageEntity)
    @Query("DELETE FROM messages WHERE conversationId = :id") suspend fun deleteForConversation(id: String)
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND createdAt > :createdAt") suspend fun deleteAfter(conversationId: String, createdAt: Long)
}
@Dao interface ProviderDao {
    @Query("SELECT * FROM provider_configs ORDER BY name") fun observeAll(): Flow<List<ProviderConfigEntity>>
    @Query("SELECT * FROM provider_configs WHERE id = :id") suspend fun get(id: String): ProviderConfigEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: ProviderConfigEntity)
    @Query("DELETE FROM provider_configs WHERE id = :id") suspend fun deleteById(id: String)
    @Query("UPDATE provider_configs SET enabled = :enabled WHERE id = :id") suspend fun setEnabled(id: String, enabled: Boolean)
    @Query("UPDATE provider_configs SET modelId = :model WHERE id = :id") suspend fun setModel(id: String, model: String)
}
@Dao interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY updatedAt DESC") fun observeAll(): Flow<List<WorkspaceEntity>>
    @Query("SELECT * FROM workspaces WHERE id = :id") suspend fun get(id: String): WorkspaceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: WorkspaceEntity)
    @Query("DELETE FROM workspaces WHERE id = :id") suspend fun deleteById(id: String)
    @Query("UPDATE workspaces SET lastOpenedFile = :path, updatedAt = :now WHERE id = :id") suspend fun setLastOpenedFile(id: String, path: String?, now: Long = System.currentTimeMillis())
}
@Dao interface MemoryDao {
    @Query("SELECT * FROM memory_entries ORDER BY updatedAt DESC") fun observeAll(): Flow<List<MemoryEntryEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: MemoryEntryEntity)
    @Query("DELETE FROM memory_entries WHERE id = :id") suspend fun deleteById(id: String)
    @Query("UPDATE memory_entries SET enabled = :enabled, updatedAt = :now WHERE id = :id") suspend fun setEnabled(id: String, enabled: Boolean, now: Long = System.currentTimeMillis())
}
@Dao interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY updatedAt DESC") fun observeAll(): Flow<List<SkillEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: SkillEntity)
    @Query("DELETE FROM skills WHERE id = :id") suspend fun deleteById(id: String)
    @Query("UPDATE skills SET enabled = :enabled, updatedAt = :now WHERE id = :id") suspend fun setEnabled(id: String, enabled: Boolean, now: Long = System.currentTimeMillis())
}
@Dao interface SettingDao { @Query("SELECT * FROM app_settings") fun observeAll(): Flow<List<AppSettingEntity>>; @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: AppSettingEntity) }

@Dao interface PermissionRuleDao {
    @Query("SELECT * FROM permission_rules ORDER BY createdAt DESC") fun observeAll(): Flow<List<PermissionRuleEntity>>
    @Query("SELECT * FROM permission_rules ORDER BY createdAt DESC") suspend fun list(): List<PermissionRuleEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: PermissionRuleEntity)
    @Query("DELETE FROM permission_rules WHERE id = :id") suspend fun deleteById(id: String)
}

@Dao interface AuditLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: AuditLogEntity)
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT :limit") fun observeRecent(limit: Int = 200): Flow<List<AuditLogEntity>>
}

@Dao interface ChangeSetDao {
    @Query("SELECT * FROM change_sets WHERE id = :id") suspend fun get(id: String): ChangeSetEntity?
    @Query("SELECT * FROM change_sets WHERE (:workspaceId IS NULL OR workspaceId = :workspaceId) ORDER BY createdAt DESC") suspend fun list(workspaceId: String?): List<ChangeSetEntity>
    @Query("SELECT * FROM change_sets WHERE workspaceId = :workspaceId ORDER BY createdAt DESC") fun observe(workspaceId: String): Flow<List<ChangeSetEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: ChangeSetEntity)
}

@Dao interface ProcessMetadataDao {
    @Query("SELECT * FROM runtime_processes WHERE processId = :id") suspend fun get(id: String): ProcessMetadataEntity?
    @Query("SELECT * FROM runtime_processes WHERE (:workspaceId IS NULL OR workspaceId = :workspaceId) ORDER BY startedAt DESC") suspend fun list(workspaceId: String?): List<ProcessMetadataEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: ProcessMetadataEntity)
    @Query("UPDATE runtime_processes SET status = 'STALE', finishedAt = :now WHERE status IN ('STARTING','RUNNING')") suspend fun markRunningStale(now: Long)
}

@Dao interface TerminalSessionDao {
    @Query("SELECT * FROM terminal_sessions WHERE sessionId = :id") suspend fun get(id: String): TerminalSessionEntity?
    @Query("SELECT * FROM terminal_sessions WHERE (:workspaceId IS NULL OR workspaceId = :workspaceId) ORDER BY lastUsedAt DESC") suspend fun list(workspaceId: String?): List<TerminalSessionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: TerminalSessionEntity)
    @Query("UPDATE terminal_sessions SET running = 0, lastUsedAt = :now WHERE running = 1") suspend fun markRunningStale(now: Long)
}

@Database(
    entities = [
        ConversationEntity::class, MessageEntity::class, ProviderConfigEntity::class, WorkspaceEntity::class,
        MemoryEntryEntity::class, SkillEntity::class, AppSettingEntity::class, PermissionRuleEntity::class,
        AuditLogEntity::class, ChangeSetEntity::class, ProcessMetadataEntity::class, TerminalSessionEntity::class,
        TaskEntity::class, TaskStepEntity::class, TaskEventEntity::class, ArtifactEntity::class,
        ResearchSessionEntity::class, ResearchSourceEntity::class, ResearchFindingEntity::class,
        BrowserSessionEntity::class, BrowserTabEntity::class, SubagentDelegationEventEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun providers(): ProviderDao
    abstract fun workspaces(): WorkspaceDao
    abstract fun memory(): MemoryDao
    abstract fun skills(): SkillDao
    abstract fun settings(): SettingDao
    abstract fun permissionRules(): PermissionRuleDao
    abstract fun auditLogs(): AuditLogDao
    abstract fun changeSets(): ChangeSetDao
    abstract fun processes(): ProcessMetadataDao
    abstract fun terminalSessions(): TerminalSessionDao
    abstract fun tasks(): TaskDao
    abstract fun artifacts(): ArtifactMetadataDao
    abstract fun research(): ResearchDao
    abstract fun browserSessions(): BrowserMetadataDao
    abstract fun subagentEvents(): SubagentDelegationEventDao
}

object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE workspaces ADD COLUMN rootPath TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE workspaces ADD COLUMN lastOpenedFile TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS permission_rules (id TEXT NOT NULL PRIMARY KEY, toolName TEXT NOT NULL, workspaceId TEXT, decision TEXT NOT NULL, scope TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_permission_rules_toolName ON permission_rules(toolName)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_permission_rules_workspaceId ON permission_rules(workspaceId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS audit_logs (id TEXT NOT NULL PRIMARY KEY, toolCallId TEXT NOT NULL, toolName TEXT NOT NULL, inputSummary TEXT NOT NULL, resultSummary TEXT NOT NULL, durationMs INTEGER NOT NULL, status TEXT NOT NULL, permissionDecision TEXT NOT NULL, timestamp INTEGER NOT NULL, workspaceId TEXT NOT NULL, conversationId TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_logs_workspaceId ON audit_logs(workspaceId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_logs_conversationId ON audit_logs(conversationId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_logs_timestamp ON audit_logs(timestamp)")
            db.execSQL("CREATE TABLE IF NOT EXISTS change_sets (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, filesJson TEXT NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL, originatingToolCallId TEXT, appliedAt INTEGER, revertedAt INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_change_sets_workspaceId ON change_sets(workspaceId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_change_sets_createdAt ON change_sets(createdAt)")
        }
    }
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE audit_logs ADD COLUMN metadataJson TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("CREATE TABLE IF NOT EXISTS runtime_processes (processId TEXT NOT NULL PRIMARY KEY, sessionId TEXT, workspaceId TEXT NOT NULL, command TEXT NOT NULL, cwd TEXT NOT NULL, status TEXT NOT NULL, exitCode INTEGER, startedAt INTEGER NOT NULL, finishedAt INTEGER, background INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_runtime_processes_workspaceId ON runtime_processes(workspaceId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_runtime_processes_status ON runtime_processes(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_runtime_processes_startedAt ON runtime_processes(startedAt)")
            db.execSQL("CREATE TABLE IF NOT EXISTS terminal_sessions (sessionId TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, title TEXT NOT NULL, cwd TEXT NOT NULL, createdAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, running INTEGER NOT NULL, exitCode INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_terminal_sessions_workspaceId ON terminal_sessions(workspaceId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_terminal_sessions_lastUsedAt ON terminal_sessions(lastUsedAt)")
        }
    }
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, workspaceId TEXT NOT NULL, conversationId TEXT NOT NULL, planSummary TEXT NOT NULL, planRevision INTEGER NOT NULL, planUpdatedAt INTEGER NOT NULL, status TEXT NOT NULL, waitReason TEXT NOT NULL, progress INTEGER NOT NULL, currentStepId TEXT, artifactRefsJson TEXT NOT NULL, createdAt INTEGER NOT NULL, startedAt INTEGER, updatedAt INTEGER NOT NULL, finishedAt INTEGER, failure TEXT, recoveryRequired INTEGER NOT NULL, revision INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_workspaceId ON tasks(workspaceId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_conversationId ON tasks(conversationId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_status ON tasks(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_updatedAt ON tasks(updatedAt)")
            db.execSQL("CREATE TABLE IF NOT EXISTS task_steps (taskId TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, description TEXT, position INTEGER NOT NULL, status TEXT NOT NULL, retryCount INTEGER NOT NULL, maxRetries INTEGER NOT NULL, startedAt INTEGER, finishedAt INTEGER, error TEXT, PRIMARY KEY(taskId, id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_task_steps_taskId ON task_steps(taskId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_task_steps_status ON task_steps(status)")
            db.execSQL("CREATE TABLE IF NOT EXISTS task_events (id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, type TEXT NOT NULL, timestamp INTEGER NOT NULL, stepId TEXT, message TEXT, taskRevision INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_task_events_taskId ON task_events(taskId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_task_events_timestamp ON task_events(timestamp)")

            db.execSQL("CREATE TABLE IF NOT EXISTS artifacts (id TEXT NOT NULL PRIMARY KEY, taskId TEXT, conversationId TEXT NOT NULL, workspaceId TEXT NOT NULL, type TEXT NOT NULL, title TEXT NOT NULL, filePath TEXT NOT NULL, mimeType TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, sourceReferencesJson TEXT NOT NULL, storage TEXT NOT NULL, sizeBytes INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_taskId ON artifacts(taskId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_conversationId ON artifacts(conversationId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_workspaceId ON artifacts(workspaceId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_updatedAt ON artifacts(updatedAt)")

            db.execSQL("CREATE TABLE IF NOT EXISTS research_sessions (id TEXT NOT NULL PRIMARY KEY, query TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, comparison TEXT, reportJson TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_research_sessions_updatedAt ON research_sessions(updatedAt)")
            db.execSQL("CREATE TABLE IF NOT EXISTS research_sources (sessionId TEXT NOT NULL, id TEXT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, domain TEXT NOT NULL, retrievedAt INTEGER NOT NULL, excerpt TEXT NOT NULL, relevance REAL NOT NULL, PRIMARY KEY(sessionId, id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_research_sources_sessionId ON research_sources(sessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_research_sources_domain ON research_sources(domain)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_research_sources_retrievedAt ON research_sources(retrievedAt)")
            db.execSQL("CREATE TABLE IF NOT EXISTS research_findings (sessionId TEXT NOT NULL, id TEXT NOT NULL, text TEXT NOT NULL, sourceIdsJson TEXT NOT NULL, relevance REAL NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(sessionId, id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_research_findings_sessionId ON research_findings(sessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_research_findings_createdAt ON research_findings(createdAt)")

            db.execSQL("CREATE TABLE IF NOT EXISTS browser_sessions (sessionId TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, conversationId TEXT NOT NULL, activeTabId TEXT NOT NULL, currentUrl TEXT, lastUsedAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_browser_sessions_workspaceId ON browser_sessions(workspaceId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_browser_sessions_conversationId ON browser_sessions(conversationId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_browser_sessions_lastUsedAt ON browser_sessions(lastUsedAt)")
            db.execSQL("CREATE TABLE IF NOT EXISTS browser_tabs (sessionId TEXT NOT NULL, tabId TEXT NOT NULL, title TEXT NOT NULL, currentUrl TEXT, lastUsedAt INTEGER NOT NULL, PRIMARY KEY(sessionId, tabId))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_browser_tabs_sessionId ON browser_tabs(sessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_browser_tabs_lastUsedAt ON browser_tabs(lastUsedAt)")

            db.execSQL("CREATE TABLE IF NOT EXISTS subagent_delegation_events (id TEXT NOT NULL PRIMARY KEY, subagentId TEXT NOT NULL, taskId TEXT NOT NULL, rootTaskId TEXT, parentSubagentId TEXT, role TEXT NOT NULL, status TEXT NOT NULL, label TEXT NOT NULL, startedAt INTEGER, finishedAt INTEGER, failureSummary TEXT, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_subagent_delegation_events_subagentId ON subagent_delegation_events(subagentId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_subagent_delegation_events_taskId ON subagent_delegation_events(taskId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_subagent_delegation_events_rootTaskId ON subagent_delegation_events(rootTaskId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_subagent_delegation_events_createdAt ON subagent_delegation_events(createdAt)")
        }
    }
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
