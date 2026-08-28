package com.agentdroid.core.mcp

import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

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
            server.takeRequest() // initialized notification
            val tools = server.takeRequest()
            assertEquals("session-1", tools.getHeader("Mcp-Session-Id"))
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

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}
