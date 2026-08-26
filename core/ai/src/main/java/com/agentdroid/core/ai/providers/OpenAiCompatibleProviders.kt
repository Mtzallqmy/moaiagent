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
import okhttp3.Response

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

open class OpenAiCompatibleProvider(
    override val kind: ProviderKind,
    override val displayName: String,
    private val defaultBaseUrl: String,
    private val transport: HttpTransport = HttpTransport()
) : AiProvider {
    override val capabilities = ProviderCapabilities(modelListing = true, toolCalling = true, vision = true, reasoning = true)

    protected fun base(config: ProviderConfig) = (config.baseUrl?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
    protected open fun headers(config: ProviderConfig, secret: String): Map<String, String> = buildMap {
        if (secret.isNotBlank()) put("Authorization", "Bearer $secret")
        config.organizationId?.let { put("OpenAI-Organization", it) }
        putAll(config.customHeaders)
    }
    protected fun request(config: ProviderConfig, secret: String, path: String, method: String = "GET", body: String? = null) = transport.request(base(config) + path, method, headers(config, secret), body)

    override suspend fun testConnection(config: ProviderConfig, secret: String): ProviderTestResult {
        val start = System.currentTimeMillis()
        return try {
            transport.execute(request(config, secret, "/models")).use { response ->
                response.requireSuccess()
                val count = parseModels(response).size
                ProviderTestResult(true, displayName, count, System.currentTimeMillis() - start, capabilities.streaming)
            }
        } catch (error: ProviderHttpException) { ProviderTestResult(false, displayName, error = error.error)
        } catch (error: Throwable) { ProviderTestResult(false, displayName, error = error.toAppError()) }
    }

    override suspend fun listModels(config: ProviderConfig, secret: String): Result<List<AiModel>> = runCatching {
        transport.execute(request(config, secret, "/models")).use { response -> response.requireSuccess(); parseModels(response) }
    }

    private fun parseModels(response: Response): List<AiModel> {
        val body = response.body?.string().orEmpty()
        val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonArray }.getOrNull() ?: return emptyList()
        return data.mapNotNull { element -> element.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.let { AiModel(it, element.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: it, capabilities) } }
    }

    override fun streamChat(request: ChatRequest, config: ProviderConfig, secret: String): Flow<AiStreamEvent> = flow {
        emit(AiStreamEvent.Started)
        val payload = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            putJsonArray("messages") {
                request.systemPrompt?.takeIf { it.isNotBlank() }?.let { addJsonObject { put("role", "system"); put("content", it) } }
                request.messages.forEach { message -> addJsonObject { put("role", message.role.name.lowercase()); put("content", message.content) } }
            }
        }.toString()
        try {
            transport.execute(request(config, secret, "/chat/completions", "POST", payload)).use { response ->
                response.requireSuccess()
                val reader = response.body?.charStream()?.buffered() ?: return@use
                while (true) {
                    val line = reader.readLine() ?: break
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") continue
                    val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                    val delta = choice["delta"]?.jsonObject
                    delta?.get("content")?.jsonPrimitive?.contentOrNull?.let { emit(AiStreamEvent.TextDelta(it)) }
                    delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull?.let { emit(AiStreamEvent.ReasoningDelta(it)) }
                    root["usage"]?.jsonObject?.let { emit(AiStreamEvent.UsageEvent(Usage(it["prompt_tokens"]?.jsonPrimitive?.intOrNull, it["completion_tokens"]?.jsonPrimitive?.intOrNull, it["total_tokens"]?.jsonPrimitive?.intOrNull))) }
                }
                emit(AiStreamEvent.Completed)
            }
        } catch (error: ProviderHttpException) { emit(AiStreamEvent.Error(error.error))
        } catch (error: Throwable) { emit(AiStreamEvent.Error(error.toAppError())) }
    }
}

class OpenAiProvider(transport: HttpTransport = HttpTransport()) : OpenAiCompatibleProvider(ProviderKind.OPENAI, "OpenAI", "https://api.openai.com/v1", transport)
class OpenRouterProvider(transport: HttpTransport = HttpTransport()) : OpenAiCompatibleProvider(ProviderKind.OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1", transport) {
    override fun headers(config: ProviderConfig, secret: String): Map<String, String> = super.headers(config, secret) + buildMap { config.siteUrl?.let { put("HTTP-Referer", it) }; config.appName?.let { put("X-Title", it) } }
}
class CompatibleProvider : OpenAiCompatibleProvider(ProviderKind.COMPATIBLE, "OpenAI-Compatible", "http://localhost:1234/v1")
