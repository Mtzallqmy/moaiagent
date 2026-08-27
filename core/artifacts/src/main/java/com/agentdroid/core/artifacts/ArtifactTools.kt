package com.agentdroid.core.artifacts

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.AgentTool
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCategory
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolDefinition
import com.agentdroid.core.agent.ToolPreview
import com.agentdroid.core.agent.ToolRegistry
import com.agentdroid.core.agent.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun interface ArtifactServices { fun repository(workspaceId: String): ArtifactRepository }

fun createArtifactTools(services: ArtifactServices): List<AgentTool> = listOf(
    CreateArtifactTool(services), UpdateArtifactTool(services), ListArtifactsTool(services),
    ReadArtifactTool(services), DeleteArtifactTool(services)
)

fun createArtifactToolRegistry(services: ArtifactServices) = ToolRegistry(createArtifactTools(services))

private abstract class BaseArtifactTool(protected val services: ArtifactServices) : AgentTool {
    protected fun repository(context: ToolContext) = services.repository(context.workspaceId)
    protected suspend fun result(block: suspend () -> ToolResult): ToolResult = try {
        block()
    } catch (failure: IllegalArgumentException) {
        ToolResult.failure(AgentError.validation(failure.message ?: "Invalid artifact request"))
    } catch (failure: InvalidCitation) {
        ToolResult.failure(AgentError.validation(failure.message ?: "Invalid citation"))
    } catch (failure: ArtifactException) {
        ToolResult.failure(AgentError.io(failure.message ?: "Artifact operation failed"))
    }
}

private class CreateArtifactTool(services: ArtifactServices) : BaseArtifactTool(services) {
    override val definition = ToolDefinition(
        "create_artifact", "Create a persisted textual artifact inside the active workspace.",
        schema(listOf("type", "title", "content"), mapOf(
            "type" to "string", "title" to "string", "content" to "string", "taskId" to "string",
            "fileName" to "string", "mimeType" to "string", "sourceReferences" to "array"
        )), RiskLevel.MODIFY, ToolCategory.FILE_MODIFY
    )
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override suspend fun preview(input: JsonObject, context: ToolContext) = ToolPreview(
        "Create ${input.string("type")} artifact: ${input.string("title")}", input.string("fileName")
    )
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult = result {
        val artifact = repository(context).create(CreateArtifactRequest(
            workspaceId = context.workspaceId, conversationId = context.conversationId,
            type = input.artifactType("type"), title = input.requiredString("title"), content = input.requiredString("content"),
            taskId = input.string("taskId"), preferredFileName = input.string("fileName"), mimeType = input.string("mimeType"),
            sourceReferences = input.references("sourceReferences")
        ))
        ToolResult.success("Created artifact ${artifact.title}", artifactJson(artifact))
    }
}

private class UpdateArtifactTool(services: ArtifactServices) : BaseArtifactTool(services) {
    override val definition = ToolDefinition(
        "update_artifact", "Update artifact text, title, filename, or verified source references.",
        schema(listOf("id"), mapOf("id" to "string", "title" to "string", "content" to "string", "fileName" to "string", "sourceReferences" to "array")),
        RiskLevel.MODIFY, ToolCategory.FILE_MODIFY
    )
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override suspend fun preview(input: JsonObject, context: ToolContext) = ToolPreview("Update artifact ${input.string("id")}")
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult = result {
        if (listOf("title", "content", "fileName", "sourceReferences").none(input::containsKey)) {
            throw IllegalArgumentException("At least one artifact field must be supplied")
        }
        val artifact = repository(context).update(context.workspaceId, input.requiredString("id"), UpdateArtifactRequest(
            title = input.string("title"), content = input.string("content"), preferredFileName = input.string("fileName"),
            sourceReferences = if (input.containsKey("sourceReferences")) input.references("sourceReferences") else null
        ))
        ToolResult.success("Updated artifact ${artifact.title}", artifactJson(artifact))
    }
}

private class ListArtifactsTool(services: ArtifactServices) : BaseArtifactTool(services) {
    override val definition = ToolDefinition(
        "list_artifacts", "List persisted artifacts for the active workspace.",
        schema(fields = mapOf("conversationId" to "string", "taskId" to "string", "type" to "string")),
        RiskLevel.SAFE, ToolCategory.FILE_READ
    )
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult = result {
        val artifacts = repository(context).list(ArtifactListFilter(
            workspaceId = context.workspaceId, conversationId = input.string("conversationId"),
            taskId = input.string("taskId"), type = input.string("type")?.let { parseType(it) }
        )).take(200)
        ToolResult.success("Listed ${artifacts.size} artifacts", buildJsonObject {
            put("artifacts", buildJsonArray { artifacts.forEach { add(artifactJson(it)) } }); put("count", artifacts.size)
        })
    }
}

private class ReadArtifactTool(services: ArtifactServices) : BaseArtifactTool(services) {
    override val definition = ToolDefinition(
        "read_artifact", "Read bounded text from a textual artifact. Screenshots return references through list/get metadata.",
        schema(listOf("id"), mapOf("id" to "string", "maxBytes" to "integer")), RiskLevel.SAFE, ToolCategory.FILE_READ
    )
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult = result {
        val read = repository(context).read(
            context.workspaceId, input.requiredString("id"),
            (input["maxBytes"]?.jsonPrimitive?.intOrNull ?: ArtifactRepository.DEFAULT_READ_LIMIT).coerceIn(1, ArtifactRepository.DEFAULT_READ_LIMIT)
        )
        ToolResult.success("Read artifact ${read.artifact.title}", buildJsonObject {
            put("artifact", artifactJson(read.artifact)); put("content", read.content); put("truncated", read.truncated)
        }, truncated = read.truncated)
    }
}

private class DeleteArtifactTool(services: ArtifactServices) : BaseArtifactTool(services) {
    override val definition = ToolDefinition(
        "delete_artifact", "Delete an artifact. Managed files are deleted; screenshot references only remove artifact metadata.",
        schema(listOf("id"), mapOf("id" to "string", "reason" to "string")), RiskLevel.DESTRUCTIVE, ToolCategory.FILE_DESTRUCTIVE
    )
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override suspend fun permissionKey(input: JsonObject, context: ToolContext) = "delete_artifact:DESTRUCTIVE"
    override suspend fun preview(input: JsonObject, context: ToolContext) = ToolPreview("Delete artifact ${input.string("id")}")
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult = result {
        val deleted = repository(context).delete(context.workspaceId, input.requiredString("id"))
        ToolResult.success("Deleted artifact ${deleted.title}", artifactJson(deleted))
    }
}

private fun artifactJson(artifact: Artifact) = buildJsonObject {
    put("id", artifact.id); artifact.taskId?.let { put("taskId", it) }; put("conversationId", artifact.conversationId)
    put("workspaceId", artifact.workspaceId); put("type", artifact.type.name); put("title", artifact.title)
    put("filePath", artifact.filePath); put("mimeType", artifact.mimeType); put("createdAt", artifact.createdAt)
    put("updatedAt", artifact.updatedAt); put("sizeBytes", artifact.sizeBytes); put("storage", artifact.storage.name)
    put("sourceReferences", buildJsonArray { artifact.sourceReferences.forEach { source -> add(buildJsonObject {
        put("researchSessionId", source.researchSessionId); put("sourceId", source.sourceId); put("url", source.url)
        source.title?.let { put("title", it) }; put("findingIds", buildJsonArray { source.findingIds.forEach { add(JsonPrimitive(it)) } })
    }) } })
}

private fun schema(required: List<String> = emptyList(), fields: Map<String, String> = emptyMap()) = buildJsonObject {
    put("type", "object"); put("additionalProperties", false)
    put("properties", buildJsonObject { fields.forEach { (name, type) -> put(name, buildJsonObject { put("type", type) }) } })
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.requiredString(key: String): String = string(key)?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("$key must not be blank")
private fun JsonObject.artifactType(key: String) = parseType(requiredString(key))
private fun parseType(value: String): ArtifactType = runCatching { ArtifactType.valueOf(value.trim().uppercase()) }
    .getOrElse { throw IllegalArgumentException("Unsupported artifact type: $value") }
private fun JsonObject.references(key: String): List<SourceReference> = ((this[key] as? JsonArray) ?: return emptyList()).mapIndexed { index, element ->
    val source = element as? JsonObject ?: throw IllegalArgumentException("$key[$index] must be an object")
    SourceReference(
        researchSessionId = source.requiredString("researchSessionId"), sourceId = source.requiredString("sourceId"),
        url = source.requiredString("url"), title = source.string("title"),
        findingIds = (source["findingIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    )
}
