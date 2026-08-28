package com.agentdroid.integration

import android.content.Context
import com.agentdroid.AgentDroidApplication
import com.agentdroid.core.model.MemoryScope
import com.agentdroid.core.mcp.McpServerConfig
import com.agentdroid.data.database.*
import com.agentdroid.settings.AppLanguage
import com.agentdroid.settings.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Explicit user-initiated backup/import. SecureSecretStore values, provider secret aliases,
 * custom provider headers and MCP credentials are deliberately never serialized.
 */
class AppBackupManager(
    private val context: Context,
    private val application: AgentDroidApplication = context.applicationContext as AgentDroidApplication,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
) {
    private val container get() = application.container

    suspend fun exportTo(output: OutputStream): BackupSummary = withContext(Dispatchers.IO) {
        val workspaces = container.workspaces.observeAll().first()
        val conversations = container.conversations.observeIncludingArchived().first()
        val providers = container.providers.observeAll().first()
        val memories = container.memory.observeAll().first()
        val skills = container.skills.observeAll().first()
        val settings = container.settings.settings.first()
        val mcpConfigs = application.mcpController.states.value.map { it.config }
        val messages = buildList {
            conversations.forEach { conversation -> addAll(container.messages.observe(conversation.id).first()) }
        }

        val metadata = buildJsonObject {
            put("schema", BACKUP_SCHEMA)
            put("createdAt", System.currentTimeMillis())
            putJsonObject("settings") {
                put("language", settings.language.name)
                put("theme", settings.theme.name)
                put("dynamicColor", settings.dynamicColor)
                settings.defaultProvider?.let { put("defaultProvider", it) }
                settings.defaultModel?.let { put("defaultModel", it) }
                put("developerMode", settings.developerMode)
            }
            putJsonArray("workspaces") { workspaces.forEach { add(it.toBackupJson()) } }
            putJsonArray("providers") { providers.forEach { add(it.toSafeBackupJson()) } }
            putJsonArray("memory") { memories.forEach { add(it.toBackupJson()) } }
            putJsonArray("skills") { skills.forEach { add(it.toBackupJson()) } }
            putJsonArray("conversations") { conversations.forEach { add(it.toBackupJson()) } }
            putJsonArray("messages") { messages.forEach { add(it.toBackupJson()) } }
            putJsonArray("mcp") { mcpConfigs.forEach { add(it.toSafeBackupJson()) } }
        }

        var fileCount = 0
        var fileBytes = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(METADATA_ENTRY))
            zip.write(json.encodeToString(JsonObject.serializer(), metadata).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            workspaces.forEach { workspace ->
                val root = container.workspaceRoot(workspace.id)
                val canonicalRoot = root.canonicalFile
                if (!canonicalRoot.isDirectory) return@forEach
                canonicalRoot.walkTopDown().forEach fileLoop@{ file ->
                    if (!file.isFile) return@fileLoop
                    val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@fileLoop
                    if (!canonical.path.startsWith(canonicalRoot.path + File.separator)) return@fileLoop
                    val relative = canonical.relativeTo(canonicalRoot).invariantSeparatorsPath
                    validateRelativePath(relative)
                    zip.putNextEntry(ZipEntry("workspaces/${workspace.id}/$relative"))
                    canonical.inputStream().buffered().use { input -> fileBytes += input.copyTo(zip) }
                    zip.closeEntry()
                    fileCount++
                }
            }
        }
        BackupSummary(workspaces.size, conversations.size, messages.size, fileCount, fileBytes, secretsExported = false)
    }

    suspend fun importFrom(input: InputStream): BackupSummary = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "backup-import-${System.nanoTime()}").apply { mkdirs() }
        var metadataText: String? = null
        var extractedFiles = 0
        var extractedBytes = 0L
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    validateArchivePath(name)
                    if (entry.isDirectory) {
                        zip.closeEntry(); continue
                    }
                    if (name == METADATA_ENTRY) {
                        metadataText = readLimited(zip, MAX_METADATA_BYTES).toString(Charsets.UTF_8)
                    } else if (name.startsWith("workspaces/")) {
                        val target = File(staging, name)
                        val canonicalRoot = staging.canonicalFile
                        val canonicalTarget = target.canonicalFile
                        require(canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) { "Backup entry escapes staging root" }
                        canonicalTarget.parentFile?.mkdirs()
                        canonicalTarget.outputStream().buffered().use { output ->
                            val copied = copyLimited(zip, output, MAX_FILE_BYTES)
                            extractedBytes += copied
                            require(extractedBytes <= MAX_TOTAL_BYTES) { "Backup exceeds import size limit" }
                        }
                        extractedFiles++
                        require(extractedFiles <= MAX_FILES) { "Backup contains too many files" }
                    }
                    zip.closeEntry()
                }
            }

            val root = json.parseToJsonElement(requireNotNull(metadataText) { "Backup metadata is missing" }).jsonObject
            require(root["schema"]?.jsonPrimitive?.intOrNull == BACKUP_SCHEMA) { "Unsupported backup schema" }
            val workspaces = root.array("workspaces").map(::workspaceFromJson)
            val providers = root.array("providers").map(::providerFromJson)
            val memories = root.array("memory").map(::memoryFromJson)
            val skills = root.array("skills").map(::skillFromJson)
            val conversations = root.array("conversations").map(::conversationFromJson)
            val messages = root.array("messages").map(::messageFromJson)

            workspaces.forEach { workspace ->
                container.workspaces.save(workspace)
                val stagedRoot = File(staging, "workspaces/${workspace.id}")
                if (stagedRoot.isDirectory) copyTreeSafely(stagedRoot, container.workspaceRoot(workspace.id))
            }
            providers.forEach { provider -> container.providers.save(provider.copy(secretAlias = null, customHeadersJson = "{}")) }
            memories.forEach { container.memory.save(it) }
            skills.forEach { container.skills.save(it) }
            conversations.forEach { container.conversations.save(it) }
            messages.filter { message -> conversations.any { it.id == message.conversationId } }.forEach { container.messages.save(it) }

            root["settings"]?.jsonObject?.let { settings ->
                settings["language"]?.jsonPrimitive?.contentOrNull?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }?.let { container.settings.setLanguage(it) }
                settings["theme"]?.jsonPrimitive?.contentOrNull?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }?.let { container.settings.setTheme(it) }
                settings["dynamicColor"]?.jsonPrimitive?.booleanOrNull?.let { container.settings.setDynamicColor(it) }
                container.settings.setDefaultProvider(settings["defaultProvider"]?.jsonPrimitive?.contentOrNull)
                container.settings.setDefaultModel(settings["defaultModel"]?.jsonPrimitive?.contentOrNull)
                settings["developerMode"]?.jsonPrimitive?.booleanOrNull?.let { container.settings.setDeveloperMode(it) }
            }

            root.array("mcp").forEach { element ->
                val item = element.jsonObject
                val id = item.requiredString("id")
                val name = item.requiredString("name")
                val endpoint = item.requiredString("endpoint")
                val enabled = item["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                application.mcpController.save(id, name, endpoint, enabled, credential = null).getOrThrow()
            }

            BackupSummary(workspaces.size, conversations.size, messages.size, extractedFiles, extractedBytes, secretsExported = false)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun copyTreeSafely(sourceRoot: File, destinationRoot: File) {
        val sourceCanonical = sourceRoot.canonicalFile
        val destCanonical = destinationRoot.apply { mkdirs() }.canonicalFile
        sourceCanonical.walkTopDown().forEach { source ->
            val relative = source.relativeTo(sourceCanonical).invariantSeparatorsPath
            if (relative.isBlank()) return@forEach
            validateRelativePath(relative)
            val target = File(destCanonical, relative).canonicalFile
            require(target.path.startsWith(destCanonical.path + File.separator)) { "Workspace import escaped destination root" }
            if (source.isDirectory) target.mkdirs()
            else if (source.isFile) {
                target.parentFile?.mkdirs()
                source.inputStream().buffered().use { input -> target.outputStream().buffered().use { output -> input.copyTo(output) } }
            }
        }
    }

    private fun validateArchivePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\u0000')) { "Invalid backup path" }
        path.split('/').forEach { require(it.isNotBlank() && it != "." && it != "..") { "Invalid backup path segment" } }
    }

    private fun validateRelativePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/'))
        path.split('/').forEach { require(it.isNotBlank() && it != "." && it != "..") }
    }

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyLimited(input, out, maxBytes)
        return out.toByteArray()
    }

    private fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            require(total <= maxBytes) { "Backup entry exceeds size limit" }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun JsonObject.array(name: String): List<JsonElement> = this[name]?.jsonArray.orEmpty()
    private fun JsonObject.requiredString(name: String): String = this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Backup field '$name' is missing")

    private fun WorkspaceEntity.toBackupJson() = buildJsonObject {
        put("id", id); put("name", name); put("description", description); put("createdAt", createdAt); put("updatedAt", updatedAt)
        put("rootPath", rootPath); lastOpenedFile?.let { put("lastOpenedFile", it) }
    }
    private fun workspaceFromJson(e: JsonElement): WorkspaceEntity = e.jsonObject.let { o ->
        WorkspaceEntity(o.requiredString("id"), o.requiredString("name"), o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(), o["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(), o["rootPath"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["lastOpenedFile"]?.jsonPrimitive?.contentOrNull)
    }

    private fun ProviderConfigEntity.toSafeBackupJson() = buildJsonObject {
        put("id", id); put("name", name); put("kind", kind); baseUrl?.let { put("baseUrl", it) }; modelId?.let { put("modelId", it) }
        organizationId?.let { put("organizationId", it) }; appName?.let { put("appName", it) }; siteUrl?.let { put("siteUrl", it) }; put("enabled", enabled)
        put("secretsExcluded", true)
    }
    private fun providerFromJson(e: JsonElement): ProviderConfigEntity = e.jsonObject.let { o ->
        ProviderConfigEntity(o.requiredString("id"), o.requiredString("name"), o.requiredString("kind"), o["baseUrl"]?.jsonPrimitive?.contentOrNull, o["modelId"]?.jsonPrimitive?.contentOrNull, null, o["organizationId"]?.jsonPrimitive?.contentOrNull, o["appName"]?.jsonPrimitive?.contentOrNull, o["siteUrl"]?.jsonPrimitive?.contentOrNull, "{}", o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true)
    }

    private fun MemoryEntryEntity.toBackupJson() = buildJsonObject {
        put("id", id); put("scope", scope); workspaceId?.let { put("workspaceId", it) }; put("title", title); put("content", content); put("enabled", enabled); put("createdAt", createdAt); put("updatedAt", updatedAt)
    }
    private fun memoryFromJson(e: JsonElement): MemoryEntryEntity = e.jsonObject.let { o ->
        MemoryEntryEntity(o.requiredString("id"), o["scope"]?.jsonPrimitive?.contentOrNull ?: MemoryScope.GLOBAL.name, o["workspaceId"]?.jsonPrimitive?.contentOrNull, o.requiredString("title"), o["content"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true, o["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(), o["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis())
    }

    private fun SkillEntity.toBackupJson() = buildJsonObject {
        put("id", id); put("name", name); put("description", description); put("instructions", instructions); put("enabled", enabled); put("scope", scope); workspaceId?.let { put("workspaceId", it) }; put("createdAt", createdAt); put("updatedAt", updatedAt)
    }
    private fun skillFromJson(e: JsonElement): SkillEntity = e.jsonObject.let { o ->
        SkillEntity(o.requiredString("id"), o.requiredString("name"), o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true, o["scope"]?.jsonPrimitive?.contentOrNull ?: "GLOBAL", o["workspaceId"]?.jsonPrimitive?.contentOrNull, o["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(), o["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis())
    }

    private fun ConversationEntity.toBackupJson() = buildJsonObject {
        put("id", id); put("title", title); put("createdAt", createdAt); put("updatedAt", updatedAt); providerId?.let { put("providerId", it) }; modelId?.let { put("modelId", it) }; workspaceId?.let { put("workspaceId", it) }; put("archived", archived)
    }
    private fun conversationFromJson(e: JsonElement): ConversationEntity = e.jsonObject.let { o ->
        ConversationEntity(o.requiredString("id"), o.requiredString("title"), o["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(), o["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(), o["providerId"]?.jsonPrimitive?.contentOrNull, o["modelId"]?.jsonPrimitive?.contentOrNull, o["workspaceId"]?.jsonPrimitive?.contentOrNull, o["archived"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    private fun MessageEntity.toBackupJson() = buildJsonObject {
        put("id", id); put("conversationId", conversationId); put("role", role); put("content", content); put("status", status); put("createdAt", createdAt); put("updatedAt", updatedAt); providerId?.let { put("providerId", it) }; modelId?.let { put("modelId", it) }; usageJson?.let { put("usageJson", it) }; errorJson?.let { put("errorJson", it) }
    }
    private fun messageFromJson(e: JsonElement): MessageEntity = e.jsonObject.let { o ->
        MessageEntity(o.requiredString("id"), o.requiredString("conversationId"), o.requiredString("role"), o["content"]?.jsonPrimitive?.contentOrNull.orEmpty(), o.requiredString("status"), o["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(), o["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(), o["providerId"]?.jsonPrimitive?.contentOrNull, o["modelId"]?.jsonPrimitive?.contentOrNull, o["usageJson"]?.jsonPrimitive?.contentOrNull, o["errorJson"]?.jsonPrimitive?.contentOrNull)
    }

    private fun McpServerConfig.toSafeBackupJson() = buildJsonObject {
        put("id", id); put("name", name); put("endpoint", endpoint); put("enabled", enabled); put("secretsExcluded", true)
    }

    companion object {
        const val BACKUP_SCHEMA = 1
        private const val METADATA_ENTRY = "backup.json"
        private const val MAX_METADATA_BYTES = 16L * 1024 * 1024
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_FILES = 100_000
    }
}

data class BackupSummary(
    val workspaces: Int,
    val conversations: Int,
    val messages: Int,
    val workspaceFiles: Int,
    val workspaceBytes: Long,
    val secretsExported: Boolean
)
