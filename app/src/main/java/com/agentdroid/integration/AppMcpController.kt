package com.agentdroid.integration

import android.content.Context
import com.agentdroid.AppContainer
import com.agentdroid.core.mcp.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

class AppMcpController(
    context: Context,
    private val container: AppContainer,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "mcp/servers.json")
    private val lock = Mutex()
    private val http = OkHttpClient.Builder().build()
    private val manager = McpConnectionManager { config ->
        HttpMcpClient(
            config = config,
            credentials = McpCredentialResolver { alias -> container.secretStore.get(alias) },
            http = http
        )
    }
    private val _states = MutableStateFlow(load().map { McpConnectionState(it, McpConnectionStatus.DISCONNECTED) })
    val states: StateFlow<List<McpConnectionState>> = _states.asStateFlow()

    suspend fun save(
        id: String? = null,
        name: String,
        endpoint: String,
        enabled: Boolean,
        credential: String? = null
    ): Result<McpServerConfig> = runCatching {
        val serverId = id ?: UUID.randomUUID().toString().replace("-", "").take(16)
        val prior = _states.value.firstOrNull { it.config.id == serverId }?.config
        val alias = when {
            !credential.isNullOrBlank() -> "mcp.$serverId.credential".also { container.secretStore.put(it, credential) }
            prior?.credentialAlias != null -> prior.credentialAlias
            else -> null
        }
        val config = McpServerConfig(serverId, name.trim(), endpoint.trim(), enabled = enabled, credentialAlias = alias)
        lock.withLock {
            val configs = _states.value.map { it.config }.filterNot { it.id == serverId } + config
            persist(configs.sortedBy { it.name })
            _states.value = configs.sortedBy { it.name }.map { cfg ->
                manager.state(cfg.id)?.takeIf { it.config == cfg } ?: McpConnectionState(cfg, McpConnectionStatus.DISCONNECTED)
            }
        }
        config
    }

    suspend fun delete(serverId: String): Result<Unit> = runCatching {
        manager.disconnect(serverId)
        lock.withLock {
            val removed = _states.value.firstOrNull { it.config.id == serverId }?.config
            removed?.credentialAlias?.let(container.secretStore::delete)
            val remaining = _states.value.map { it.config }.filterNot { it.id == serverId }
            persist(remaining)
            _states.value = remaining.map { McpConnectionState(it, McpConnectionStatus.DISCONNECTED) }
        }
    }

    suspend fun setEnabled(serverId: String, enabled: Boolean): Result<Unit> = runCatching {
        val current = _states.value.first { it.config.id == serverId }.config
        if (!enabled) manager.disconnect(serverId)
        save(serverId, current.name, current.endpoint, enabled).getOrThrow()
        Unit
    }

    suspend fun connect(serverId: String): Result<McpConnectionState> {
        val config = _states.value.firstOrNull { it.config.id == serverId }?.config
            ?: return Result.failure(IllegalArgumentException("Unknown MCP server"))
        val connecting = McpConnectionState(config, McpConnectionStatus.CONNECTING)
        replace(connecting)
        val outcome = manager.connect(config)
        outcome.onSuccess { state ->
            manager.toolAdapters(serverId).forEach { adapter ->
                if (container.toolRegistry.get(adapter.definition.name) == null) container.toolRegistry.register(adapter)
            }
            replace(state)
        }.onFailure { failure -> replace(McpConnectionState(config, McpConnectionStatus.ERROR, error = failure.message)) }
        return outcome
    }

    suspend fun disconnect(serverId: String): Result<Unit> {
        val result = manager.disconnect(serverId)
        manager.state(serverId)?.let(::replace)
        return result
    }

    suspend fun test(serverId: String): Result<McpServerIdentity> {
        val config = _states.value.firstOrNull { it.config.id == serverId }?.config
            ?: return Result.failure(IllegalArgumentException("Unknown MCP server"))
        return manager.test(config)
    }

    fun resourceAdapter(serverId: String): McpResourceAdapter? = manager.resourceAdapter(serverId)

    private fun replace(state: McpConnectionState) {
        _states.value = (_states.value.filterNot { it.config.id == state.config.id } + state).sortedBy { it.config.name }
    }

    private fun load(): List<McpServerConfig> = runCatching {
        if (!file.isFile) emptyList() else json.decodeFromString(ListSerializer(McpServerConfig.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private suspend fun persist(configs: List<McpServerConfig>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".servers-${System.nanoTime()}.tmp")
        temp.writeText(json.encodeToString(ListSerializer(McpServerConfig.serializer()), configs))
        if (file.exists()) require(file.delete()) { "Could not replace MCP configuration" }
        require(temp.renameTo(file)) { "Could not persist MCP configuration" }
    }
}
