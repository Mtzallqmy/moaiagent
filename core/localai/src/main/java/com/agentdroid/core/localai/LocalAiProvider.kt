package com.agentdroid.core.localai

import com.agentdroid.core.ai.AiProvider
import com.agentdroid.core.ai.ProviderTestResult
import com.agentdroid.core.model.AiModel
import com.agentdroid.core.model.AiStreamEvent
import com.agentdroid.core.model.AppError
import com.agentdroid.core.model.ChatMessage
import com.agentdroid.core.model.ChatRequest
import com.agentdroid.core.model.MessageRole
import com.agentdroid.core.model.ProviderCapabilities
import com.agentdroid.core.model.ProviderConfig
import com.agentdroid.core.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Local chat provider. Tool calling is intentionally disabled unless a future backend exposes structured calls. */
class LocalAiProvider(private val manager: LocalModelManager) : AiProvider {
    override val kind = ProviderKind.LOCAL
    override val displayName = "Local"
    override val capabilities = ProviderCapabilities(
        chat = true,
        streaming = true,
        modelListing = true,
        toolCalling = false,
        vision = false,
        reasoning = false,
        systemPrompt = true
    )

    override suspend fun testConnection(config: ProviderConfig, secret: String): ProviderTestResult {
        val models = manager.models.value
        return ProviderTestResult(
            success = models.any { it.compatibility.supported },
            provider = displayName,
            modelCount = models.size,
            streamingSupported = true,
            error = if (models.any { it.compatibility.supported }) null else AppError.Provider(
                "No compatible local model is imported",
                "Import a compatible local model first",
                recoverable = true
            )
        )
    }

    override suspend fun listModels(config: ProviderConfig, secret: String): Result<List<AiModel>> = Result.success(
        manager.models.value.map { model ->
            AiModel(model.id, model.displayName, capabilities.copy(toolCalling = model.metadata.supportsToolCalling))
        }
    )

    override fun streamChat(request: ChatRequest, config: ProviderConfig, secret: String): Flow<AiStreamEvent> = flow {
        val modelId = request.model.ifBlank { manager.defaultModel()?.id.orEmpty() }
        if (modelId.isBlank()) {
            emit(AiStreamEvent.Error(AppError.Provider("No local model selected", "Select a local model first")))
            return@flow
        }
        val descriptor = manager.get(modelId)
        if (descriptor == null) {
            emit(AiStreamEvent.Error(AppError.Provider("Unknown local model $modelId", "The selected local model is unavailable")))
            return@flow
        }
        if (request.tools.isNotEmpty() && !descriptor.metadata.supportsToolCalling) {
            emit(AiStreamEvent.Error(AppError.Provider(
                "Selected local model does not expose structured tool calling",
                "This local model supports Chat mode only"
            )))
            return@flow
        }
        val session = manager.load(modelId).getOrElse { failure ->
            emit(AiStreamEvent.Error(AppError.Provider(failure.message ?: "Local model load failed", "Could not load the local model")))
            return@flow
        }
        emit(AiStreamEvent.Started)
        session.generate(
            prompt = buildPrompt(request.systemPrompt, request.messages),
            config = LocalGenerationConfig(request.temperature?.toFloat() ?: 0.7f, request.maxTokens ?: 512)
        ).collect { event ->
            when (event) {
                LocalGenerationEvent.Started -> Unit
                is LocalGenerationEvent.Token -> emit(AiStreamEvent.TextDelta(event.text))
                is LocalGenerationEvent.Error -> emit(AiStreamEvent.Error(AppError.Provider(event.message, "Local inference failed")))
                LocalGenerationEvent.Completed -> emit(AiStreamEvent.Completed)
            }
        }
    }

    private fun buildPrompt(system: String?, messages: List<ChatMessage>): String = buildString {
        system?.takeIf(String::isNotBlank)?.let { append("System:\n").append(it.trim()).append("\n\n") }
        messages.forEach { message ->
            val role = when (message.role) {
                MessageRole.USER -> "User"
                MessageRole.ASSISTANT -> "Assistant"
                MessageRole.SYSTEM -> "System"
                MessageRole.TOOL -> "Tool"
            }
            append(role).append(":\n").append(message.content.trim()).append("\n\n")
        }
        append("Assistant:\n")
    }
}
