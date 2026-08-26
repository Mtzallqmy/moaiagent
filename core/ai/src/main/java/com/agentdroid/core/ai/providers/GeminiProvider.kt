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
import java.net.URLEncoder

private val geminiJson = Json { ignoreUnknownKeys = true; isLenient = true }

class GeminiProvider(private val transport: HttpTransport = HttpTransport()) : AiProvider {
    override val kind = ProviderKind.GEMINI
    override val displayName = "Google Gemini"
    override val capabilities = ProviderCapabilities(modelListing = true, streaming = true, vision = true, reasoning = true)
    private fun base(config: ProviderConfig) = (config.baseUrl?.takeIf { it.isNotBlank() } ?: "https://generativelanguage.googleapis.com/v1beta").trimEnd('/')
    private fun url(config: ProviderConfig, path: String, secret: String) = "${base(config)}$path?key=${URLEncoder.encode(secret, "UTF-8")}" 

    override suspend fun testConnection(config: ProviderConfig, secret: String): ProviderTestResult {
        val start = System.currentTimeMillis()
        return try {
            transport.execute(transport.request(url(config, "/models", secret))).use { response ->
                response.requireSuccess()
                val count = parseModels(response.body?.string().orEmpty()).size
                ProviderTestResult(true, displayName, count, System.currentTimeMillis() - start, true)
            }
        } catch (error: ProviderHttpException) { ProviderTestResult(false, displayName, error = error.error)
        } catch (error: Throwable) { ProviderTestResult(false, displayName, error = error.toAppError()) }
    }

    override suspend fun listModels(config: ProviderConfig, secret: String): Result<List<AiModel>> = runCatching {
        transport.execute(transport.request(url(config, "/models", secret))).use { response -> response.requireSuccess(); parseModels(response.body?.string().orEmpty()) }
    }

    private fun parseModels(body: String): List<AiModel> = runCatching {
        geminiJson.parseToJsonElement(body).jsonObject["models"]?.jsonArray.orEmpty().mapNotNull { item ->
            val objectValue = item.jsonObject
            objectValue["name"]?.jsonPrimitive?.contentOrNull?.let { name ->
                val id = name.removePrefix("models/")
                AiModel(id, objectValue["displayName"]?.jsonPrimitive?.contentOrNull ?: id, capabilities)
            }
        }
    }.getOrDefault(emptyList())

    override fun streamChat(request: ChatRequest, config: ProviderConfig, secret: String): Flow<AiStreamEvent> = flow {
        emit(AiStreamEvent.Started)
        val modelPath = "/models/${request.model.removePrefix("models/")}:streamGenerateContent"
        val payload = buildJsonObject {
            putJsonArray("contents") { request.messages.filter { it.role != MessageRole.SYSTEM }.forEach { message -> addJsonObject { put("role", if (message.role == MessageRole.ASSISTANT) "model" else "user"); putJsonArray("parts") { addJsonObject { put("text", message.content) } } } } }
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", it) } } } }
            putJsonObject("generationConfig") { request.temperature?.let { put("temperature", it) }; request.maxTokens?.let { put("maxOutputTokens", it) } }
        }.toString()
        try {
            transport.execute(transport.request(url(config, modelPath, secret), "POST", body = payload)).use { response ->
                response.requireSuccess()
                val reader = response.body?.charStream()?.buffered() ?: return@use
                while (true) {
                    val line = reader.readLine() ?: break
                    val candidate = line.removePrefix("data:").trim()
                    if (candidate.isBlank()) continue
                    val root = runCatching { geminiJson.parseToJsonElement(candidate).jsonObject }.getOrNull() ?: continue
                    root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.forEach { part -> part.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.let { emit(AiStreamEvent.TextDelta(it)) } }
                    root["usageMetadata"]?.jsonObject?.let { usage -> emit(AiStreamEvent.UsageEvent(Usage(usage["promptTokenCount"]?.jsonPrimitive?.intOrNull, usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull, usage["totalTokenCount"]?.jsonPrimitive?.intOrNull))) }
                }
                emit(AiStreamEvent.Completed)
            }
        } catch (error: ProviderHttpException) { emit(AiStreamEvent.Error(error.error))
        } catch (error: Throwable) { emit(AiStreamEvent.Error(error.toAppError())) }
    }
}
