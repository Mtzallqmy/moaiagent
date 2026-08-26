package com.agentdroid.core.ai

import com.agentdroid.core.model.*
import kotlinx.coroutines.flow.Flow

interface AiProvider { val id: String; val displayName: String; val capabilities: ProviderCapabilities; suspend fun testConnection(config: ProviderConfig, secret: String): ProviderTestResult; suspend fun listModels(config: ProviderConfig, secret: String): Result<List<AiModel>>; fun streamChat(request: ChatRequest, config: ProviderConfig, secret: String): Flow<AiStreamEvent> }
data class ProviderTestResult(val success: Boolean, val provider: String, val modelCount: Int? = null, val latencyMs: Long? = null, val streamingSupported: Boolean = false, val error: AppError? = null)
class ProviderRegistry(private val providers: List<AiProvider>) { fun get(kind: ProviderKind): AiProvider? = providers.firstOrNull { it.id == kind.name.lowercase() }; fun all(): List<AiProvider> = providers }
