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
                request.messages.forEach { message -> add(openAiMessage(message)) }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.inputSchema)
                            }
                        }
                    }
                }
            }
        }.toString()
        try {
            transport.execute(request(config, secret, "/chat/completions", "POST", payload)).use { response ->
                response.requireSuccess()
                val reader = response.body?.charStream()?.buffered() ?: return@use
                val pending = linkedMapOf<Int, OpenAiToolAccumulator>()
                val completed = mutableSetOf<Int>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") continue
                    val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                    val delta = choice["delta"]?.jsonObject
                    delta?.get("content")?.jsonPrimitive?.contentOrNull?.let { emit(AiStreamEvent.TextDelta(it)) }
                    delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull?.let { emit(AiStreamEvent.ReasoningDelta(it)) }
                    delta?.get("tool_calls")?.jsonArray?.forEach { item ->
                        val tool = item.jsonObject
                        val index = tool["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val state = pending.getOrPut(index) { OpenAiToolAccumulator() }
                        tool["id"]?.jsonPrimitive?.contentOrNull?.let { id -> state.id = id }
                        val function = tool["function"]?.jsonObject
                        function?.get("name")?.jsonPrimitive?.contentOrNull?.let { name -> state.name = name }
                        if (!state.started && state.id.isNotBlank() && state.name.isNotBlank()) {
                            state.started = true
                            emit(AiStreamEvent.ToolCallStarted(state.id, state.name, index))
                        }
                        function?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { arguments ->
                            state.arguments.append(arguments)
                            if (state.id.isNotBlank()) emit(AiStreamEvent.ToolCallDelta(state.id, arguments, index))
                        }
                    }
                    root["usage"]?.jsonObject?.let { usage -> emit(AiStreamEvent.UsageEvent(Usage(usage["prompt_tokens"]?.jsonPrimitive?.intOrNull, usage["completion_tokens"]?.jsonPrimitive?.intOrNull, usage["total_tokens"]?.jsonPrimitive?.intOrNull))) }
                    if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "tool_calls") {
                        for ((index, state) in pending) {
                            if (index in completed) continue
                            emit(AiStreamEvent.ToolCallCompleted(state.toCall(), index))
                            completed += index
                        }
                    }
                }
                for ((index, state) in pending) {
                    if (index !in completed) emit(AiStreamEvent.ToolCallCompleted(state.toCall(), index))
                }
                emit(AiStreamEvent.Completed)
            }
        } catch (error: ProviderHttpException) { emit(AiStreamEvent.Error(error.error))
        } catch (error: ToolArgumentsException) { emit(AiStreamEvent.Error(AppError.Serialization(error.message ?: "Invalid tool arguments")))
        } catch (error: Throwable) { emit(AiStreamEvent.Error(error.toAppError())) }
    }

    private fun openAiMessage(message: ChatMessage): JsonObject = buildJsonObject {
        when (message.role) {
            MessageRole.TOOL -> {
                put("role", "tool")
                put("tool_call_id", message.toolCallId ?: "")
                put("content", message.content)
            }
            MessageRole.ASSISTANT -> {
                put("role", "assistant")
                if (message.content.isNotBlank()) put("content", message.content) else put("content", JsonNull)
                if (message.toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        message.toolCalls.forEach { call ->
                            addJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.rawArguments.ifBlank { call.arguments.toString() })
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                put("role", message.role.name.lowercase())
                put("content", message.content)
            }
        }
    }
}

private class OpenAiToolAccumulator {
    var id: String = ""
    var name: String = ""
    var started: Boolean = false
    val arguments = StringBuilder()

    fun toCall(): ModelToolCall {
        if (id.isBlank() || name.isBlank()) throw ToolArgumentsException("OpenAI returned an incomplete tool call")
        val raw = arguments.toString().ifBlank { "{}" }
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw ToolArgumentsException("Invalid JSON arguments for $name: ${it.message}")
        }
        return ModelToolCall(id, name, parsed, raw)
    }
}

private class ToolArgumentsException(message: String) : IllegalArgumentException(message)

class OpenAiProvider(transport: HttpTransport = HttpTransport()) : OpenAiCompatibleProvider(ProviderKind.OPENAI, "OpenAI", "https://api.openai.com/v1", transport)
class OpenRouterProvider(transport: HttpTransport = HttpTransport()) : OpenAiCompatibleProvider(ProviderKind.OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1", transport) {
    override fun headers(config: ProviderConfig, secret: String): Map<String, String> = super.headers(config, secret) + buildMap { config.siteUrl?.let { put("HTTP-Referer", it) }; config.appName?.let { put("X-Title", it) } }
}
class CompatibleProvider : OpenAiCompatibleProvider(ProviderKind.COMPATIBLE, "OpenAI-Compatible", "http://localhost:1234/v1")
