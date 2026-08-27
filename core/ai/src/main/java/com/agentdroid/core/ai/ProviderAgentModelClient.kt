package com.agentdroid.core.ai

import com.agentdroid.core.agent.AgentMessageRole
import com.agentdroid.core.agent.AgentModelClient
import com.agentdroid.core.agent.AgentModelEvent
import com.agentdroid.core.agent.AgentModelRequest
import com.agentdroid.core.agent.AgentModelResponse
import com.agentdroid.core.agent.ToolCall
import com.agentdroid.core.model.AiStreamEvent
import com.agentdroid.core.model.ChatMessage
import com.agentdroid.core.model.ChatRequest
import com.agentdroid.core.model.MessageRole
import com.agentdroid.core.model.ModelToolCall
import com.agentdroid.core.model.ModelToolDefinition
import com.agentdroid.core.model.ProviderConfig
import com.agentdroid.core.model.Usage
import kotlinx.coroutines.flow.collect

class ProviderAgentModelClient(
    private val provider: AiProvider,
    private val config: ProviderConfig,
    private val secret: String
) : AgentModelClient {
    override val supportsToolCalling: Boolean get() = provider.capabilities.toolCalling

    override suspend fun complete(
        request: AgentModelRequest,
        onEvent: suspend (AgentModelEvent) -> Unit
    ): Result<AgentModelResponse> = runCatching {
        val text = StringBuilder()
        val calls = LinkedHashMap<String, ModelToolCall>()
        var usage: Usage? = null
        var failure: Throwable? = null

        val chatRequest = ChatRequest(
            messages = request.messages.map { message ->
                when (message.role) {
                    AgentMessageRole.USER -> ChatMessage(MessageRole.USER, message.content)
                    AgentMessageRole.ASSISTANT -> ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = message.content,
                        toolCalls = message.toolCalls.map { ModelToolCall(it.id, it.name, it.input, it.rawArguments ?: it.input.toString()) }
                    )
                    AgentMessageRole.TOOL -> ChatMessage(
                        role = MessageRole.TOOL,
                        content = message.content,
                        toolCallId = message.toolCallId,
                        toolName = message.toolName
                    )
                }
            },
            model = request.modelId,
            systemPrompt = request.systemPrompt,
            tools = request.tools.map { ModelToolDefinition(it.name, it.description, it.inputSchema) }
        )

        provider.streamChat(chatRequest, config, secret).collect { event ->
            when (event) {
                AiStreamEvent.Started -> onEvent(AgentModelEvent.Started)
                is AiStreamEvent.TextDelta -> {
                    text.append(event.text)
                    onEvent(AgentModelEvent.TextDelta(event.text))
                }
                is AiStreamEvent.ReasoningDelta -> Unit
                is AiStreamEvent.ToolCallStarted -> onEvent(AgentModelEvent.ToolCallStarted(event.id, event.name))
                is AiStreamEvent.ToolCallDelta -> onEvent(AgentModelEvent.ToolCallDelta(event.id, event.argumentsDelta))
                is AiStreamEvent.ToolCallCompleted -> {
                    calls[event.call.id] = event.call
                    onEvent(
                        AgentModelEvent.ToolCallCompleted(
                            ToolCall(
                                id = event.call.id,
                                name = event.call.name,
                                input = event.call.arguments,
                                rawArguments = event.call.rawArguments
                            )
                        )
                    )
                }
                is AiStreamEvent.UsageEvent -> usage = event.usage
                is AiStreamEvent.Error -> failure = ProviderStreamException(event.error.technicalMessage)
                AiStreamEvent.Completed -> Unit
            }
        }
        failure?.let { throw it }
        AgentModelResponse(
            text = text.toString(),
            toolCalls = calls.values.map { ToolCall(it.id, it.name, it.arguments, it.rawArguments) },
            usage = usage
        )
    }
}

private class ProviderStreamException(message: String) : IllegalStateException(message)
