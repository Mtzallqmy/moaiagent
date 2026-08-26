package com.agentdroid.core.ai.providers

import com.agentdroid.core.ai.AiProvider
import com.agentdroid.core.ai.ProviderTestResult
import com.agentdroid.core.ai.transport.HttpTransport
import com.agentdroid.core.ai.transport.ProviderHttpException
import com.agentdroid.core.ai.transport.requireSuccess
import com.agentdroid.core.ai.transport.toAppError
import com.agentdroid.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

private val anthropicJson = Json { ignoreUnknownKeys = true; isLenient = true }

class AnthropicProvider(private val transport: HttpTransport = HttpTransport()) : AiProvider {
    override val kind = ProviderKind.ANTHROPIC
    override val displayName = "Anthropic"
    override val capabilities = ProviderCapabilities(modelListing = true, streaming = true, vision = true, reasoning = true)
    private fun base(config: ProviderConfig) = (config.baseUrl?.takeIf { it.isNotBlank() } ?: "https://api.anthropic.com/v1").trimEnd('/')
    private fun headers(secret: String) = mapOf("x-api-key" to secret, "anthropic-version" to "2023-06-01", "content-type" to "application/json")

    override suspend fun testConnection(config: ProviderConfig, secret: String): ProviderTestResult {
        val start = System.currentTimeMillis()
        return try {
            transport.execute(transport.request(base(config) + "/models", headers = headers(secret))).use { response ->
                response.requireSuccess()
                val count = parseModels(response.body?.string().orEmpty()).size
                ProviderTestResult(true, displayName, count, System.currentTimeMillis() - start, true)
            }
        } catch (error: ProviderHttpException) { ProviderTestResult(false, displayName, error = error.error)
        } catch (error: Throwable) { ProviderTestResult(false, displayName, error = error.toAppError()) }
    }

    override suspend fun listModels(config: ProviderConfig, secret: String): Result<List<AiModel>> = runCatching {
        transport.execute(transport.request(base(config) + "/models", headers = headers(secret))).use { response -> response.requireSuccess(); parseModels(response.body?.string().orEmpty()) }
    }

    private fun parseModels(body: String): List<AiModel> = runCatching {
        anthropicJson.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { item ->
            val objectValue = item.jsonObject
            objectValue["id"]?.jsonPrimitive?.contentOrNull?.let { id -> AiModel(id, objectValue["display_name"]?.jsonPrimitive?.contentOrNull ?: id, capabilities) }
        }
    }.getOrDefault(emptyList())

    override fun streamChat(request: ChatRequest, config: ProviderConfig, secret: String): Flow<AiStreamEvent> = flow {
        emit(AiStreamEvent.Started)
        val payload = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens ?: 1024)
            put("stream", true)
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { put("system", it) }
            putJsonArray("messages") { request.messages.filter { it.role != MessageRole.SYSTEM }.forEach { addJsonObject { put("role", if (it.role == MessageRole.USER) "user" else "assistant"); put("content", it.content) } } }
        }.toString()
        try {
            transport.execute(transport.request(base(config) + "/messages", "POST", headers(secret), payload)).use { response ->
                response.requireSuccess()
                val reader = response.body?.charStream()?.buffered() ?: return@use
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val root = runCatching { anthropicJson.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }.getOrNull() ?: continue
                    when (root["type"]?.jsonPrimitive?.contentOrNull) {
                        "message_start" -> {
                            root["message"]?.jsonObject?.get("usage")?.jsonObject?.let { usage -> emit(AiStreamEvent.UsageEvent(Usage(usage["input_tokens"]?.jsonPrimitive?.intOrNull, usage["output_tokens"]?.jsonPrimitive?.intOrNull, null))) }
                        }
                        "content_block_delta" -> {
                            val delta = root["delta"]?.jsonObject ?: continue
                            when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let { emit(AiStreamEvent.TextDelta(it)) }
                                "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { emit(AiStreamEvent.ReasoningDelta(it)) }
                            }
                        }
                        "message_delta" -> root["usage"]?.jsonObject?.let { usage -> emit(AiStreamEvent.UsageEvent(Usage(null, usage["output_tokens"]?.jsonPrimitive?.intOrNull, null))) }
                        "message_stop" -> emit(AiStreamEvent.Completed)
                        "error" -> emit(AiStreamEvent.Error(AppError.Provider(root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "Anthropic stream error")))
                    }
                }
            }
        } catch (error: ProviderHttpException) { emit(AiStreamEvent.Error(error.error))
        } catch (error: Throwable) { emit(AiStreamEvent.Error(error.toAppError())) }
    }
}
