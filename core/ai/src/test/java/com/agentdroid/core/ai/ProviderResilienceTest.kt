package com.agentdroid.core.ai

import com.agentdroid.core.ai.providers.AnthropicProvider
import com.agentdroid.core.ai.providers.GeminiProvider
import com.agentdroid.core.ai.providers.OpenAiProvider
import com.agentdroid.core.ai.transport.HttpTransport
import com.agentdroid.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
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
        val factories = listOf<Pair<ProviderKind, (HttpTransport) -> Flow<AiStreamEvent>>>(
            ProviderKind.OPENAI to { transport -> OpenAiProvider(transport).streamChat(request, cancellationConfig(ProviderKind.OPENAI), "secret") },
            ProviderKind.ANTHROPIC to { transport -> AnthropicProvider(transport).streamChat(request, cancellationConfig(ProviderKind.ANTHROPIC), "secret") },
            ProviderKind.GEMINI to { transport -> GeminiProvider(transport).streamChat(request, cancellationConfig(ProviderKind.GEMINI), "secret") }
        )

        factories.forEach { (kind, factory) ->
            val body = BlockingResponseBody()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/event-stream")
                    .body(body)
                    .build()
            }.build()

            val job = launch(Dispatchers.IO) { factory(HttpTransport(client)).collect() }
            val enteredRead = withContext(Dispatchers.IO) { body.awaitRead(2, TimeUnit.SECONDS) }
            assertTrue("$kind stream never entered its response body", enteredRead)

            withTimeout(2_000) { job.cancelAndJoin() }

            assertTrue("$kind stream did not remain cancelled", job.isCancelled)
            val closed = withContext(Dispatchers.IO) { body.awaitClosed(2, TimeUnit.SECONDS) }
            assertTrue("$kind response body was not closed on cancellation", closed)
        }
    }

    private fun config(kind: ProviderKind, server: MockWebServer, basePath: String) =
        ProviderConfig("id", "test", kind, server.url(basePath).toString().trimEnd('/'), "test-model")

    private fun cancellationConfig(kind: ProviderKind) = ProviderConfig(
        id = "id",
        name = "test",
        kind = kind,
        baseUrl = "https://provider.test/v1",
        modelId = "test-model"
    )

    private class BlockingResponseBody : ResponseBody() {
        private val readStarted = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        private val blockingSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readStarted.countDown()
                closed.await()
                throw IOException("response body closed")
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                closed.countDown()
            }
        }.buffer()

        override fun contentType(): MediaType = "text/event-stream".toMediaType()
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = blockingSource

        fun awaitRead(timeout: Long, unit: TimeUnit): Boolean = readStarted.await(timeout, unit)
        fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean = closed.await(timeout, unit)
    }
}
