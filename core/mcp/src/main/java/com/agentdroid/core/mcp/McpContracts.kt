package com.agentdroid.core.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class McpTransport { STREAMABLE_HTTP }

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val endpoint: String,
    val transport: McpTransport = McpTransport.STREAMABLE_HTTP,
    val enabled: Boolean = true,
    /** Alias only. Credentials stay in AgentDroid secure storage and are resolved at connection time. */
    val credentialAlias: String? = null
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Invalid MCP server id" }
        require(name.isNotBlank())
        McpEndpointPolicy.requireAllowed(endpoint)
    }
}

@Serializable
data class McpServerIdentity(val name: String, val version: String? = null, val protocolVersion: String)

@Serializable
data class McpToolDescriptor(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class McpResourceDescriptor(
    val uri: String,
    val name: String = uri,
    val description: String? = null,
    val mimeType: String? = null
)

@Serializable
enum class McpConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

@Serializable
data class McpConnectionState(
    val config: McpServerConfig,
    val status: McpConnectionStatus,
    val identity: McpServerIdentity? = null,
    val tools: List<McpToolDescriptor> = emptyList(),
    val resources: List<McpResourceDescriptor> = emptyList(),
    val error: String? = null
)

interface McpClient {
    val config: McpServerConfig
    suspend fun connect(): Result<McpServerIdentity>
    suspend fun ping(): Result<Unit>
    suspend fun listTools(): Result<List<McpToolDescriptor>>
    suspend fun listResources(): Result<List<McpResourceDescriptor>>
    suspend fun callTool(name: String, arguments: JsonObject): Result<JsonObject>
    suspend fun readResource(uri: String): Result<JsonObject>
    suspend fun disconnect(): Result<Unit>
}

fun interface McpCredentialResolver { suspend fun resolve(alias: String): String? }

object McpEndpointPolicy {
    fun requireAllowed(raw: String) {
        val uri = runCatching { java.net.URI(raw) }.getOrElse { throw IllegalArgumentException("Invalid MCP endpoint") }
        require(uri.userInfo == null && uri.fragment == null) { "MCP endpoint must not contain credentials or fragments" }
        val host = uri.host?.lowercase().orEmpty()
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
            "MCP requires HTTPS except for loopback test/local servers"
        }
    }
}
