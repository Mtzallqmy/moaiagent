package com.agentdroid.core.workspace

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.ToolRegistryException
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class WorkspaceLimits(
    val maxReadBytes: Long = 1L * 1024 * 1024,
    val maxSearchFileBytes: Long = 2L * 1024 * 1024,
    val maxSearchResults: Int = 200,
    val binaryProbeBytes: Int = 8 * 1024,
    val maxListResults: Int = 2_000
)

@Serializable
data class WorkspaceFileInfo(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val mime: String? = null,
    val binary: Boolean = false,
    val sha256: String? = null
)

@Serializable
data class ReadFileResult(
    val path: String,
    val content: String? = null,
    val startLine: Int = 1,
    val endLine: Int = 0,
    val totalLines: Int = 0,
    val truncated: Boolean = false,
    val binary: Boolean = false,
    val size: Long = 0,
    val mime: String? = null,
    val sha256: String? = null
)

@Serializable
data class SearchMatch(val path: String, val line: Int? = null, val snippet: String? = null, val fileNameMatch: Boolean = false)

class WorkspaceFileSystem(
    root: File,
    val limits: WorkspaceLimits = WorkspaceLimits()
) {
    private val root: File
    private val rootPrefix: String
    private val internalRoots = setOf(".workspace-trash", ".agentdroid")

    init {
        if (!root.exists() && !root.mkdirs()) throw ToolRegistryException(AgentError.io("Could not create workspace root ${root.path}"))
        this.root = root.canonicalFile
        rootPrefix = this.root.path.trimEnd(File.separatorChar) + File.separator
        File(this.root, ".workspace-trash").mkdirs()
        File(this.root, ".agentdroid").mkdirs()
    }

    fun rootPath(): String = root.path

    fun resolve(path: String, mustExist: Boolean = false): File = resolveInternal(path, mustExist, allowInternal = false)

    internal fun resolveInternal(path: String, mustExist: Boolean = false, allowInternal: Boolean = true): File {
        val normalized = sanitizeRelativePath(path)
        val firstSegment = normalized.substringBefore('/', normalized)
        if (!allowInternal && firstSegment in internalRoots) {
            throw ToolRegistryException(AgentError.workspaceViolation("Internal workspace path is reserved: $firstSegment"))
        }
        val candidate = if (normalized.isBlank()) root else File(root, normalized)
        val canonical = try { candidate.canonicalFile } catch (error: Throwable) {
            throw ToolRegistryException(AgentError.workspaceViolation("Cannot resolve path '$path': ${error.message}"))
        }
        if (canonical != root && !canonical.path.startsWith(rootPrefix)) {
            throw ToolRegistryException(AgentError.workspaceViolation("Path escapes workspace: $path"))
        }
        if (mustExist && !canonical.exists()) throw ToolRegistryException(AgentError.io("Path does not exist: $path"))
        return canonical
    }

    fun relative(file: File): String {
        val canonical = file.canonicalFile
        if (canonical != root && !canonical.path.startsWith(rootPrefix)) throw ToolRegistryException(AgentError.workspaceViolation("Path escapes workspace"))
        return canonical.relativeTo(root).invariantSeparatorsPath.takeUnless { it == "." }.orEmpty()
    }

    fun fileInfo(path: String): WorkspaceFileInfo {
        val file = resolve(path, mustExist = true)
        val directory = file.isDirectory
        return WorkspaceFileInfo(
            path = relative(file),
            name = file.name.ifBlank { "/" },
            directory = directory,
            size = if (directory) 0 else file.length(),
            modifiedAt = file.lastModified(),
            mime = if (directory) null else mime(file),
            binary = if (directory) false else isBinary(file),
            sha256 = if (directory) null else sha256(file)
        )
    }

    fun list(path: String = "", recursive: Boolean = false, maxDepth: Int = 8, maxResults: Int = limits.maxListResults): List<WorkspaceFileInfo> {
        val directory = resolve(path, mustExist = true)
        if (!directory.isDirectory) throw ToolRegistryException(AgentError.validation("list_files requires a directory"))
        val result = ArrayList<WorkspaceFileInfo>()
        fun walk(dir: File, depth: Int) {
            if (result.size >= maxResults || depth > maxDepth) return
            dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })?.forEach { child ->
                if (child.parentFile == root && child.name in internalRoots) return@forEach
                if (result.size >= maxResults) return
                result += WorkspaceFileInfo(
                    path = relative(child),
                    name = child.name,
                    directory = child.isDirectory,
                    size = if (child.isDirectory) 0 else child.length(),
                    modifiedAt = child.lastModified(),
                    mime = if (child.isDirectory) null else mime(child),
                    binary = if (child.isDirectory) false else isBinary(child)
                )
                if (recursive && child.isDirectory) walk(child, depth + 1)
            }
        }
        walk(directory, 1)
        return result
    }

    fun read(path: String, startLine: Int? = null, endLine: Int? = null): ReadFileResult {
        val file = resolve(path, mustExist = true)
        if (!file.isFile) throw ToolRegistryException(AgentError.validation("read_file requires a regular file"))
        val size = file.length()
        val detectedMime = mime(file)
        if (isBinary(file)) {
            return ReadFileResult(path = relative(file), binary = true, size = size, mime = detectedMime, sha256 = sha256(file))
        }
        if (size > limits.maxReadBytes) throw ToolRegistryException(AgentError.fileTooLarge(limits.maxReadBytes))
        val text = decodeUtf8(file.readBytes())
        val lines = text.split('\n')
        val total = lines.size
        val from = (startLine ?: 1).coerceAtLeast(1)
        val to = (endLine ?: total).coerceAtMost(total).coerceAtLeast(from - 1)
        val content = if (from > total || to < from) "" else lines.subList(from - 1, to).joinToString("\n")
        return ReadFileResult(
            path = relative(file),
            content = content,
            startLine = from,
            endLine = to,
            totalLines = total,
            truncated = from > 1 || to < total,
            binary = false,
            size = size,
            mime = detectedMime,
            sha256 = sha256(file)
        )
    }

    fun search(
        query: String,
        fileNameQuery: String? = null,
        glob: String? = null,
        caseSensitive: Boolean = false,
        maxResults: Int = limits.maxSearchResults
    ): List<SearchMatch> {
        val capped = maxResults.coerceIn(1, limits.maxSearchResults)
        val matcher = glob?.takeIf { it.isNotBlank() }?.let { pattern ->
            runCatching { FileSystems.getDefault().getPathMatcher("glob:$pattern") }.getOrElse {
                throw ToolRegistryException(AgentError.validation("Invalid glob pattern: $pattern"))
            }
        }
        val results = ArrayList<SearchMatch>()
        val needle = if (caseSensitive) query else query.lowercase()
        val fileNeedle = fileNameQuery?.let { if (caseSensitive) it else it.lowercase() }

        fun walk(dir: File) {
            if (results.size >= capped) return
            dir.listFiles()?.forEach { file ->
                if (file.parentFile == root && file.name in internalRoots) return@forEach
                if (results.size >= capped) return
                if (file.isDirectory) {
                    walk(file)
                    return@forEach
                }
                val relative = relative(file)
                if (matcher != null && !matcher.matches(java.nio.file.Paths.get(relative))) return@forEach
                val comparableName = if (caseSensitive) file.name else file.name.lowercase()
                if (!fileNeedle.isNullOrBlank() && comparableName.contains(fileNeedle)) {
                    results += SearchMatch(relative, fileNameMatch = true)
                    if (results.size >= capped) return@forEach
                }
                if (query.isBlank() || file.length() > limits.maxSearchFileBytes || isBinary(file)) return@forEach
                val text = runCatching { decodeUtf8(file.readBytes()) }.getOrNull() ?: return@forEach
                text.lineSequence().forEachIndexed { index, line ->
                    if (results.size >= capped) return@forEachIndexed
                    val haystack = if (caseSensitive) line else line.lowercase()
                    if (haystack.contains(needle)) results += SearchMatch(relative, index + 1, line.trim().take(320), false)
                }
            }
        }
        walk(root)
        return results
    }

    fun readTextForMutation(path: String): Pair<String, String> {
        val file = resolve(path, mustExist = true)
        if (!file.isFile) throw ToolRegistryException(AgentError.validation("Expected a regular file: $path"))
        if (isBinary(file)) throw ToolRegistryException(AgentError.binaryUnsupported())
        if (file.length() > limits.maxReadBytes) throw ToolRegistryException(AgentError.fileTooLarge(limits.maxReadBytes))
        return decodeUtf8(file.readBytes()) to sha256(file)
    }

    internal fun writeText(path: String, content: String, createParents: Boolean, overwrite: Boolean) {
        val file = resolve(path)
        if (file.exists() && file.isDirectory) throw ToolRegistryException(AgentError.validation("Destination is a directory: $path"))
        if (file.exists() && !overwrite) throw ToolRegistryException(AgentError.validation("Destination already exists and overwrite=false: $path"))
        val parent = file.parentFile ?: throw ToolRegistryException(AgentError.workspaceViolation("Invalid destination: $path"))
        if (!parent.exists()) {
            if (!createParents) throw ToolRegistryException(AgentError.io("Parent directory does not exist: ${relative(parent)}"))
            if (!parent.mkdirs()) throw ToolRegistryException(AgentError.io("Could not create parent directory"))
            resolve(relative(parent), mustExist = true)
        }
        atomicWrite(file, content.toByteArray(StandardCharsets.UTF_8))
    }

    internal fun createDirectory(path: String, createParents: Boolean) {
        val dir = resolve(path)
        if (dir.exists()) {
            if (!dir.isDirectory) throw ToolRegistryException(AgentError.validation("A file already exists at $path"))
            return
        }
        val ok = if (createParents) dir.mkdirs() else dir.mkdir()
        if (!ok) throw ToolRegistryException(AgentError.io("Could not create directory: $path"))
        resolve(path, mustExist = true)
    }

    internal fun move(source: String, destination: String, overwrite: Boolean = false) {
        val src = resolve(source, mustExist = true)
        if (src == root) throw ToolRegistryException(AgentError.workspaceViolation("Cannot move workspace root"))
        val dst = resolve(destination)
        if (dst.exists() && !overwrite) throw ToolRegistryException(AgentError.validation("Destination already exists: $destination"))
        dst.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw ToolRegistryException(AgentError.io("Could not create destination parent")) }
        try {
            Files.move(src.toPath(), dst.toPath(), *(if (overwrite) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()))
        } catch (error: Throwable) {
            throw ToolRegistryException(AgentError.io("Move failed: ${error.message}"))
        }
    }

    internal fun deleteRecursively(path: String) {
        val target = resolve(path, mustExist = true)
        if (target == root) throw ToolRegistryException(AgentError.workspaceViolation("Cannot delete workspace root"))
        if (!target.deleteRecursively()) throw ToolRegistryException(AgentError.io("Could not delete $path"))
    }

    internal fun moveToTrash(path: String, changeSetId: String): String {
        val source = resolve(path, mustExist = true)
        if (source == root) throw ToolRegistryException(AgentError.workspaceViolation("Cannot trash workspace root"))
        val trashBase = resolveInternal(".workspace-trash/$changeSetId", allowInternal = true)
        if (!trashBase.exists() && !trashBase.mkdirs()) throw ToolRegistryException(AgentError.io("Could not create workspace trash"))
        val destination = File(trashBase, source.name)
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return relativeInternal(destination)
    }

    internal fun restoreFromTrash(trashPath: String, destinationPath: String) {
        val trash = resolveInternal(trashPath, mustExist = true, allowInternal = true)
        val destination = resolve(destinationPath)
        if (destination.exists()) throw ToolRegistryException(AgentError.patchConflict("Cannot restore because destination exists: $destinationPath"))
        destination.parentFile?.mkdirs()
        Files.move(trash.toPath(), destination.toPath())
    }

    fun sha256(path: String): String = sha256(resolve(path, mustExist = true))

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun relativeInternal(file: File): String = file.canonicalFile.relativeTo(root).invariantSeparatorsPath

    private fun sanitizeRelativePath(path: String): String {
        val raw = path.trim().replace('\\', '/')
        if (raw.isBlank() || raw == ".") return ""
        if (raw.indexOf('\u0000') >= 0) throw ToolRegistryException(AgentError.workspaceViolation("NUL byte in path"))
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)) throw ToolRegistryException(AgentError.workspaceViolation("URI schemes are not allowed in workspace paths"))
        if (raw.startsWith('/') || File(raw).isAbsolute) throw ToolRegistryException(AgentError.workspaceViolation("Absolute paths are not allowed"))
        val segments = raw.split('/').filter { it.isNotBlank() && it != "." }
        if (segments.any { it == ".." }) throw ToolRegistryException(AgentError.workspaceViolation("Path traversal is not allowed"))
        return segments.joinToString("/")
    }

    private fun isBinary(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val probe = ByteArray(minOf(limits.binaryProbeBytes.toLong(), file.length()).toInt())
        val read = file.inputStream().use { it.read(probe) }.coerceAtLeast(0)
        if (read == 0) return false
        var suspicious = 0
        for (index in 0 until read) {
            val value = probe[index].toInt() and 0xff
            if (value == 0) return true
            if (value < 0x09 || value in 0x0E..0x1F) suspicious++
        }
        if (suspicious.toDouble() / read > 0.10) return true
        return runCatching { decodeUtf8(probe.copyOf(read)); false }.getOrDefault(true)
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (error: Throwable) {
            throw ToolRegistryException(AgentError.binaryUnsupported())
        }
    }

    private fun mime(file: File): String? = URLConnection.guessContentTypeFromName(file.name)

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val temp = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            temp.delete()
            throw ToolRegistryException(AgentError.io("Atomic write failed: ${error.message}"))
        }
    }
}
