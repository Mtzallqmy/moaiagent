package com.agentdroid.core.mcp

import com.agentdroid.core.agent.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

class McpToolAdapter(
    private val server: McpServerConfig,
    private val tool: McpToolDescriptor,
    private val clientProvider: () -> McpClient?,
    private val connected: () -> Boolean
) : AgentTool {
    override val definition = ToolDefinition(
        name = "mcp.${safe(server.id)}.${safe(tool.name)}",
        description = "External MCP server '${server.name}': ${tool.description}",
        inputSchema = tool.inputSchema,
        riskLevel = RiskLevel.EXTERNAL,
        category = ToolCategory.EXTERNAL
    )

    override fun availableInMode(mode: AgentMode): Boolean = connected() && server.enabled && mode == AgentMode.AGENT
    override suspend fun permissionKey(input: JsonObject, context: ToolContext): String = "mcp:${server.id}:tool:${tool.name}"
    override fun auditInputSummary(input: JsonObject, context: ToolContext): String =
        "MCP server=${server.id}; tool=${tool.name}; argumentKeys=${input.keys.sorted().joinToString(",")}"
    override suspend fun preview(input: JsonObject, context: ToolContext) = ToolPreview(
        summary = "Call external MCP tool ${tool.name} on ${server.name}",
        metadata = mapOf("server" to server.name, "transport" to server.transport.name)
    )
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val client = clientProvider() ?: return ToolResult.failure(AgentError.provider("MCP server is disconnected"))
        return client.callTool(tool.name, input).fold(
            { result -> ToolResult.success("MCP tool completed", buildJsonObject { put("serverId", server.id); put("tool", tool.name); put("result", result) }) },
            { failure -> ToolResult.failure(AgentError.provider("MCP ${server.id}/${tool.name}: ${failure.message}")) }
        )
    }

    companion object {
        fun safe(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9_.-]+"), "_").take(96).trim('_').ifBlank { "tool" }
    }
}

class McpResourceAdapter(private val client: McpClient) {
    suspend fun list(): Result<List<McpResourceDescriptor>> = client.listResources()
    suspend fun read(uri: String): Result<JsonObject> {
        require(uri.length <= 4096) { "MCP resource URI is too long" }
        return client.readResource(uri)
    }
}

class McpConnectionManager(
    private val clientFactory: (McpServerConfig) -> McpClient
) {
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val states = ConcurrentHashMap<String, McpConnectionState>()

    fun state(serverId: String): McpConnectionState? = states[serverId]
    fun states(): List<McpConnectionState> = states.values.sortedBy { it.config.name }

    suspend fun connect(config: McpServerConfig): Result<McpConnectionState> {
        states[config.id] = McpConnectionState(config, McpConnectionStatus.CONNECTING)
        val client = clientFactory(config)
        val outcome = runCatching {
            val identity = client.connect().getOrThrow()
            val tools = client.listTools().getOrThrow().take(256)
            val resources = client.listResources().getOrDefault(emptyList()).take(512)
            clients[config.id] = client
            McpConnectionState(config, McpConnectionStatus.CONNECTED, identity, tools, resources)
        }
        return outcome.onSuccess { states[config.id] = it }.onFailure {
            clients.remove(config.id)
            states[config.id] = McpConnectionState(config, McpConnectionStatus.ERROR, error = it.message)
        }
    }

    suspend fun disconnect(serverId: String): Result<Unit> {
        val client = clients.remove(serverId)
        val prior = states[serverId]
        val result = client?.disconnect() ?: Result.success(Unit)
        if (prior != null) states[serverId] = prior.copy(status = McpConnectionStatus.DISCONNECTED, tools = emptyList(), resources = emptyList())
        return result
    }

    suspend fun test(config: McpServerConfig): Result<McpServerIdentity> {
        val temp = clientFactory(config)
        return temp.connect().fold(
            { identity -> temp.ping().getOrThrow(); temp.disconnect(); Result.success(identity) },
            { Result.failure(it) }
        )
    }

    fun toolAdapters(serverId: String): List<McpToolAdapter> {
        val state = states[serverId]?.takeIf { it.status == McpConnectionStatus.CONNECTED } ?: return emptyList()
        return state.tools.map { descriptor ->
            McpToolAdapter(state.config, descriptor, { clients[serverId] }, { states[serverId]?.status == McpConnectionStatus.CONNECTED })
        }
    }

    fun resourceAdapter(serverId: String): McpResourceAdapter? = clients[serverId]?.let(::McpResourceAdapter)
}
