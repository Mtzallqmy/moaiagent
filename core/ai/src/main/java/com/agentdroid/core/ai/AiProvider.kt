package com.agentdroid.core.ai

import com.agentdroid.core.model.AiModel
import com.agentdroid.core.model.AiStreamEvent
import com.agentdroid.core.model.AppError
import com.agentdroid.core.model.ChatRequest
import com.agentdroid.core.model.ProviderCapabilities
import com.agentdroid.core.model.ProviderConfig
import com.agentdroid.core.model.ProviderKind
import kotlinx.coroutines.flow.Flow

interface AiProvider {
    val kind: ProviderKind
    val displayName: String
    val capabilities: ProviderCapabilities
    suspend fun testConnection(config: ProviderConfig, secret: String): ProviderTestResult
    suspend fun listModels(config: ProviderConfig, secret: String): Result<List<AiModel>>
    fun streamChat(request: ChatRequest, config: ProviderConfig, secret: String): Flow<AiStreamEvent>
}

data class ProviderTestResult(val success: Boolean, val provider: String, val modelCount: Int? = null, val latencyMs: Long? = null, val streamingSupported: Boolean = false, val error: AppError? = null)

class ProviderRegistry(providers: List<AiProvider>) {
    private val byKind = providers.associate { provider ->
        provider.kind to if (provider is UsageRecordingProvider) provider else UsageRecordingProvider(provider)
    }
    fun get(kind: ProviderKind): AiProvider? = byKind[kind]
    fun all(): List<AiProvider> = byKind.values.toList()
}

object ErrorMapper {
    fun http(code: Int, body: String): AppError = when (code) {
        401 -> AppError.Authentication("HTTP 401: ${body.take(500)}")
        403 -> AppError.Authentication("HTTP 403: ${body.take(500)}", "Provider permission denied")
        429 -> AppError.RateLimit("HTTP 429: ${body.take(500)}")
        in 500..599 -> AppError.Provider("HTTP $code: ${body.take(500)}", "Provider is temporarily unavailable")
        else -> AppError.Provider("HTTP $code: ${body.take(500)}")
    }
}
