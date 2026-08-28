package com.agentdroid.core.mcp

import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpMcpClientTest {
    @Test fun `connect lists calls and disconnects through streamable HTTP`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","serverInfo":{"name":"test","version":"1"},"capabilities":{}}}""").addHeader("Mcp-Session-Id", "session-1"))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object","properties":{"value":{"type":"string"}}}}]}}"""))
            server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"resources":[{"uri":"test://one","name":"One"}]}}"""))
            server.enqueue(json("""{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"ok"}]}}"""))
            server.enqueue(MockResponse().setResponseCode(200))
            val config = McpServerConfig("local", "Local test", server.url("/mcp").toString())
            val client = HttpMcpClient(config)

            assertEquals("test", client.connect().getOrThrow().name)
            assertEquals("echo", client.listTools().getOrThrow().single().name)
            assertEquals("test://one", client.listResources().getOrThrow().single().uri)
            assertTrue(client.callTool("echo", buildJsonObject { put("value", "hi") }).isSuccess)
            assertTrue(client.disconnect().isSuccess)

            val initialize = server.takeRequest()
            assertTrue(initialize.body.readUtf8().contains("2025-11-25"))
            server.takeRequest()
            val tools = server.takeRequest()
            assertEquals("session-1", tools.getHeader("Mcp-Session-Id"))
        }
    }

    @Test fun `expired session reinitializes once and retries request`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(init("one")).addHeader("Mcp-Session-Id", "session-1"))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(json(init("two")).addHeader("Mcp-Session-Id", "session-2"))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(json("""{"jsonrpc":"2.0","id":4,"result":{}}"""))
            val client = HttpMcpClient(McpServerConfig("local", "Local", server.url("/mcp").toString()))
            assertTrue(client.connect().isSuccess)
            assertTrue(client.ping().isSuccess)
            val requests = List(6) { server.takeRequest() }
            assertEquals("session-1", requests[2].getHeader("Mcp-Session-Id"))
            assertNull(requests[3].getHeader("Mcp-Session-Id"))
            assertEquals("session-2", requests[5].getHeader("Mcp-Session-Id"))
        }
    }

    @Test fun `call timeout returns failure instead of hanging`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(init("slow")).addHeader("Mcp-Session-Id", "s"))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{}}""").setBodyDelay(2, TimeUnit.SECONDS))
            val http = OkHttpClient.Builder().callTimeout(200, TimeUnit.MILLISECONDS).build()
            val client = HttpMcpClient(McpServerConfig("local", "Local", server.url("/mcp").toString()), http = http)
            assertTrue(client.connect().isSuccess)
            assertTrue(client.ping().isFailure)
        }
    }

    @Test fun `sse ignores non json data before rpc response`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: keepalive\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\",\"serverInfo\":{\"name\":\"sse\"}}}\n"
            ).addHeader("Mcp-Session-Id", "s"))
            server.enqueue(MockResponse().setResponseCode(202))
            val client = HttpMcpClient(McpServerConfig("local", "Local", server.url("/mcp").toString()))
            assertEquals("sse", client.connect().getOrThrow().name)
        }
    }

    @Test fun `tool adapter is external agent-only and exposes no implicit context`(): Unit = runBlocking {
        val fake = object : McpClient {
            override val config = McpServerConfig("srv", "Server", "https://example.com/mcp")
            var arguments = buildJsonObject {}
            override suspend fun connect() = Result.success(McpServerIdentity("Server", "1", "2025-11-25"))
            override suspend fun ping() = Result.success(Unit)
            override suspend fun listTools() = Result.success(emptyList<McpToolDescriptor>())
            override suspend fun listResources() = Result.success(emptyList<McpResourceDescriptor>())
            override suspend fun callTool(name: String, arguments: kotlinx.serialization.json.JsonObject): Result<kotlinx.serialization.json.JsonObject> { this.arguments = arguments; return Result.success(buildJsonObject { put("ok", true) }) }
            override suspend fun readResource(uri: String) = Result.success(buildJsonObject {})
            override suspend fun disconnect() = Result.success(Unit)
        }
        val adapter = McpToolAdapter(fake.config, McpToolDescriptor("echo", inputSchema = buildJsonObject { put("type", "object") }), { fake }, { true })
        assertFalse(adapter.availableInMode(AgentMode.CHAT)); assertFalse(adapter.availableInMode(AgentMode.PLAN)); assertTrue(adapter.availableInMode(AgentMode.AGENT))
        assertEquals("mcp:srv:tool:echo", adapter.permissionKey(buildJsonObject {}, ToolContext("workspace-secret", "conversation-secret", "s", AgentMode.AGENT)))
        adapter.execute(buildJsonObject { put("value", "only-this") }, ToolContext("workspace-secret", "conversation-secret", "s", AgentMode.AGENT))
        assertEquals(setOf("value"), fake.arguments.keys)
    }

    @Test(expected = IllegalArgumentException::class) fun `remote cleartext endpoint is rejected`() {
        McpServerConfig("bad", "Bad", "http://example.com/mcp")
    }

    private fun init(name: String) = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","serverInfo":{"name":"$name","version":"1"},"capabilities":{}}}"""
    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}
