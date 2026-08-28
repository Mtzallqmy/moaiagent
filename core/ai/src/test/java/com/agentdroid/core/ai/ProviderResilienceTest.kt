package com.agentdroid.core.ai

import com.agentdroid.core.ai.providers.AnthropicProvider
import com.agentdroid.core.ai.providers.GeminiProvider
import com.agentdroid.core.ai.providers.OpenAiProvider
import com.agentdroid.core.ai.transport.HttpTransport
import com.agentdroid.core.model.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProviderResilienceTest {
    private val request = ChatRequest(listOf(ChatMessage(MessageRole.USER, "hello")), "test-model")

    @Test fun `openai maps rate limit responses`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("too many requests"))
            val events = OpenAiProvider(HttpTransport()).streamChat(request, config(ProviderKind.OPENAI, server, "/v1"), "secret").toList()
            assertTrue(events.filterIsInstance<AiStreamEvent.Error>().single().error is AppError.RateLimit)
        }
    }

    @Test fun `openai streams multiple independent tool calls`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"one\",\"arguments\":\"{\\\"a\\\":1}\"}},{\"index\":1,\"id\":\"c2\",\"function\":{\"name\":\"two\",\"arguments\":\"{\\\"b\\\":2}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n"
            ))
            val events = OpenAiProvider(HttpTransport()).streamChat(request, config(ProviderKind.OPENAI, server, "/v1"), "secret").toList()
            val calls = events.filterIsInstance<AiStreamEvent.ToolCallCompleted>()
            assertEquals(listOf("one", "two"), calls.map { it.call.name })
            assertEquals(listOf(0, 1), calls.map { it.index })
        }
    }

    @Test fun `openai malformed tool arguments fail closed`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"bad\",\"arguments\":\"{not-json\"}}]},\"finish_reason\":\"tool_calls\"}]}\n"
            ))
            val events = OpenAiProvider(HttpTransport()).streamChat(request, config(ProviderKind.OPENAI, server, "/v1"), "secret").toList()
            assertTrue(events.any { it is AiStreamEvent.Error && it.error is AppError.Serialization })
            assertTrue(events.none { it is AiStreamEvent.ToolCallCompleted })
        }
    }

    @Test fun `provider streaming cancellation closes open responses`(): Unit = runBlocking {
        val factories = listOf<(MockWebServer) -> kotlinx.coroutines.flow.Flow<AiStreamEvent>>(
            { server -> OpenAiProvider(HttpTransport()).streamChat(request, config(ProviderKind.OPENAI, server, "/v1"), "secret") },
            { server -> AnthropicProvider(HttpTransport()).streamChat(request, config(ProviderKind.ANTHROPIC, server, "/v1"), "secret") },
            { server -> GeminiProvider(HttpTransport()).streamChat(request, config(ProviderKind.GEMINI, server, "/v1beta"), "secret") }
        )
        factories.forEach { factory ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {}\n").setBodyDelay(10, TimeUnit.SECONDS))
                val job = launch { factory(server).collect() }
                delay(150)
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
        }
    }

    private fun config(kind: ProviderKind, server: MockWebServer, basePath: String) =
        ProviderConfig("id", "test", kind, server.url(basePath).toString().trimEnd('/'), "test-model")
}
