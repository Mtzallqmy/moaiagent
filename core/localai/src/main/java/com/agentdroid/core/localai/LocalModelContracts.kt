package com.agentdroid.core.localai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.InputStream

@Serializable
enum class LocalModelBackend { LLAMA_CPP, LITERT_LM }

@Serializable
data class LocalModelCompatibility(
    val supported: Boolean,
    val reason: String? = null,
    val supportedAbis: List<String> = emptyList()
)

@Serializable
data class LocalModelMetadata(
    val architecture: String? = null,
    val description: String? = null,
    val contextSize: Int? = null,
    val parameterCount: Long? = null,
    val tensorBytes: Long? = null,
    val quantization: String? = null,
    val backendVersion: String? = null,
    val supportsToolCalling: Boolean = false
)

@Serializable
data class LocalModelDescriptor(
    val id: String,
    val displayName: String,
    val fileName: String,
    val backend: LocalModelBackend,
    val sizeBytes: Long,
    val sha256: String,
    val importedAt: Long,
    val compatibility: LocalModelCompatibility,
    val metadata: LocalModelMetadata = LocalModelMetadata(),
    val loaded: Boolean = false,
    val isDefault: Boolean = false
)

@Serializable
data class LocalModelLoadConfig(
    val contextSize: Int = 4096,
    val threads: Int = 4
) {
    init {
        require(contextSize in 256..131_072)
        require(threads in 1..32)
    }
}

@Serializable
data class LocalGenerationConfig(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512
) {
    init {
        require(temperature in 0f..2f)
        require(maxTokens in 1..8192)
    }
}

sealed interface LocalGenerationEvent {
    data object Started : LocalGenerationEvent
    data class Token(val text: String) : LocalGenerationEvent
    data class Error(val message: String) : LocalGenerationEvent
    data object Completed : LocalGenerationEvent
}

interface LocalModelSession : Closeable {
    val model: LocalModelDescriptor
    val isGenerating: StateFlow<Boolean>
    fun generate(prompt: String, config: LocalGenerationConfig = LocalGenerationConfig()): Flow<LocalGenerationEvent>
    fun stop()
    override fun close()
}

interface LocalModelEngine {
    val backend: LocalModelBackend
    val displayName: String
    val supportedExtensions: Set<String>
    suspend fun inspect(modelFile: java.io.File): Result<Pair<LocalModelCompatibility, LocalModelMetadata>>
    suspend fun load(
        descriptor: LocalModelDescriptor,
        modelFile: java.io.File,
        config: LocalModelLoadConfig = LocalModelLoadConfig()
    ): Result<LocalModelSession>
}

interface LocalModelManager {
    val models: StateFlow<List<LocalModelDescriptor>>
    val loadedModelId: StateFlow<String?>
    suspend fun importModel(
        displayName: String,
        fileName: String,
        source: InputStream,
        backendHint: LocalModelBackend? = null
    ): Result<LocalModelDescriptor>
    suspend fun delete(modelId: String): Result<Unit>
    suspend fun load(modelId: String, config: LocalModelLoadConfig = LocalModelLoadConfig()): Result<LocalModelSession>
    suspend fun unload(modelId: String? = null): Result<Unit>
    suspend fun setDefault(modelId: String?): Result<Unit>
    fun defaultModel(): LocalModelDescriptor?
    fun get(modelId: String): LocalModelDescriptor?
}
