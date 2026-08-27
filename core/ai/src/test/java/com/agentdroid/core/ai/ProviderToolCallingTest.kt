package com.agentdroid.core.ai

import com.agentdroid.core.ai.providers.AnthropicProvider
import com.agentdroid.core.ai.providers.GeminiProvider
import com.agentdroid.core.ai.providers.OpenAiProvider
import com.agentdroid.core.ai.transport.HttpTransport
import com.agentdroid.core.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderToolCallingTest {
    private val tool = ModelToolDefinition(
        "read_file",
        "Read a workspace file",
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("path", buildJsonObject { put("type", "string") }) })
        }
    )

    @Test fun openAiStreamsToolArgumentsAndInjectsToolResult() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"pa\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":\\\"a.txt\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                    "data: [DONE]\n"
            ))
            val provider = OpenAiProvider(HttpTransport())
            val config = config(ProviderKind.OPENAI, server, "/v1")
            val events = provider.streamChat(ChatRequest(listOf(ChatMessage(MessageRole.USER, "read")), "test-model", tools = listOf(tool)), config, "secret").toList()
            val call = events.filterIsInstance<AiStreamEvent.ToolCallCompleted>().single().call
            assertEquals("call_1", call.id)
            assertEquals("read_file", call.name)
            assertEquals("a.txt", call.arguments["path"]?.jsonPrimitive?.content)
            assertTrue(events.filterIsInstance<AiStreamEvent.ToolCallDelta>().size >= 2)
            val firstBody = server.takeRequest().body.readUtf8()
            assertTrue(firstBody.contains("\"tools\""))
            assertTrue(firstBody.contains("\"read_file\""))

            server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}\n\ndata: [DONE]\n"))
            provider.streamChat(
                ChatRequest(
                    listOf(
                        ChatMessage(MessageRole.USER, "read"),
                        ChatMessage(MessageRole.ASSISTANT, toolCalls = listOf(call)),
                        ChatMessage(MessageRole.TOOL, "SUCCESS: file contents", toolCallId = call.id, toolName = call.name)
                    ),
                    "test-model",
                    tools = listOf(tool)
                ),
                config,
                "secret"
            ).toList()
            val secondBody = server.takeRequest().body.readUtf8()
            assertTrue(secondBody.contains("\"role\":\"tool\""))
            assertTrue(secondBody.contains("\"tool_call_id\":\"call_1\""))
            assertTrue(secondBody.contains("SUCCESS: file contents"))
        }
    }

    @Test fun anthropicAssemblesPartialJsonAndInjectsToolResult() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{}}}\n\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}\n\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"a.txt\\\"}\"}}\n\n" +
                    "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                    "data: {\"type\":\"message_stop\"}\n"
            ))
            val provider = AnthropicProvider(HttpTransport())
            val config = config(ProviderKind.ANTHROPIC, server, "/v1")
            val events = provider.streamChat(ChatRequest(listOf(ChatMessage(MessageRole.USER, "read")), "test-model", tools = listOf(tool)), config, "secret").toList()
            val call = events.filterIsInstance<AiStreamEvent.ToolCallCompleted>().single().call
            assertEquals("a.txt", call.arguments["path"]?.jsonPrimitive?.content)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"input_schema\""))

            server.enqueue(sse("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}\n\ndata: {\"type\":\"message_stop\"}\n"))
            provider.streamChat(
                ChatRequest(
                    listOf(
                        ChatMessage(MessageRole.USER, "read"),
                        ChatMessage(MessageRole.ASSISTANT, toolCalls = listOf(call)),
                        ChatMessage(MessageRole.TOOL, "SUCCESS", toolCallId = call.id, toolName = call.name)
                    ),
                    "test-model",
                    tools = listOf(tool)
                ), config, "secret"
            ).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"type\":\"tool_result\""))
            assertTrue(body.contains("\"tool_use_id\":\"toolu_1\""))
        }
    }

    @Test fun geminiParsesFunctionCallAndInjectsFunctionResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse("data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"read_file\",\"args\":{\"path\":\"a.txt\"}}}]}}]}\n"))
            val provider = GeminiProvider(HttpTransport())
            val config = config(ProviderKind.GEMINI, server, "/v1beta")
            val events = provider.streamChat(ChatRequest(listOf(ChatMessage(MessageRole.USER, "read")), "test-model", tools = listOf(tool)), config, "secret").toList()
            val call = events.filterIsInstance<AiStreamEvent.ToolCallCompleted>().single().call
            assertEquals("read_file", call.name)
            assertEquals("a.txt", call.arguments["path"]?.jsonPrimitive?.content)
            val firstBody = server.takeRequest().body.readUtf8()
            assertTrue(firstBody.contains("\"functionDeclarations\""))

            server.enqueue(sse("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]}}]}\n"))
            provider.streamChat(
                ChatRequest(
                    listOf(
                        ChatMessage(MessageRole.USER, "read"),
                        ChatMessage(MessageRole.ASSISTANT, toolCalls = listOf(call)),
                        ChatMessage(MessageRole.TOOL, "SUCCESS", toolCallId = call.id, toolName = call.name)
                    ),
                    "test-model",
                    tools = listOf(tool)
                ), config, "secret"
            ).toList()
            val secondBody = server.takeRequest().body.readUtf8()
            assertTrue(secondBody.contains("\"functionResponse\""))
            assertTrue(secondBody.contains("\"name\":\"read_file\""))
            assertTrue(secondBody.contains("SUCCESS"))
        }
    }

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    private fun config(kind: ProviderKind, server: MockWebServer, path: String) = ProviderConfig("id", "test", kind, server.url(path).toString().trimEnd('/'), "test-model")
}
