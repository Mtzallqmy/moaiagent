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
data class PermissionRuleEntity(
    @PrimaryKey val id: String,
    val toolName: String,
    val workspaceId: String?,
    val decision: String,
    val scope: String,
    val createdAt: Long
)

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
    val conversationId: String
)

@Entity(tableName = "change_sets", indices = [Index("workspaceId"), Index("createdAt")])
data class ChangeSetEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val filesJson: String,
    val createdAt: Long,
    val status: String,
    val originatingToolCallId: String?,
    val appliedAt: Long?,
    val revertedAt: Long?
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

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ProviderConfigEntity::class,
        WorkspaceEntity::class,
        MemoryEntryEntity::class,
        SkillEntity::class,
        AppSettingEntity::class,
        PermissionRuleEntity::class,
        AuditLogEntity::class,
        ChangeSetEntity::class
    ],
    version = 2,
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
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
