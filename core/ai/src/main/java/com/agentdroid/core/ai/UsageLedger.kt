package com.agentdroid.core.ai

import com.agentdroid.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicLong

/** Process-local, secret-free usage telemetry sourced only from provider-reported token usage. */
data class UsageRecord(
    val id: Long,
    val providerId: String,
    val providerKind: ProviderKind,
    val modelId: String,
    val usage: Usage,
    val timestamp: Long
)

object UsageLedger {
    private val ids = AtomicLong(1)
    private val _records = MutableStateFlow<List<UsageRecord>>(emptyList())
    val records: StateFlow<List<UsageRecord>> = _records.asStateFlow()

    @Synchronized
    fun record(config: ProviderConfig, modelId: String, usage: Usage) {
        if (usage.promptTokens == null && usage.completionTokens == null && usage.totalTokens == null) return
        val next = UsageRecord(ids.getAndIncrement(), config.id, config.kind, modelId, usage, System.currentTimeMillis())
        _records.value = (_records.value + next).takeLast(2_000)
    }

    @Synchronized fun clear() { _records.value = emptyList() }
}

class UsageRecordingProvider(private val delegate: AiProvider) : AiProvider {
    override val kind: ProviderKind get() = delegate.kind
    override val displayName: String get() = delegate.displayName
    override val capabilities: ProviderCapabilities get() = delegate.capabilities
    override suspend fun testConnection(config: ProviderConfig, secret: String) = delegate.testConnection(config, secret)
    override suspend fun listModels(config: ProviderConfig, secret: String) = delegate.listModels(config, secret)
    override fun streamChat(request: ChatRequest, config: ProviderConfig, secret: String): Flow<AiStreamEvent> =
        delegate.streamChat(request, config, secret).onEach { event ->
            if (event is AiStreamEvent.UsageEvent) UsageLedger.record(config, request.model, event.usage)
        }
}
