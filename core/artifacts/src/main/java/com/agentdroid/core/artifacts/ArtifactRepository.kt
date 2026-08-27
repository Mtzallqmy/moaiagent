package com.agentdroid.core.artifacts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

fun interface ArtifactWorkspaceProvider {
    fun root(workspaceId: String): File
}

interface ArtifactRepository {
    suspend fun create(request: CreateArtifactRequest): Artifact
    suspend fun addScreenshotReference(request: ScreenshotReferenceRequest): Artifact
    suspend fun get(workspaceId: String, id: String): Artifact
    suspend fun list(filter: ArtifactListFilter): List<Artifact>
    suspend fun read(workspaceId: String, id: String, maxBytes: Int = DEFAULT_READ_LIMIT): ArtifactReadResult
    suspend fun update(workspaceId: String, id: String, request: UpdateArtifactRequest): Artifact
    suspend fun rename(workspaceId: String, id: String, title: String, preferredFileName: String? = null): Artifact
    suspend fun copy(workspaceId: String, id: String, title: String? = null): Artifact
    suspend fun delete(workspaceId: String, id: String): Artifact
    suspend fun export(workspaceId: String, id: String, destinationRelativePath: String, overwrite: Boolean = false): String

    companion object { const val DEFAULT_READ_LIMIT = 1_048_576 }
}

class FileArtifactRepository(
    private val workspaces: ArtifactWorkspaceProvider,
    private val citations: CitationValidator = CitationValidator.REJECT_ALL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxArtifactBytes: Long = 8L * 1024 * 1024
) : ArtifactRepository {
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun create(request: CreateArtifactRequest): Artifact = mutex.withLock {
        requireTextual(request.type)
        validateIdentity(request.workspaceId, request.conversationId, request.title)
        validateContent(request.type, request.content)
        citations.validate(request.sourceReferences)
        val root = workspaceRoot(request.workspaceId)
        val id = checkedId(newId())
        if (loadIndex(root).artifacts.any { it.id == id }) throw ArtifactWriteError("Artifact id already exists")
        val directory = managedDirectory(root)
        val filename = uniqueFileName(directory, request.preferredFileName ?: request.title, request.type, id)
        val target = safeResolve(root, "Artifacts/$filename", internal = false)
        val bytes = request.content.toByteArray(StandardCharsets.UTF_8)
        checkSize(bytes.size.toLong())
        atomicWrite(target, bytes)
        val now = clock()
        val artifact = Artifact(
            id = id,
            taskId = request.taskId?.takeIf(String::isNotBlank),
            conversationId = request.conversationId,
            workspaceId = request.workspaceId,
            type = request.type,
            title = request.title.trim(),
            filePath = relative(root, target),
            mimeType = request.mimeType?.trim()?.takeIf(String::isNotBlank) ?: request.type.defaultMimeType,
            createdAt = now,
            updatedAt = now,
            sourceReferences = request.sourceReferences,
            sizeBytes = bytes.size.toLong()
        )
        try {
            updateIndex(root) { it + artifact }
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
        artifact
    }

    override suspend fun addScreenshotReference(request: ScreenshotReferenceRequest): Artifact = mutex.withLock {
        validateIdentity(request.workspaceId, request.conversationId, request.title)
        require(request.mimeType.lowercase().startsWith("image/")) { "Screenshot mimeType must be an image type" }
        citations.validate(request.sourceReferences)
        val root = workspaceRoot(request.workspaceId)
        val screenshot = safeResolve(root, request.workspaceRelativePath, mustExist = true, internal = false)
        if (!screenshot.isFile) throw UnsafeArtifactPath("Screenshot reference must point to a regular workspace file")
        val now = clock()
        val id = checkedId(newId())
        if (loadIndex(root).artifacts.any { it.id == id }) throw ArtifactWriteError("Artifact id already exists")
        val artifact = Artifact(
            id = id, taskId = request.taskId?.takeIf(String::isNotBlank),
            conversationId = request.conversationId, workspaceId = request.workspaceId,
            type = ArtifactType.SCREENSHOT, title = request.title.trim(), filePath = relative(root, screenshot),
            mimeType = request.mimeType, createdAt = now, updatedAt = now,
            sourceReferences = request.sourceReferences, storage = ArtifactStorage.EXTERNAL_REFERENCE,
            sizeBytes = screenshot.length()
        )
        updateIndex(root) { it + artifact }
        artifact
    }

    override suspend fun get(workspaceId: String, id: String): Artifact = mutex.withLock {
        find(workspaceRoot(workspaceId), workspaceId, id)
    }

    override suspend fun list(filter: ArtifactListFilter): List<Artifact> = mutex.withLock {
        loadIndex(workspaceRoot(filter.workspaceId)).artifacts.asSequence()
            .filter { it.workspaceId == filter.workspaceId }
            .filter { filter.conversationId == null || it.conversationId == filter.conversationId }
            .filter { filter.taskId == null || it.taskId == filter.taskId }
            .filter { filter.type == null || it.type == filter.type }
            .sortedByDescending { it.updatedAt }.toList()
    }

    override suspend fun read(workspaceId: String, id: String, maxBytes: Int): ArtifactReadResult = mutex.withLock {
        require(maxBytes in 1..ArtifactRepository.DEFAULT_READ_LIMIT) { "maxBytes must be between 1 and ${ArtifactRepository.DEFAULT_READ_LIMIT}" }
        val root = workspaceRoot(workspaceId)
        val artifact = find(root, workspaceId, id)
        if (!artifact.type.textual) throw ArtifactException("Binary artifacts are exposed by file reference, not injected as text")
        val file = artifactFile(root, artifact, mustExist = true)
        if (!file.isFile) throw ArtifactNotFound(id)
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream((maxBytes + 1).coerceAtMost(16 * 1024))
            val buffer = ByteArray(8 * 1024)
            var remaining = maxBytes + 1
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.toByteArray()
        }
        val truncated = bytes.size > maxBytes
        val readable = if (truncated) bytes.copyOf(maxBytes) else bytes
        ArtifactReadResult(artifact, readable.toString(StandardCharsets.UTF_8), truncated)
    }

    override suspend fun update(workspaceId: String, id: String, request: UpdateArtifactRequest): Artifact = mutex.withLock {
        val root = workspaceRoot(workspaceId)
        val current = find(root, workspaceId, id)
        if (current.storage == ArtifactStorage.EXTERNAL_REFERENCE && (request.content != null || request.preferredFileName != null)) {
            throw ArtifactWriteError("Referenced screenshots cannot be overwritten or renamed by the artifact repository")
        }
        val title = request.title?.trim()?.also { require(it.isNotBlank()) { "title must not be blank" } } ?: current.title
        val references = request.sourceReferences ?: current.sourceReferences
        citations.validate(references)
        var path = current.filePath
        if (request.preferredFileName != null && current.storage == ArtifactStorage.MANAGED_FILE) {
            val source = artifactFile(root, current, mustExist = true)
            val destinationName = uniqueFileName(managedDirectory(root), request.preferredFileName, current.type, current.id, source.name)
            val destination = safeResolve(root, "Artifacts/$destinationName", internal = false)
            move(source, destination)
            path = relative(root, destination)
        }
        val target = artifactFile(root, current.copy(filePath = path), mustExist = true)
        if (request.content != null) {
            requireTextual(current.type)
            validateContent(current.type, request.content)
            val bytes = request.content.toByteArray(StandardCharsets.UTF_8)
            checkSize(bytes.size.toLong())
            atomicWrite(target, bytes)
        }
        val updated = current.copy(
            title = title, filePath = path, sourceReferences = references, updatedAt = clock(), sizeBytes = target.length()
        )
        updateIndex(root) { entries -> entries.map { if (it.id == id) updated else it } }
        updated
    }

    override suspend fun rename(workspaceId: String, id: String, title: String, preferredFileName: String?): Artifact =
        update(workspaceId, id, UpdateArtifactRequest(title = title, preferredFileName = preferredFileName))

    override suspend fun copy(workspaceId: String, id: String, title: String?): Artifact {
        val original = get(workspaceId, id)
        if (!original.type.textual) throw ArtifactWriteError("Screenshot references are not copied as text artifacts")
        val contents = read(workspaceId, id).content
        return create(CreateArtifactRequest(
            workspaceId = workspaceId, conversationId = original.conversationId, taskId = original.taskId,
            type = original.type, title = title?.takeIf(String::isNotBlank) ?: "${original.title} copy",
            content = contents, sourceReferences = original.sourceReferences, mimeType = original.mimeType
        ))
    }

    override suspend fun delete(workspaceId: String, id: String): Artifact = mutex.withLock {
        val root = workspaceRoot(workspaceId)
        val artifact = find(root, workspaceId, id)
        if (artifact.storage == ArtifactStorage.MANAGED_FILE) {
            val file = artifactFile(root, artifact, mustExist = true)
            if (!file.delete()) throw ArtifactWriteError("Could not delete artifact file")
        }
        updateIndex(root) { entries -> entries.filterNot { it.id == id } }
        artifact
    }

    override suspend fun export(workspaceId: String, id: String, destinationRelativePath: String, overwrite: Boolean): String = mutex.withLock {
        val root = workspaceRoot(workspaceId)
        val artifact = find(root, workspaceId, id)
        val source = artifactFile(root, artifact, mustExist = true)
        val destination = safeResolve(root, destinationRelativePath, internal = false)
        if (source == destination) throw UnsafeArtifactPath("Export destination must differ from the artifact file")
        if (destination.exists() && !overwrite) throw ArtifactWriteError("Export destination already exists")
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) throw ArtifactWriteError("Could not create export directory")
            safeResolve(root, relative(root, parent), mustExist = true, internal = false)
        }
        if (destination.exists() && destination.isDirectory) throw ArtifactWriteError("Export destination is a directory")
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        relative(root, destination)
    }

    private fun workspaceRoot(workspaceId: String): File {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        val supplied = workspaces.root(workspaceId)
        if (!supplied.exists() && !supplied.mkdirs()) throw ArtifactWriteError("Could not create workspace root")
        if (!supplied.isDirectory) throw ArtifactWriteError("Workspace root is not a directory")
        return supplied.canonicalFile
    }

    private fun managedDirectory(root: File): File = safeResolve(root, "Artifacts", internal = false).also {
        if (!it.exists() && !it.mkdirs()) throw ArtifactWriteError("Could not create Artifacts directory")
    }

    private fun find(root: File, workspaceId: String, id: String): Artifact =
        loadIndex(root).artifacts.firstOrNull { it.workspaceId == workspaceId && it.id == id } ?: throw ArtifactNotFound(id)

    private fun updateIndex(root: File, transform: (List<Artifact>) -> List<Artifact>) {
        val next = ArtifactIndex(transform(loadIndex(root).artifacts))
        atomicWrite(indexFile(root), json.encodeToString(next).toByteArray(StandardCharsets.UTF_8))
    }

    private fun loadIndex(root: File): ArtifactIndex {
        val file = indexFile(root)
        if (!file.exists()) return ArtifactIndex()
        return runCatching { json.decodeFromString<ArtifactIndex>(file.readText(StandardCharsets.UTF_8)) }
            .getOrElse { throw ArtifactWriteError("Artifact index is corrupt", it) }
    }

    private fun indexFile(root: File): File = safeResolve(root, ".agentdroid/artifacts-v1.json", internal = true).also {
        val parent = it.parentFile
        if (!parent.exists() && !parent.mkdirs()) throw ArtifactWriteError("Could not create artifact metadata directory")
    }

    private fun artifactFile(root: File, artifact: Artifact, mustExist: Boolean): File {
        if (artifact.storage == ArtifactStorage.MANAGED_FILE) {
            val normalized = artifact.filePath.replace('\\', '/')
            if (!normalized.startsWith("Artifacts/") || normalized.removePrefix("Artifacts/").contains('/')) {
                throw UnsafeArtifactPath("Managed artifact metadata points outside the Artifacts directory")
            }
        }
        return safeResolve(root, artifact.filePath, mustExist = mustExist, internal = false)
    }

    private fun safeResolve(root: File, relativePath: String, mustExist: Boolean = false, internal: Boolean): File {
        if (relativePath.isBlank() || File(relativePath).isAbsolute || relativePath.indexOf('\u0000') >= 0) {
            throw UnsafeArtifactPath("Artifact path must be a non-empty workspace-relative path")
        }
        val normalized = relativePath.replace('\\', '/').split('/').filter { it.isNotBlank() }
        if (normalized.any { it == "." || it == ".." }) throw UnsafeArtifactPath("Artifact path contains unsafe traversal")
        if (!internal && normalized.firstOrNull() in setOf(".agentdroid", ".workspace-trash")) {
            throw UnsafeArtifactPath("Internal workspace locations are reserved")
        }
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        val candidate = runCatching { File(root, normalized.joinToString(File.separator)).canonicalFile }
            .getOrElse { throw UnsafeArtifactPath("Artifact path cannot be resolved") }
        if (candidate != root && !candidate.path.startsWith(rootPrefix)) throw UnsafeArtifactPath("Artifact path escapes workspace")
        if (mustExist && !candidate.exists()) throw ArtifactWriteError("Artifact file does not exist")
        return candidate
    }

    private fun relative(root: File, file: File): String {
        val canonical = file.canonicalFile
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        if (!canonical.path.startsWith(rootPrefix)) throw UnsafeArtifactPath("Artifact path escapes workspace")
        return canonical.relativeTo(root).invariantSeparatorsPath
    }

    private fun uniqueFileName(directory: File, input: String, type: ArtifactType, id: String, currentName: String? = null): String {
        val raw = input.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.', input.substringAfterLast('/').substringAfterLast('\\'))
        val stem = raw.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('.', '-', '_').take(80).ifBlank { "artifact" }
        val base = if (stem.uppercase() in WINDOWS_RESERVED) "artifact-$stem" else stem
        var candidate = "$base.${type.defaultExtension}"
        if (candidate != currentName && File(directory, candidate).exists()) candidate = "$base-${id.take(8)}.${type.defaultExtension}"
        return candidate
    }

    private fun checkedId(value: String): String = value.also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Artifact id generator returned an unsafe id" }
    }

    private fun validateIdentity(workspaceId: String, conversationId: String, title: String) {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(title.length <= 240) { "title is too long" }
    }

    private fun requireTextual(type: ArtifactType) {
        if (!type.textual) throw ArtifactWriteError("Use addScreenshotReference for screenshot artifacts")
    }

    private fun validateContent(type: ArtifactType, content: String) {
        checkSize(content.toByteArray(StandardCharsets.UTF_8).size.toLong())
        if (type == ArtifactType.JSON) runCatching { json.parseToJsonElement(content) }
            .getOrElse { throw ArtifactWriteError("JSON artifact content is invalid", it) }
    }

    private fun checkSize(size: Long) {
        if (size > maxArtifactBytes) throw ArtifactWriteError("Artifact exceeds the $maxArtifactBytes byte limit")
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw ArtifactWriteError("Artifact target has no parent")
        if (!parent.exists() && !parent.mkdirs()) throw ArtifactWriteError("Could not create artifact directory")
        val temp = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.outputStream().use { output -> output.write(bytes); output.flush(); output.fd.sync() }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            temp.delete()
            throw ArtifactWriteError("Could not write artifact", failure)
        }
    }

    private fun move(source: File, destination: File) {
        try {
            try {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), destination.toPath())
            }
        } catch (failure: Throwable) {
            throw ArtifactWriteError("Could not rename artifact", failure)
        }
    }

    @Serializable private data class ArtifactIndex(val artifacts: List<Artifact> = emptyList())

    private companion object {
        val WINDOWS_RESERVED = setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")
    }
}
