package com.agentdroid.core.workspace

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.AgentTool
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCategory
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolDefinition
import com.agentdroid.core.agent.ToolPreview
import com.agentdroid.core.agent.ToolRegistry
import com.agentdroid.core.agent.ToolRegistryException
import com.agentdroid.core.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

interface WorkspaceServices {
    fun fileSystem(workspaceId: String): WorkspaceFileSystem
    fun changeSets(workspaceId: String): ChangeSetManager
}

class StaticWorkspaceServices(
    private val workspaceId: String,
    private val fileSystem: WorkspaceFileSystem,
    private val changeSetManager: ChangeSetManager
) : WorkspaceServices {
    override fun fileSystem(workspaceId: String): WorkspaceFileSystem {
        require(workspaceId == this.workspaceId) { "Unknown workspace $workspaceId" }
        return fileSystem
    }

    override fun changeSets(workspaceId: String): ChangeSetManager {
        require(workspaceId == this.workspaceId) { "Unknown workspace $workspaceId" }
        return changeSetManager
    }
}

fun createWorkspaceToolRegistry(services: WorkspaceServices, diffEngine: DiffEngine = DiffEngine()): ToolRegistry = ToolRegistry(
    listOf(
        ReadFileTool(services),
        ListFilesTool(services),
        SearchFilesTool(services),
        FileInfoTool(services),
        WriteFileTool(services, diffEngine),
        PatchFileTool(services, diffEngine),
        MoveFileTool(services),
        DeleteFileTool(services),
        CreateDirectoryTool(services)
    )
)

private fun objectSchema(properties: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("properties", JsonObject(properties))
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
}

private fun field(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type)
    put("description", description)
}

private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull
    ?: throw ToolRegistryException(AgentError.validation("Missing '$name'"))
private fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(name: String, default: Boolean = false): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: default
private fun JsonObject.int(name: String, default: Int): Int = this[name]?.jsonPrimitive?.intOrNull ?: default

private abstract class WorkspaceTool(protected val services: WorkspaceServices) : AgentTool {
    protected fun fs(context: ToolContext) = services.fileSystem(context.workspaceId)
    protected fun changes(context: ToolContext) = services.changeSets(context.workspaceId)
}

private class ReadFileTool(services: WorkspaceServices) : WorkspaceTool(services) {
    override val definition = ToolDefinition(
        "read_file",
        "Read UTF-8 text from a workspace file. Binary files return metadata only. Supports an optional inclusive line range.",
        objectSchema(
            mapOf(
                "path" to field("string", "Workspace-relative file path"),
                "startLine" to field("integer", "1-based first line"),
                "endLine" to field("integer", "1-based inclusive last line")
            ),
            listOf("path")
        ),
        RiskLevel.SAFE,
        ToolCategory.FILE_READ
    )

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val result = fs(context).read(input.string("path"), input["startLine"]?.jsonPrimitive?.intOrNull, input["endLine"]?.jsonPrimitive?.intOrNull)
        return ToolResult.success(
            if (result.binary) "Read binary metadata for ${result.path}" else "Read ${result.path} lines ${result.startLine}-${result.endLine}",
            buildJsonObject {
                put("path", result.path)
                put("binary", result.binary)
                put("size", result.size)
                result.mime?.let { put("mime", it) }
                result.sha256?.let { put("sha256", it) }
                if (!result.binary) {
                    put("content", result.content.orEmpty())
                    put("startLine", result.startLine)
                    put("endLine", result.endLine)
                    put("totalLines", result.totalLines)
                    put("truncated", result.truncated)
                }
            },
            truncated = result.truncated
        )
    }
}

private class ListFilesTool(services: WorkspaceServices) : WorkspaceTool(services) {
    override val definition = ToolDefinition(
        "list_files",
        "List files and folders inside the workspace with type, size, and modified time.",
        objectSchema(
            mapOf(
                "path" to field("string", "Workspace-relative directory; empty means workspace root"),
                "recursive" to field("boolean", "Whether to recursively list descendants"),
                "maxDepth" to field("integer", "Maximum recursion depth"),
                "maxResults" to field("integer", "Maximum entries to return")
            )
        ),
        RiskLevel.SAFE,
        ToolCategory.FILE_READ
    )

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val fileSystem = fs(context)
        val maxResults = input.int("maxResults", fileSystem.limits.maxListResults).coerceIn(1, fileSystem.limits.maxListResults)
        val entries = fileSystem.list(
            input.optionalString("path").orEmpty(),
            input.bool("recursive"),
            input.int("maxDepth", 8).coerceIn(1, 32),
            maxResults
        )
        return ToolResult.success("Listed ${entries.size} workspace entries", buildJsonObject {
            put("entries", buildJsonArray {
                entries.forEach { info ->
                    add(buildJsonObject {
                        put("path", info.path)
                        put("name", info.name)
                        put("directory", info.directory)
                        put("size", info.size)
                        put("modifiedAt", info.modifiedAt)
                        info.mime?.let { put("mime", it) }
                    })
                }
            })
            put("count", entries.size)
            put("truncated", entries.size >= maxResults)
        }, truncated = entries.size >= maxResults)
    }
}

private class SearchFilesTool(services: WorkspaceServices) : WorkspaceTool(services) {
    override val definition = ToolDefinition(
        "search_files",
        "Search workspace file names and UTF-8 text. Returns structured path, line, and snippet results.",
        objectSchema(
            mapOf(
                "query" to field("string", "Text query; may be empty when filenameQuery is provided"),
                "filenameQuery" to field("string", "Optional filename substring"),
                "glob" to field("string", "Optional glob such as **/*.kt"),
                "caseSensitive" to field("boolean", "Use case-sensitive matching"),
                "maxResults" to field("integer", "Maximum matches")
            ),
            listOf("query")
        ),
        RiskLevel.SAFE,
        ToolCategory.FILE_SEARCH
    )

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val fileSystem = fs(context)
        val max = input.int("maxResults", fileSystem.limits.maxSearchResults).coerceIn(1, fileSystem.limits.maxSearchResults)
        val results = fileSystem.search(
            query = input.string("query"),
            fileNameQuery = input.optionalString("filenameQuery"),
            glob = input.optionalString("glob"),
            caseSensitive = input.bool("caseSensitive"),
            maxResults = max
        )
        return ToolResult.success("Found ${results.size} workspace matches", buildJsonObject {
            put("results", buildJsonArray {
                results.forEach { match ->
                    add(buildJsonObject {
                        put("path", match.path)
                        match.line?.let { put("line", it) }
                        match.snippet?.let { put("snippet", it) }
                        put("fileNameMatch", match.fileNameMatch)
                    })
                }
            })
            put("count", results.size)
            put("truncated", results.size >= max)
        }, truncated = results.size >= max)
    }
}

private class FileInfoTool(services: WorkspaceServices) : WorkspaceTool(services) {
    override val definition = ToolDefinition(
        "file_info",
        "Return metadata for a workspace file or directory.",
        objectSchema(mapOf("path" to field("string", "Workspace-relative path")), listOf("path")),
        RiskLevel.SAFE,
        ToolCategory.FILE_READ
    )

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val info = fs(context).fileInfo(input.string("path"))
        return ToolResult.success("Read metadata for ${info.path}", buildJsonObject {
            put("path", info.path)
            put("name", info.name)
            put("directory", info.directory)
            put("size", info.size)
            put("modifiedAt", info.modifiedAt)
            info.mime?.let { put("mime", it) }
            put("binary", info.binary)
            info.sha256?.let { put("sha256", it) }
        })
    }
}

private data class PreparedMutation(val change: FileChange, val summary: String, val previewDiff: String? = null, val preparedAt: Long = System.currentTimeMillis())

private abstract class StagedMutationTool(services: WorkspaceServices) : WorkspaceTool(services) {
    private val prepared = ConcurrentHashMap<String, PreparedMutation>()

    protected abstract fun prepare(input: JsonObject, context: ToolContext): PreparedMutation

    override suspend fun preview(input: JsonObject, context: ToolContext): ToolPreview {
        prunePrepared()
        val mutation = prepare(input, context)
        prepared[key(context)] = mutation
        return ToolPreview(mutation.summary, mutation.change.path, mutation.previewDiff)
    }

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        prunePrepared()
        val cached = prepared.remove(key(context)) ?: prepare(input, context)
        verifyStillCurrent(cached.change, context)
        val changeSet = changes(context).propose(listOf(cached.change), context.toolCallId)
        return ToolResult.success(
            "Staged ${definition.name}: ${cached.summary}",
            buildJsonObject {
                put("changeSetId", changeSet.id)
                put("status", changeSet.status.name)
                put("filesChanged", changeSet.files.size)
                put("added", changeSet.addedLines)
                put("removed", changeSet.removedLines)
                cached.previewDiff?.let { put("diff", it) }
            },
            changeSetId = changeSet.id
        )
    }

    private fun key(context: ToolContext) = "${context.sessionId}:${context.toolCallId ?: definition.name}"

    private fun prunePrepared() {
        val cutoff = System.currentTimeMillis() - 30 * 60_000L
        prepared.entries.removeIf { it.value.preparedAt < cutoff }
        if (prepared.size > 512) {
            prepared.entries.sortedBy { it.value.preparedAt }.take(prepared.size - 512).forEach { prepared.remove(it.key, it.value) }
        }
    }

    private fun verifyStillCurrent(change: FileChange, context: ToolContext) {
        val fileSystem = fs(context)
        when (change.changeType) {
            FileChangeType.CREATE, FileChangeType.CREATE_DIRECTORY -> if (fileSystem.exists(change.path)) {
                throw ToolRegistryException(AgentError.patchConflict("${change.path} changed while permission was pending"))
            }
            FileChangeType.MODIFY -> if (!fileSystem.exists(change.path) || change.beforeHash != null && fileSystem.sha256(change.path) != change.beforeHash) {
                throw ToolRegistryException(AgentError.patchConflict("${change.path} changed while permission was pending"))
            }
            FileChangeType.MOVE, FileChangeType.DELETE -> {
                if (!fileSystem.exists(change.path) || change.beforeHash != null && fileSystem.fingerprint(change.path) != change.beforeHash) {
                    throw ToolRegistryException(AgentError.patchConflict("${change.path} changed while permission was pending"))
                }
                change.destinationPath?.let { if (fileSystem.exists(it)) throw ToolRegistryException(AgentError.patchConflict("$it appeared while permission was pending")) }
            }
        }
    }
}

private class WriteFileTool(services: WorkspaceServices, private val diffEngine: DiffEngine) : StagedMutationTool(services) {
    override val definition = ToolDefinition(
        "write_file",
        "Create or replace a UTF-8 workspace text file. The agent stages the change for review before it is committed.",
        objectSchema(
            mapOf(
                "path" to field("string", "Workspace-relative file path"),
                "content" to field("string", "Complete UTF-8 file content"),
                "createParents" to field("boolean", "Create missing parent folders"),
                "overwrite" to field("boolean", "Must be true to replace an existing file"),
                "expectedHash" to field("string", "Optional SHA-256 of the version being replaced"),
                "reason" to field("string", "Short reason shown in the permission UI")
            ),
            listOf("path", "content")
        ),
        RiskLevel.MODIFY,
        ToolCategory.FILE_MODIFY
    )

    override fun prepare(input: JsonObject, context: ToolContext): PreparedMutation {
        val fileSystem = fs(context)
        val path = input.string("path")
        val content = input.string("content")
        fileSystem.resolve(path)
        val exists = fileSystem.exists(path)
        val createParents = input.bool("createParents")
        if (exists && !input.bool("overwrite")) throw ToolRegistryException(AgentError.validation("overwrite=true is required for an existing file"))
        if (!exists && !createParents) {
            val parent = fileSystem.resolve(path).parentFile
            if (parent != null && !parent.exists()) throw ToolRegistryException(AgentError.io("Parent directory does not exist"))
        }
        val before = if (exists) fileSystem.readTextForMutation(path) else null
        input.optionalString("expectedHash")?.let { expected ->
            if (!exists || before?.second != expected) throw ToolRegistryException(AgentError.patchConflict("Expected hash does not match $path"))
        }
        val beforeContent = before?.first.orEmpty()
        val diff = diffEngine.diff(path, beforeContent, content).unifiedDiff
        return PreparedMutation(
            FileChange(
                path = path,
                beforeHash = before?.second,
                afterHash = hashText(content),
                beforeContent = before?.first,
                afterContent = content,
                diff = diff,
                changeType = if (exists) FileChangeType.MODIFY else FileChangeType.CREATE,
                createParents = createParents
            ),
            if (exists) "Replace $path" else "Create $path",
            diff
        )
    }
}

private class PatchFileTool(services: WorkspaceServices, private val diffEngine: DiffEngine) : StagedMutationTool(services) {
    override val definition = ToolDefinition(
        "patch_file",
        "Safely patch a UTF-8 workspace file using exact old/new content, an inclusive line range, or unified diff. Rejects stale or ambiguous patches.",
        objectSchema(
            mapOf(
                "path" to field("string", "Workspace-relative file path"),
                "expectedHash" to field("string", "Optional SHA-256 of the expected current file"),
                "oldContent" to field("string", "Exact current text to replace once"),
                "newContent" to field("string", "Replacement text for exact or range patch"),
                "startLine" to field("integer", "1-based inclusive start line for a range patch"),
                "endLine" to field("integer", "1-based inclusive end line for a range patch"),
                "unifiedDiff" to field("string", "Unified diff that must apply cleanly to the current file"),
                "reason" to field("string", "Short reason shown in the permission UI")
            ),
            listOf("path")
        ),
        RiskLevel.MODIFY,
        ToolCategory.FILE_MODIFY
    )

    override fun prepare(input: JsonObject, context: ToolContext): PreparedMutation {
        val path = input.string("path")
        val (before, currentHash) = fs(context).readTextForMutation(path)
        input.optionalString("expectedHash")?.let { if (it != currentHash) throw ToolRegistryException(AgentError.patchConflict("Expected hash does not match $path")) }
        val unified = input.optionalString("unifiedDiff")
        val oldContent = input.optionalString("oldContent")
        val newContent = input.optionalString("newContent")
        val startLine = input["startLine"]?.jsonPrimitive?.intOrNull
        val endLine = input["endLine"]?.jsonPrimitive?.intOrNull
        val after = when {
            !unified.isNullOrBlank() -> diffEngine.applyUnifiedDiff(before, unified)
            oldContent != null && newContent != null -> replaceExactOnce(before, oldContent, newContent)
            startLine != null && endLine != null && newContent != null -> replaceLineRange(before, startLine, endLine, newContent)
            else -> throw ToolRegistryException(AgentError.validation("patch_file requires unifiedDiff, oldContent+newContent, or startLine+endLine+newContent"))
        }
        if (after == before) throw ToolRegistryException(AgentError.validation("Patch would not change the file"))
        val diff = diffEngine.diff(path, before, after).unifiedDiff
        return PreparedMutation(
            FileChange(path, beforeHash = currentHash, afterHash = hashText(after), beforeContent = before, afterContent = after, diff = diff, changeType = FileChangeType.MODIFY),
            "Patch $path",
            diff
        )
    }

    private fun replaceExactOnce(before: String, oldContent: String, newContent: String): String {
        if (oldContent.isEmpty()) throw ToolRegistryException(AgentError.validation("oldContent cannot be empty"))
        val first = before.indexOf(oldContent)
        if (first < 0) throw ToolRegistryException(AgentError.patchConflict("oldContent does not match the current file"))
        if (before.indexOf(oldContent, first + oldContent.length) >= 0) throw ToolRegistryException(AgentError.patchConflict("oldContent matches more than once; use a range or unified diff"))
        return before.replaceRange(first, first + oldContent.length, newContent)
    }

    private fun replaceLineRange(before: String, startLine: Int, endLine: Int, replacement: String): String {
        val lines = if (before.isEmpty()) emptyList() else before.split('\n')
        if (startLine < 1 || endLine < startLine || endLine > lines.size) throw ToolRegistryException(AgentError.patchConflict("Line range $startLine-$endLine is outside the current file"))
        return buildList {
            addAll(lines.take(startLine - 1))
            if (replacement.isNotEmpty()) addAll(replacement.split('\n'))
            addAll(lines.drop(endLine))
        }.joinToString("\n")
    }
}

private class MoveFileTool(services: WorkspaceServices) : StagedMutationTool(services) {
    override val definition = ToolDefinition(
        "move_file",
        "Stage a move or rename inside the workspace. Destination conflicts and workspace escapes are rejected.",
        objectSchema(mapOf("source" to field("string", "Workspace-relative source"), "destination" to field("string", "Workspace-relative destination"), "reason" to field("string", "Short reason shown in the permission UI")), listOf("source", "destination")),
        RiskLevel.MODIFY,
        ToolCategory.FILE_MODIFY
    )

    override fun prepare(input: JsonObject, context: ToolContext): PreparedMutation {
        val fileSystem = fs(context)
        val source = input.string("source")
        val destination = input.string("destination")
        val sourceFile = fileSystem.resolve(source, mustExist = true)
        fileSystem.resolve(destination)
        if (sourceFile == fileSystem.resolve("")) throw ToolRegistryException(AgentError.workspaceViolation("Cannot move the workspace root"))
        if (fileSystem.exists(destination)) throw ToolRegistryException(AgentError.validation("Destination already exists: $destination"))
        return PreparedMutation(FileChange(path = source, destinationPath = destination, beforeHash = fileSystem.fingerprint(source), changeType = FileChangeType.MOVE), "Move $source to $destination")
    }
}

private class DeleteFileTool(services: WorkspaceServices) : StagedMutationTool(services) {
    override val definition = ToolDefinition(
        "delete_file",
        "Stage deletion of a workspace file or folder. Accepted deletions move to the internal workspace trash so they can be reverted.",
        objectSchema(mapOf("path" to field("string", "Workspace-relative file or folder"), "reason" to field("string", "Short reason shown in the permission UI")), listOf("path")),
        RiskLevel.DESTRUCTIVE,
        ToolCategory.FILE_DESTRUCTIVE
    )

    override fun prepare(input: JsonObject, context: ToolContext): PreparedMutation {
        val fileSystem = fs(context)
        val path = input.string("path")
        val target = fileSystem.resolve(path, mustExist = true)
        if (target == fileSystem.resolve("")) throw ToolRegistryException(AgentError.workspaceViolation("Cannot delete the workspace root"))
        val beforeContent = if (target.isFile) runCatching { fileSystem.readTextForMutation(path).first }.getOrNull() else null
        return PreparedMutation(FileChange(path = path, beforeHash = fileSystem.fingerprint(path), beforeContent = beforeContent, changeType = FileChangeType.DELETE), "Delete $path (moves to workspace trash)")
    }
}

private class CreateDirectoryTool(services: WorkspaceServices) : StagedMutationTool(services) {
    override val definition = ToolDefinition(
        "create_directory",
        "Stage creation of a directory inside the workspace.",
        objectSchema(mapOf("path" to field("string", "Workspace-relative directory"), "createParents" to field("boolean", "Create missing parent folders"), "reason" to field("string", "Short reason shown in the permission UI")), listOf("path")),
        RiskLevel.MODIFY,
        ToolCategory.WORKSPACE
    )

    override fun prepare(input: JsonObject, context: ToolContext): PreparedMutation {
        val path = input.string("path")
        val fileSystem = fs(context)
        fileSystem.resolve(path)
        if (path.isBlank()) throw ToolRegistryException(AgentError.workspaceViolation("Cannot create the workspace root"))
        if (fileSystem.exists(path)) throw ToolRegistryException(AgentError.validation("Path already exists: $path"))
        val createParents = input.bool("createParents", true)
        if (!createParents) {
            val parent = fileSystem.resolve(path).parentFile
            if (parent != null && !parent.exists()) throw ToolRegistryException(AgentError.io("Parent directory does not exist"))
        }
        return PreparedMutation(FileChange(path = path, changeType = FileChangeType.CREATE_DIRECTORY, createParents = createParents), "Create directory $path")
    }
}
