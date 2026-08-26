package com.agentdroid.core.ai

import com.agentdroid.core.ai.providers.AnthropicProvider
import com.agentdroid.core.ai.providers.GeminiProvider
import com.agentdroid.core.ai.providers.OpenAiProvider
import com.agentdroid.core.ai.transport.HttpTransport
import com.agentdroid.core.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProtocolTest {
    private val request = ChatRequest(listOf(ChatMessage(MessageRole.USER, "hello")), "test-model")

    @Test fun openAiSseParsesTextDoneAndUnknownLines() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: not-json\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n"))
            val events = OpenAiProvider(HttpTransport()).streamChat(request, config(ProviderKind.OPENAI, server, "/v1"), "secret").toList()
            assertTrue(events.any { it is AiStreamEvent.TextDelta && it.text == "hi" })
            assertTrue(events.last() is AiStreamEvent.Completed)
            assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun anthropicUsesMessagesEndpointAndParsesNativeEvents() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":4,\"output_tokens\":0}}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"مرحبا\"}}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":2}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n"))
            val events = AnthropicProvider(HttpTransport()).streamChat(request, config(ProviderKind.ANTHROPIC, server, "/v1"), "secret").toList()
            assertTrue(events.any { it is AiStreamEvent.TextDelta && it.text == "مرحبا" })
            assertTrue(events.any { it is AiStreamEvent.UsageEvent })
            assertTrue(events.any { it is AiStreamEvent.Completed })
            val recorded = server.takeRequest()
            assertEquals("/v1/messages", recorded.path)
            assertEquals("secret", recorded.getHeader("x-api-key"))
            assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        }
    }

    @Test fun geminiUsesStreamGenerateContentAndMergesTextChunks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"a\"}]}}]}\n\ndata: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"b\"}]}}],\"usageMetadata\":{\"totalTokenCount\":3}}\n"))
            val events = GeminiProvider(HttpTransport()).streamChat(request, config(ProviderKind.GEMINI, server, "/v1beta"), "secret").toList()
            assertEquals(listOf("a", "b"), events.filterIsInstance<AiStreamEvent.TextDelta>().map { it.text })
            assertTrue(events.any { it is AiStreamEvent.UsageEvent })
            assertEquals("/v1beta/models/test-model:streamGenerateContent?key=secret", server.takeRequest().path)
        }
    }

    @Test fun httpErrorsMapWithoutExposingBodyToUserMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret-bearing provider response"))
            val events = OpenAiProvider(HttpTransport()).streamChat(request, config(ProviderKind.OPENAI, server, "/v1"), "secret").toList()
            val error = events.filterIsInstance<AiStreamEvent.Error>().single().error
            assertTrue(error is AppError.Authentication)
            assertTrue(!error.userMessage.contains("secret-bearing"))
        }
    }

    private fun config(kind: ProviderKind, server: MockWebServer, basePath: String) = ProviderConfig("id", "test", kind, server.url(basePath).toString().trimEnd('/'), "test-model")
}
