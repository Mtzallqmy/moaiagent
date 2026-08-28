package com.agentdroid.core.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicLong

class HttpMcpClient(
    override val config: McpServerConfig,
    private val credentials: McpCredentialResolver = McpCredentialResolver { null },
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : McpClient {
    private val ids = AtomicLong(1)
    @Volatile private var sessionId: String? = null
    @Volatile private var negotiatedVersion: String = SUPPORTED_PROTOCOL

    override suspend fun connect(): Result<McpServerIdentity> = runCatching {
        require(config.enabled) { "MCP server is disabled" }
        val result = rpc("initialize", buildJsonObject {
            put("protocolVersion", SUPPORTED_PROTOCOL)
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", "AgentDroid"); put("version", "1.0") })
        }, initializing = true)
        val protocol = result["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: SUPPORTED_PROTOCOL
        require(protocol in ACCEPTED_PROTOCOLS) { "Unsupported MCP protocol version: $protocol" }
        negotiatedVersion = protocol
        notify("notifications/initialized", buildJsonObject {})
        val info = result["serverInfo"]?.jsonObject
        McpServerIdentity(
            name = info?.get("name")?.jsonPrimitive?.contentOrNull ?: config.name,
            version = info?.get("version")?.jsonPrimitive?.contentOrNull,
            protocolVersion = protocol
        )
    }

    override suspend fun ping(): Result<Unit> = runCatching { rpc("ping", buildJsonObject {}); Unit }

    override suspend fun listTools(): Result<List<McpToolDescriptor>> = runCatching {
        paginate("tools/list", "tools").map { raw ->
            val item = raw.jsonObject
            McpToolDescriptor(
                name = item["name"]!!.jsonPrimitive.content,
                description = item["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchema = item["inputSchema"] as? JsonObject ?: JsonObject(emptyMap())
            )
        }
    }

    override suspend fun listResources(): Result<List<McpResourceDescriptor>> = runCatching {
        paginate("resources/list", "resources").map { raw ->
            val item = raw.jsonObject
            McpResourceDescriptor(
                uri = item["uri"]!!.jsonPrimitive.content,
                name = item["name"]?.jsonPrimitive?.contentOrNull ?: item["uri"]!!.jsonPrimitive.content,
                description = item["description"]?.jsonPrimitive?.contentOrNull,
                mimeType = item["mimeType"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    override suspend fun callTool(name: String, arguments: JsonObject): Result<JsonObject> = runCatching {
        rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
    }

    override suspend fun readResource(uri: String): Result<JsonObject> = runCatching {
        rpc("resources/read", buildJsonObject { put("uri", uri) })
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        val sid = sessionId
        sessionId = null
        if (sid != null) withContext(Dispatchers.IO) {
            val request = baseRequest().delete().header("Mcp-Session-Id", sid).build()
            http.newCall(request).execute().close()
        }
    }

    private suspend fun paginate(method: String, field: String, maxPages: Int = 20): List<JsonElement> {
        val output = mutableListOf<JsonElement>()
        var cursor: String? = null
        repeat(maxPages) {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = rpc(method, params)
            output += result[field]?.jsonArray.orEmpty()
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
            if (cursor == null) return output
        }
        error("MCP pagination exceeded $maxPages pages")
    }

    private suspend fun notify(method: String, params: JsonObject) {
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) }, expectsResponse = false)
    }

    private suspend fun rpc(method: String, params: JsonObject, initializing: Boolean = false): JsonObject {
        val id = ids.getAndIncrement()
        val envelope = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }
        val response = post(envelope, expectsResponse = true, initializing = initializing)
        response["error"]?.jsonObject?.let { error ->
            throw IllegalStateException("MCP ${error["code"]}: ${error["message"]?.jsonPrimitive?.contentOrNull}")
        }
        return response["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun post(payload: JsonObject, expectsResponse: Boolean, initializing: Boolean = false): JsonObject = withContext(Dispatchers.IO) {
        val body = payload.toString().toRequestBody(JSON)
        val builder = baseRequest().post(body).header("Accept", "application/json, text/event-stream")
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        if (!initializing) builder.header("MCP-Protocol-Version", negotiatedVersion)
        val response = http.newCall(builder.build()).execute()
        response.use {
            if (initializing) response.header("Mcp-Session-Id")?.takeIf { it.length <= 512 }?.let { sessionId = it }
            require(response.isSuccessful) { "MCP HTTP ${response.code}" }
            if (!expectsResponse || response.code == 202) return@withContext JsonObject(emptyMap())
            val text = response.body?.string().orEmpty()
            require(text.toByteArray().size <= MAX_RESPONSE_BYTES) { "MCP response exceeds limit" }
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val jsonText = if ("text/event-stream" in contentType) extractSseJson(text) else text
            json.parseToJsonElement(jsonText).jsonObject
        }
    }

    private suspend fun baseRequest(): Request.Builder {
        val builder = Request.Builder().url(config.endpoint).header("Content-Type", "application/json")
        val alias = config.credentialAlias
        if (alias != null) credentials.resolve(alias)?.let { token -> builder.header("Authorization", "Bearer $token") }
        return builder
    }

    private fun extractSseJson(body: String): String {
        val data = body.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim()
        require(!data.isNullOrBlank()) { "MCP SSE response contained no data event" }
        return data
    }

    companion object {
        const val SUPPORTED_PROTOCOL = "2025-11-25"
        val ACCEPTED_PROTOCOLS = setOf("2025-11-25", "2025-06-18", "2025-03-26")
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}
