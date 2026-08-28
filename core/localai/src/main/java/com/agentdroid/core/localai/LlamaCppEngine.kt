package com.agentdroid.core.localai

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class LlamaCppEngine(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : LocalModelEngine {
    override val backend = LocalModelBackend.LLAMA_CPP
    override val displayName = "llama.cpp"
    override val supportedExtensions = setOf("gguf")

    override suspend fun inspect(modelFile: File): Result<Pair<LocalModelCompatibility, LocalModelMetadata>> = withContext(Dispatchers.IO) {
        runCatching {
            require(modelFile.isFile && modelFile.canRead()) { "Model file is not readable" }
            require(modelFile.extension.equals("gguf", ignoreCase = true)) { "llama.cpp requires a GGUF model" }
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val supported = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
            val compatibility = LocalModelCompatibility(
                supported = supported,
                reason = if (supported) null else "This build supports llama.cpp on arm64-v8a and x86_64 only (device ABI: $abi)",
                supportedAbis = listOf("arm64-v8a", "x86_64")
            )
            if (!supported) return@runCatching compatibility to LocalModelMetadata(backendVersion = LlamaNative.version())
            val info = json.decodeFromString<NativeModelInfo>(LlamaNative.inspect(modelFile.absolutePath))
            compatibility to LocalModelMetadata(
                architecture = info.architecture,
                description = info.description,
                contextSize = info.contextSize,
                parameterCount = info.parameterCount,
                tensorBytes = info.tensorBytes,
                quantization = info.quantization,
                backendVersion = LlamaNative.version(),
                supportsToolCalling = false
            )
        }
    }

    override suspend fun load(
        descriptor: LocalModelDescriptor,
        modelFile: File,
        config: LocalModelLoadConfig
    ): Result<LocalModelSession> = withContext(Dispatchers.IO) {
        runCatching {
            require(descriptor.compatibility.supported) { descriptor.compatibility.reason ?: "Model is not compatible" }
            val handle = LlamaNative.open(modelFile.absolutePath, config.contextSize, config.threads)
            require(handle != 0L) { "llama.cpp could not load the model" }
            LlamaCppSession(descriptor.copy(loaded = true), handle)
        }
    }

    @Serializable
    private data class NativeModelInfo(
        val architecture: String? = null,
        val description: String? = null,
        val contextSize: Int? = null,
        val parameterCount: Long? = null,
        val tensorBytes: Long? = null,
        val quantization: String? = null
    )
}

private class LlamaCppSession(
    override val model: LocalModelDescriptor,
    private val handle: Long
) : LocalModelSession {
    private val closed = AtomicBoolean(false)
    private val _isGenerating = MutableStateFlow(false)
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    override fun generate(prompt: String, config: LocalGenerationConfig): Flow<LocalGenerationEvent> = callbackFlow {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        check(!closed.get()) { "Local model session is closed" }
        if (!_isGenerating.compareAndSet(false, true)) {
            trySend(LocalGenerationEvent.Error("A generation is already running"))
            close()
            return@callbackFlow
        }
        trySend(LocalGenerationEvent.Started)
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val result = runCatching {
                LlamaNative.generate(handle, prompt, config.temperature, config.maxTokens, object : NativeTokenCallback {
                    override fun onToken(text: String) { if (text.isNotEmpty()) trySend(LocalGenerationEvent.Token(text)) }
                })
            }
            _isGenerating.value = false
            result.onSuccess { trySend(LocalGenerationEvent.Completed) }
                .onFailure { trySend(LocalGenerationEvent.Error(it.message ?: "Local inference failed")) }
            close()
        }
        awaitClose {
            if (worker.isActive) LlamaNative.stop(handle)
            worker.cancel()
            _isGenerating.value = false
        }
    }

    override fun stop() {
        if (!closed.get()) LlamaNative.stop(handle)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            LlamaNative.stop(handle)
            LlamaNative.close(handle)
            _isGenerating.value = false
        }
    }
}

private interface NativeTokenCallback { fun onToken(text: String) }

private object LlamaNative {
    init { System.loadLibrary("agentdroid_llama") }
    external fun version(): String
    external fun inspect(path: String): String
    external fun open(path: String, contextSize: Int, threads: Int): Long
    external fun generate(handle: Long, prompt: String, temperature: Float, maxTokens: Int, callback: NativeTokenCallback)
    external fun stop(handle: Long)
    external fun close(handle: Long)
}

private fun MutableStateFlow<Boolean>.compareAndSet(expect: Boolean, update: Boolean): Boolean = synchronized(this) {
    if (value != expect) false else { value = update; true }
}
