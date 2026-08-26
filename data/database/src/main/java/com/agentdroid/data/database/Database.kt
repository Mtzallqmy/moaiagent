package com.agentdroid.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="conversations") data class ConversationEntity(@PrimaryKey val id:String,val title:String,val createdAt:Long,val updatedAt:Long,val providerId:String?=null,val modelId:String?=null,val workspaceId:String?=null,val archived:Boolean=false)
@Entity(tableName="messages", indices=[Index("conversationId")]) data class MessageEntity(@PrimaryKey val id:String,val conversationId:String,val role:String,val content:String,val status:String,val createdAt:Long,val updatedAt:Long,val providerId:String?=null,val modelId:String?=null,val usageJson:String?=null,val errorJson:String?=null)
@Entity(tableName="provider_configs") data class ProviderConfigEntity(@PrimaryKey val id:String,val name:String,val kind:String,val baseUrl:String?,val modelId:String?,val secretAlias:String?,val organizationId:String?,val appName:String?,val siteUrl:String?,val customHeadersJson:String,val enabled:Boolean)
@Entity(tableName="workspaces") data class WorkspaceEntity(@PrimaryKey val id:String,val name:String,val description:String,val createdAt:Long,val updatedAt:Long)
@Entity(tableName="memory_entries") data class MemoryEntryEntity(@PrimaryKey val id:String,val scope:String,val workspaceId:String?,val title:String,val content:String,val enabled:Boolean,val createdAt:Long,val updatedAt:Long)
@Entity(tableName="skills") data class SkillEntity(@PrimaryKey val id:String,val name:String,val description:String,val instructions:String,val enabled:Boolean,val scope:String,val workspaceId:String?,val createdAt:Long,val updatedAt:Long)
@Entity(tableName="app_settings") data class AppSettingEntity(@PrimaryKey val key:String,val value:String)

@Dao interface ConversationDao { @Query("SELECT * FROM conversations WHERE archived=0 ORDER BY updatedAt DESC") fun observeAll():Flow<List<ConversationEntity>>; @Query("SELECT * FROM conversations WHERE id=:id") suspend fun get(id:String):ConversationEntity?; @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(e:ConversationEntity); @Delete suspend fun delete(e:ConversationEntity); @Query("UPDATE conversations SET title=:title,updatedAt=:now WHERE id=:id") suspend fun rename(id:String,title:String,now:Long=System.currentTimeMillis()); }
@Dao interface MessageDao { @Query("SELECT * FROM messages WHERE conversationId=:id ORDER BY createdAt ASC") fun observe(id:String):Flow<List<MessageEntity>>; @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(e:MessageEntity); @Query("DELETE FROM messages WHERE conversationId=:id") suspend fun deleteForConversation(id:String); }
@Dao interface ProviderDao { @Query("SELECT * FROM provider_configs ORDER BY name") fun observeAll():Flow<List<ProviderConfigEntity>>; @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(e:ProviderConfigEntity); @Delete suspend fun delete(e:ProviderConfigEntity); }
@Dao interface WorkspaceDao { @Query("SELECT * FROM workspaces ORDER BY updatedAt DESC") fun observeAll():Flow<List<WorkspaceEntity>>; @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(e:WorkspaceEntity); @Delete suspend fun delete(e:WorkspaceEntity); }
@Dao interface MemoryDao { @Query("SELECT * FROM memory_entries ORDER BY updatedAt DESC") fun observeAll():Flow<List<MemoryEntryEntity>>; @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(e:MemoryEntryEntity); @Delete suspend fun delete(e:MemoryEntryEntity); }
@Dao interface SkillDao { @Query("SELECT * FROM skills ORDER BY updatedAt DESC") fun observeAll():Flow<List<SkillEntity>>; @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(e:SkillEntity); @Delete suspend fun delete(e:SkillEntity); }
@Dao interface SettingDao { @Query("SELECT * FROM app_settings") fun observeAll():Flow<List<AppSettingEntity>>; @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(e:AppSettingEntity); }

@Database(entities=[ConversationEntity::class,MessageEntity::class,ProviderConfigEntity::class,WorkspaceEntity::class,MemoryEntryEntity::class,SkillEntity::class,AppSettingEntity::class],version=1,exportSchema=true)
abstract class AgentDatabase:RoomDatabase(){ abstract fun conversations():ConversationDao; abstract fun messages():MessageDao; abstract fun providers():ProviderDao; abstract fun workspaces():WorkspaceDao; abstract fun memory():MemoryDao; abstract fun skills():SkillDao; abstract fun settings():SettingDao }
