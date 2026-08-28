package com.agentdroid.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class ProviderKind { OPENAI, ANTHROPIC, GEMINI, OPENROUTER, COMPATIBLE, LOCAL, FAKE }
enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }
enum class MessageStatus { PENDING, STREAMING, COMPLETED, FAILED, CANCELLED }
enum class MemoryScope { GLOBAL, WORKSPACE }
enum class ChatPhase { IDLE, SUBMITTING, STREAMING, COMPLETED, FAILED, CANCELLED }

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String? = null,
    val modelId: String? = null,
    val secretAlias: String? = null,
    val organizationId: String? = null,
    val appName: String? = null,
    val siteUrl: String? = null,
    val customHeaders: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
)

@Serializable
data class AiModel(
    val id: String,
    val displayName: String = id,
    val capabilities: ProviderCapabilities = ProviderCapabilities()
)

@Serializable
data class ProviderCapabilities(
    val chat: Boolean = true,
    val streaming: Boolean = true,
    val modelListing: Boolean = false,
    val toolCalling: Boolean = false,
    val vision: Boolean = false,
    val reasoning: Boolean = false,
    val systemPrompt: Boolean = true
)

@Serializable
data class ModelToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class ModelToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val rawArguments: String = arguments.toString()
)

@Serializable
data class ChatMessage(
    val role: MessageRole,
    val content: String = "",
    val toolCalls: List<ModelToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null
)

@Serializable
data class ChatRequest(
    val messages: List<ChatMessage>,
    val model: String,
    val systemPrompt: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val tools: List<ModelToolDefinition> = emptyList()
)

@Serializable
data class Usage(val promptTokens: Int? = null, val completionTokens: Int? = null, val totalTokens: Int? = null)

sealed interface AiStreamEvent {
    data object Started : AiStreamEvent
    data class TextDelta(val text: String) : AiStreamEvent
    data class ReasoningDelta(val text: String) : AiStreamEvent
    data class ToolCallStarted(val id: String, val name: String, val index: Int = 0) : AiStreamEvent
    data class ToolCallDelta(val id: String, val argumentsDelta: String, val index: Int = 0) : AiStreamEvent
    data class ToolCallCompleted(val call: ModelToolCall, val index: Int = 0) : AiStreamEvent
    data class UsageEvent(val usage: Usage) : AiStreamEvent
    data object Completed : AiStreamEvent
    data class Error(val error: AppError) : AiStreamEvent
}

sealed class AppError(open val technicalMessage: String, open val userMessage: String, open val recoverable: Boolean) {
    data class Authentication(override val technicalMessage: String, override val userMessage: String = "Invalid API key", override val recoverable: Boolean = false) : AppError(technicalMessage, userMessage, recoverable)
    data class RateLimit(override val technicalMessage: String, override val userMessage: String = "Rate limit exceeded", override val recoverable: Boolean = true) : AppError(technicalMessage, userMessage, recoverable)
    data class Timeout(override val technicalMessage: String, override val userMessage: String = "Network timeout", override val recoverable: Boolean = true) : AppError(technicalMessage, userMessage, recoverable)
    data class Network(override val technicalMessage: String, override val userMessage: String = "Network error", override val recoverable: Boolean = true) : AppError(technicalMessage, userMessage, recoverable)
    data class Ssl(override val technicalMessage: String, override val userMessage: String = "Secure connection failed", override val recoverable: Boolean = false) : AppError(technicalMessage, userMessage, recoverable)
    data class Provider(override val technicalMessage: String, override val userMessage: String = "Provider error", override val recoverable: Boolean = true) : AppError(technicalMessage, userMessage, recoverable)
    data class Serialization(override val technicalMessage: String, override val userMessage: String = "Invalid provider response", override val recoverable: Boolean = false) : AppError(technicalMessage, userMessage, recoverable)
    data class Database(override val technicalMessage: String, override val userMessage: String = "Storage error", override val recoverable: Boolean = true) : AppError(technicalMessage, userMessage, recoverable)
    data class Security(override val technicalMessage: String, override val userMessage: String = "Security error", override val recoverable: Boolean = false) : AppError(technicalMessage, userMessage, recoverable)
    data class Unknown(override val technicalMessage: String, override val userMessage: String = "Something went wrong", override val recoverable: Boolean = true) : AppError(technicalMessage, userMessage, recoverable)
}
