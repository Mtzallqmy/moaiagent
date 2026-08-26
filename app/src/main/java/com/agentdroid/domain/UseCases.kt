package com.agentdroid.domain

import com.agentdroid.core.ai.ProviderRegistry
import com.agentdroid.core.model.*
import com.agentdroid.data.database.*
import com.agentdroid.security.SecureSecretStore
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class TestProviderUseCase(private val registry: ProviderRegistry, private val secrets: SecureSecretStore) {
    suspend operator fun invoke(config: ProviderConfigEntity): com.agentdroid.core.ai.ProviderTestResult {
        val provider = registry.get(ProviderKind.valueOf(config.kind)) ?: return com.agentdroid.core.ai.ProviderTestResult(false, config.name, error = AppError.Provider("Provider not registered", "Provider is unavailable", false))
        return provider.testConnection(config.toModel(), config.secretAlias?.let(secrets::get).orEmpty())
    }
}

class LoadModelsUseCase(private val registry: ProviderRegistry, private val secrets: SecureSecretStore) {
    suspend operator fun invoke(config: ProviderConfigEntity): Result<List<AiModel>> {
        val provider = registry.get(ProviderKind.valueOf(config.kind)) ?: return Result.failure(IllegalStateException("Provider not registered"))
        return provider.listModels(config.toModel(), config.secretAlias?.let(secrets::get).orEmpty())
    }
}

class SendMessageUseCase(private val registry: ProviderRegistry, private val messages: MessageRepository, private val secrets: SecureSecretStore) {
    operator fun invoke(request: ChatRequest, config: ProviderConfigEntity): Flow<AiStreamEvent> {
        val provider = registry.get(ProviderKind.valueOf(config.kind)) ?: error("Provider not registered")
        return provider.streamChat(request, config.toModel(), config.secretAlias?.let(secrets::get).orEmpty())
    }
}

class SaveProviderUseCase(private val providers: ProviderRepository, private val secrets: SecureSecretStore) {
    suspend operator fun invoke(config: ProviderConfig, secret: String) { config.secretAlias?.let { if (secret.isNotBlank()) secrets.put(it, secret) }; providers.save(config.toEntity()) }
}
class DeleteProviderUseCase(private val providers: ProviderRepository, private val secrets: SecureSecretStore) { suspend operator fun invoke(config: ProviderConfigEntity) { config.secretAlias?.let(secrets::delete); providers.delete(config.id) } }
class SaveWorkspaceUseCase(private val repository: WorkspaceRepository) { suspend operator fun invoke(name: String, description: String) = repository.save(WorkspaceEntity(UUID.randomUUID().toString(), name, description, System.currentTimeMillis(), System.currentTimeMillis())) }
class SaveMemoryUseCase(private val repository: MemoryRepository) { suspend operator fun invoke(title: String, content: String, workspaceId: String? = null) = repository.save(MemoryEntryEntity(UUID.randomUUID().toString(), MemoryScope.GLOBAL.name, workspaceId, title, content, true, System.currentTimeMillis(), System.currentTimeMillis())) }
class SaveSkillUseCase(private val repository: SkillRepository) { suspend operator fun invoke(name: String, description: String, instructions: String, workspaceId: String? = null) = repository.save(SkillEntity(UUID.randomUUID().toString(), name, description, instructions, true, "GLOBAL", workspaceId, System.currentTimeMillis(), System.currentTimeMillis())) }

private fun ProviderConfigEntity.toModel() = ProviderConfig(id, name, ProviderKind.valueOf(kind), baseUrl, modelId, secretAlias, organizationId, appName, siteUrl, emptyMap(), enabled)
private fun ProviderConfig.toEntity() = ProviderConfigEntity(id, name, kind.name, baseUrl, modelId, secretAlias, organizationId, appName, siteUrl, "{}", enabled)
