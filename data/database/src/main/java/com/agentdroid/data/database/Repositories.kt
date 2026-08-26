package com.agentdroid.data.database

import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun observeAll(): Flow<List<ConversationEntity>>
    fun observeIncludingArchived(): Flow<List<ConversationEntity>>
    fun observeSearch(query: String): Flow<List<ConversationEntity>>
    suspend fun get(id: String): ConversationEntity?
    suspend fun save(conversation: ConversationEntity)
    suspend fun rename(id: String, title: String)
    suspend fun archive(id: String, archived: Boolean)
    suspend fun delete(id: String)
}

class RoomConversationRepository(private val dao: ConversationDao, private val messages: MessageDao) : ConversationRepository {
    override fun observeAll() = dao.observeAll()
    override fun observeIncludingArchived() = dao.observeIncludingArchived()
    override fun observeSearch(query: String) = dao.search(query)
    override suspend fun get(id: String) = dao.get(id)
    override suspend fun save(conversation: ConversationEntity) = dao.upsert(conversation)
    override suspend fun rename(id: String, title: String) = dao.rename(id, title)
    override suspend fun archive(id: String, archived: Boolean) = dao.setArchived(id, archived)
    override suspend fun delete(id: String) { messages.deleteForConversation(id); dao.deleteById(id) }
}

interface MessageRepository { fun observe(conversationId: String): Flow<List<MessageEntity>>; suspend fun save(message: MessageEntity); suspend fun deleteAfter(conversationId: String, createdAt: Long) }
class RoomMessageRepository(private val dao: MessageDao) : MessageRepository { override fun observe(conversationId: String) = dao.observe(conversationId); override suspend fun save(message: MessageEntity) = dao.upsert(message); override suspend fun deleteAfter(conversationId: String, createdAt: Long) = dao.deleteAfter(conversationId, createdAt) }

interface ProviderRepository {
    fun observeAll(): Flow<List<ProviderConfigEntity>>
    suspend fun get(id: String): ProviderConfigEntity?
    suspend fun save(config: ProviderConfigEntity)
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun setModel(id: String, model: String)
}
class RoomProviderRepository(private val dao: ProviderDao) : ProviderRepository {
    override fun observeAll() = dao.observeAll()
    override suspend fun get(id: String) = dao.get(id)
    override suspend fun save(config: ProviderConfigEntity) = dao.upsert(config)
    override suspend fun delete(id: String) = dao.deleteById(id)
    override suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)
    override suspend fun setModel(id: String, model: String) = dao.setModel(id, model)
}

interface WorkspaceRepository {
    fun observeAll(): Flow<List<WorkspaceEntity>>
    suspend fun get(id: String): WorkspaceEntity?
    suspend fun save(item: WorkspaceEntity)
    suspend fun setLastOpenedFile(id: String, path: String?)
    suspend fun delete(id: String)
}
class RoomWorkspaceRepository(private val dao: WorkspaceDao) : WorkspaceRepository {
    override fun observeAll() = dao.observeAll()
    override suspend fun get(id: String) = dao.get(id)
    override suspend fun save(item: WorkspaceEntity) = dao.upsert(item)
    override suspend fun setLastOpenedFile(id: String, path: String?) = dao.setLastOpenedFile(id, path)
    override suspend fun delete(id: String) = dao.deleteById(id)
}

interface MemoryRepository { fun observeAll(): Flow<List<MemoryEntryEntity>>; suspend fun save(item: MemoryEntryEntity); suspend fun delete(id: String); suspend fun setEnabled(id: String, enabled: Boolean) }
class RoomMemoryRepository(private val dao: MemoryDao) : MemoryRepository { override fun observeAll() = dao.observeAll(); override suspend fun save(item: MemoryEntryEntity) = dao.upsert(item); override suspend fun delete(id: String) = dao.deleteById(id); override suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled) }

interface SkillRepository { fun observeAll(): Flow<List<SkillEntity>>; suspend fun save(item: SkillEntity); suspend fun delete(id: String); suspend fun setEnabled(id: String, enabled: Boolean) }
class RoomSkillRepository(private val dao: SkillDao) : SkillRepository { override fun observeAll() = dao.observeAll(); override suspend fun save(item: SkillEntity) = dao.upsert(item); override suspend fun delete(id: String) = dao.deleteById(id); override suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled) }
