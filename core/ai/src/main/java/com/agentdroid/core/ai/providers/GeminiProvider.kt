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
import java.util.UUID

private val geminiJson = Json { ignoreUnknownKeys = true; isLenient = true }

class GeminiProvider(private val transport: HttpTransport = HttpTransport()) : AiProvider {
    override val kind = ProviderKind.GEMINI
    override val displayName = "Google Gemini"
    override val capabilities = ProviderCapabilities(modelListing = true, streaming = true, toolCalling = true, vision = true, reasoning = true)
    private fun base(config: ProviderConfig) = (config.baseUrl?.takeIf { it.isNotBlank() } ?: "https://generativelanguage.googleapis.com/v1beta").trimEnd('/')
    private fun url(config: ProviderConfig, path: String, secret: String): String {
        val separator = if (path.contains('?')) "&" else "?"
        return "${base(config)}$path${separator}key=${URLEncoder.encode(secret, "UTF-8")}"
    }

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
        val modelPath = "/models/${request.model.removePrefix("models/")}:streamGenerateContent?alt=sse"
        val payload = buildJsonObject {
            putJsonArray("contents") { request.messages.filter { it.role != MessageRole.SYSTEM }.forEach { add(geminiMessage(it)) } }
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", it) } } } }
            putJsonObject("generationConfig") { request.temperature?.let { put("temperature", it) }; request.maxTokens?.let { put("maxOutputTokens", it) } }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            request.tools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.inputSchema)
                                }
                            }
                        }
                    }
                }
            }
        }.toString()
        try {
            transport.execute(transport.request(url(config, modelPath, secret), "POST", body = payload)).use { response ->
                response.requireSuccess()
                val reader = response.body?.charStream()?.buffered() ?: return@use
                var toolOrdinal = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val candidate = line.removePrefix("data:").trim()
                    if (candidate.isBlank()) continue
                    val root = runCatching { geminiJson.parseToJsonElement(candidate).jsonObject }.getOrNull() ?: continue
                    root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.forEach { partElement ->
                        val part = partElement.jsonObject
                        part["text"]?.jsonPrimitive?.contentOrNull?.let { emit(AiStreamEvent.TextDelta(it)) }
                        part["functionCall"]?.jsonObject?.let { functionCall ->
                            val name = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                            val args = functionCall["args"]?.jsonObject ?: JsonObject(emptyMap())
                            val id = "gemini-${toolOrdinal++}-${UUID.randomUUID()}"
                            val rawArgs = args.toString()
                            emit(AiStreamEvent.ToolCallStarted(id, name, toolOrdinal - 1))
                            emit(AiStreamEvent.ToolCallDelta(id, rawArgs, toolOrdinal - 1))
                            emit(AiStreamEvent.ToolCallCompleted(ModelToolCall(id, name, args, rawArgs), toolOrdinal - 1))
                        }
                    }
                    root["usageMetadata"]?.jsonObject?.let { usage -> emit(AiStreamEvent.UsageEvent(Usage(usage["promptTokenCount"]?.jsonPrimitive?.intOrNull, usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull, usage["totalTokenCount"]?.jsonPrimitive?.intOrNull))) }
                }
                emit(AiStreamEvent.Completed)
            }
        } catch (error: ProviderHttpException) { emit(AiStreamEvent.Error(error.error))
        } catch (error: Throwable) { emit(AiStreamEvent.Error(error.toAppError())) }
    }

    private fun geminiMessage(message: ChatMessage): JsonObject = buildJsonObject {
        when (message.role) {
            MessageRole.ASSISTANT -> {
                put("role", "model")
                putJsonArray("parts") {
                    if (message.content.isNotBlank()) addJsonObject { put("text", message.content) }
                    message.toolCalls.forEach { call ->
                        addJsonObject {
                            putJsonObject("functionCall") {
                                put("name", call.name)
                                put("args", call.arguments)
                            }
                        }
                    }
                }
            }
            MessageRole.TOOL -> {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", message.toolName ?: "tool")
                            putJsonObject("response") { put("result", message.content) }
                        }
                    }
                }
            }
            else -> {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", message.content) } }
            }
        }
    }
}
