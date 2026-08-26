package com.agentdroid.core.ai.providers

import com.agentdroid.core.ai.AiProvider
import com.agentdroid.core.ai.ProviderTestResult
import com.agentdroid.core.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeAiProvider : AiProvider {
    override val kind = ProviderKind.FAKE
    override val displayName = "Fake Provider"
    override val capabilities = ProviderCapabilities(modelListing = true, streaming = true)
    override suspend fun testConnection(config: ProviderConfig, secret: String) = ProviderTestResult(true, displayName, 1, 5, true)
    override suspend fun listModels(config: ProviderConfig, secret: String) = Result.success(listOf(AiModel("fake-model", "Fake model", capabilities)))
    override fun streamChat(request: ChatRequest, config: ProviderConfig, secret: String): Flow<AiStreamEvent> = flow {
        emit(AiStreamEvent.Started)
        "This is a deterministic local test response. دعم العربية وEnglish.".forEach { character ->
            delay(4)
            emit(AiStreamEvent.TextDelta(character.toString()))
        }
        emit(AiStreamEvent.Completed)
    }
}
