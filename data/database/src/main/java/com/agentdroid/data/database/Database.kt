package com.agentdroid.data.database

import androidx.room.*
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(@PrimaryKey val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val providerId: String? = null, val modelId: String? = null, val workspaceId: String? = null, val archived: Boolean = false)
@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val role: String, val content: String, val status: String, val createdAt: Long, val updatedAt: Long, val providerId: String? = null, val modelId: String? = null, val usageJson: String? = null, val errorJson: String? = null)
@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(@PrimaryKey val id: String, val name: String, val kind: String, val baseUrl: String?, val modelId: String?, val secretAlias: String?, val organizationId: String?, val appName: String?, val siteUrl: String?, val customHeadersJson: String, val enabled: Boolean)
@Entity(tableName = "workspaces")
data class WorkspaceEntity(@PrimaryKey val id: String, val name: String, val description: String, val createdAt: Long, val updatedAt: Long)
@Entity(tableName = "memory_entries")
data class MemoryEntryEntity(@PrimaryKey val id: String, val scope: String, val workspaceId: String?, val title: String, val content: String, val enabled: Boolean, val createdAt: Long, val updatedAt: Long)
@Entity(tableName = "skills")
data class SkillEntity(@PrimaryKey val id: String, val name: String, val description: String, val instructions: String, val enabled: Boolean, val scope: String, val workspaceId: String?, val createdAt: Long, val updatedAt: Long)
@Entity(tableName = "app_settings")
data class AppSettingEntity(@PrimaryKey val key: String, val value: String)

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
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: ProviderConfigEntity)
    @Query("DELETE FROM provider_configs WHERE id = :id") suspend fun deleteById(id: String)
    @Query("UPDATE provider_configs SET enabled = :enabled WHERE id = :id") suspend fun setEnabled(id: String, enabled: Boolean)
    @Query("UPDATE provider_configs SET modelId = :model WHERE id = :id") suspend fun setModel(id: String, model: String)
}
@Dao interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY updatedAt DESC") fun observeAll(): Flow<List<WorkspaceEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: WorkspaceEntity)
    @Query("DELETE FROM workspaces WHERE id = :id") suspend fun deleteById(id: String)
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

@Database(entities = [ConversationEntity::class, MessageEntity::class, ProviderConfigEntity::class, WorkspaceEntity::class, MemoryEntryEntity::class, SkillEntity::class, AppSettingEntity::class], version = 1, exportSchema = true)
abstract class AgentDatabase : RoomDatabase() { abstract fun conversations(): ConversationDao; abstract fun messages(): MessageDao; abstract fun providers(): ProviderDao; abstract fun workspaces(): WorkspaceDao; abstract fun memory(): MemoryDao; abstract fun skills(): SkillDao; abstract fun settings(): SettingDao }

object DatabaseMigrations { val ALL: Array<Migration> = emptyArray() }
