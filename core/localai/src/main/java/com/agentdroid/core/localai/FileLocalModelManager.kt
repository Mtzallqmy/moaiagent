package com.agentdroid.core.localai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class FileLocalModelManager(
    rootDirectory: File,
    engines: List<LocalModelEngine>,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
) : LocalModelManager {
    private val root = rootDirectory.apply { mkdirs() }
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val indexFile = File(root, "models.json")
    private val enginesByBackend = engines.associateBy { it.backend }
    private val lock = Mutex()
    private val sessions = mutableMapOf<String, LocalModelSession>()
    private val _models = MutableStateFlow(readIndex())
    override val models: StateFlow<List<LocalModelDescriptor>> = _models.asStateFlow()
    private val _loadedModelId = MutableStateFlow<String?>(null)
    override val loadedModelId: StateFlow<String?> = _loadedModelId.asStateFlow()

    override suspend fun importModel(
        displayName: String,
        fileName: String,
        source: InputStream,
        backendHint: LocalModelBackend?
    ): Result<LocalModelDescriptor> = withContext(Dispatchers.IO) {
        runCatching {
            require(displayName.isNotBlank()) { "Model name must not be blank" }
            val cleanName = File(fileName).name
            require(cleanName == fileName && cleanName.isNotBlank()) { "Invalid model file name" }
            val extension = cleanName.substringAfterLast('.', "").lowercase()
            val engine = backendHint?.let(enginesByBackend::get)
                ?: enginesByBackend.values.firstOrNull { extension in it.supportedExtensions }
                ?: throw IllegalArgumentException("No local backend supports .$extension")
            require(extension in engine.supportedExtensions) { "${engine.displayName} does not support .$extension" }

            val id = UUID.randomUUID().toString()
            val target = File(filesDir, "$id.$extension")
            val staging = File(filesDir, ".$id.part")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            source.use { input ->
                staging.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        size += read
                        require(size <= MAX_MODEL_BYTES) { "Model exceeds the ${MAX_MODEL_BYTES / (1024 * 1024 * 1024)} GiB import limit" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(size > 0) { "Model file is empty" }
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = false)
                check(staging.delete()) { "Could not finalize model import" }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val inspected = engine.inspect(target).getOrElse { failure ->
                target.delete()
                throw failure
            }
            val descriptor = LocalModelDescriptor(
                id = id,
                displayName = displayName.trim().take(200),
                fileName = cleanName,
                backend = engine.backend,
                sizeBytes = size,
                sha256 = sha256,
                importedAt = System.currentTimeMillis(),
                compatibility = inspected.first,
                metadata = inspected.second,
                isDefault = _models.value.isEmpty()
            )
            lock.withLock {
                _models.value = _models.value + descriptor
                persistIndex()
            }
            descriptor
        }.also { stagingCleanup(filesDir) }
    }

    override suspend fun delete(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            unload(modelId).getOrThrow()
            lock.withLock {
                val descriptor = requireModel(modelId)
                val file = modelFile(descriptor)
                require(!file.exists() || file.delete()) { "Could not delete model file" }
                val remaining = _models.value.filterNot { it.id == modelId }
                _models.value = if (remaining.isNotEmpty() && descriptor.isDefault) {
                    remaining.mapIndexed { index, item -> item.copy(isDefault = index == 0) }
                } else remaining
                persistIndex()
            }
        }
    }

    override suspend fun load(modelId: String, config: LocalModelLoadConfig): Result<LocalModelSession> = withContext(Dispatchers.IO) {
        runCatching {
            sessions[modelId]?.let { return@runCatching it }
            val descriptor = requireModel(modelId)
            require(descriptor.compatibility.supported) { descriptor.compatibility.reason ?: "Model is not supported" }
            val engine = checkNotNull(enginesByBackend[descriptor.backend]) { "Backend ${descriptor.backend} is unavailable" }
            val session = engine.load(descriptor, modelFile(descriptor), config).getOrThrow()
            lock.withLock {
                sessions[modelId] = session
                _loadedModelId.value = modelId
                _models.value = _models.value.map { if (it.id == modelId) it.copy(loaded = true) else it.copy(loaded = false) }
                persistIndex()
            }
            session
        }
    }

    override suspend fun unload(modelId: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            lock.withLock {
                val ids = if (modelId == null) sessions.keys.toList() else listOf(modelId)
                ids.forEach { id -> sessions.remove(id)?.close() }
                if (modelId == null || _loadedModelId.value == modelId) _loadedModelId.value = null
                _models.value = _models.value.map { item -> if (modelId == null || item.id == modelId) item.copy(loaded = false) else item }
                persistIndex()
            }
        }
    }

    override suspend fun setDefault(modelId: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            lock.withLock {
                if (modelId != null) requireModel(modelId)
                _models.value = _models.value.map { it.copy(isDefault = it.id == modelId) }
                persistIndex()
            }
        }
    }

    override fun defaultModel(): LocalModelDescriptor? = _models.value.firstOrNull { it.isDefault }
    override fun get(modelId: String): LocalModelDescriptor? = _models.value.firstOrNull { it.id == modelId }

    private fun requireModel(id: String) = get(id) ?: throw IllegalArgumentException("Unknown local model")
    private fun modelFile(model: LocalModelDescriptor) = File(filesDir, "${model.id}.${model.fileName.substringAfterLast('.', "")}")

    private fun readIndex(): List<LocalModelDescriptor> = runCatching {
        if (!indexFile.exists()) emptyList() else json.decodeFromString<Index>(indexFile.readText()).models.map { it.copy(loaded = false) }
    }.getOrDefault(emptyList())

    private fun persistIndex() {
        val tmp = File(root, ".models.json.tmp")
        tmp.writeText(json.encodeToString(Index(_models.value.map { it.copy(loaded = false) })))
        if (indexFile.exists()) require(indexFile.delete()) { "Could not replace local model index" }
        require(tmp.renameTo(indexFile)) { "Could not commit local model index" }
    }

    @Serializable private data class Index(val models: List<LocalModelDescriptor> = emptyList())

    companion object {
        private const val MAX_MODEL_BYTES = 16L * 1024 * 1024 * 1024
        private fun stagingCleanup(directory: File) { directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach(File::delete) }
    }
}
