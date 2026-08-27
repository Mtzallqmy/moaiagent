package com.agentdroid.core.artifacts

import kotlinx.serialization.Serializable

@Serializable
enum class ArtifactType(val defaultExtension: String, val defaultMimeType: String, val textual: Boolean = true) {
    MARKDOWN("md", "text/markdown"),
    PLAIN_TEXT("txt", "text/plain"),
    JSON("json", "application/json"),
    CSV("csv", "text/csv"),
    HTML("html", "text/html"),
    CODE("txt", "text/plain"),
    REPORT("md", "text/markdown"),
    SCREENSHOT("png", "image/png", textual = false)
}

@Serializable
enum class ArtifactStorage { MANAGED_FILE, EXTERNAL_REFERENCE }

/** A citation captured by Research, identified by its immutable session/source ids. */
@Serializable
data class SourceReference(
    val researchSessionId: String,
    val sourceId: String,
    val url: String,
    val title: String? = null,
    val findingIds: List<String> = emptyList()
) {
    init {
        require(researchSessionId.isNotBlank()) { "researchSessionId must not be blank" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(url.isNotBlank()) { "url must not be blank" }
    }
}

@Serializable
data class Artifact(
    val id: String,
    val taskId: String? = null,
    val conversationId: String,
    val workspaceId: String,
    val type: ArtifactType,
    val title: String,
    /** Always workspace-relative; absolute paths never cross this API boundary. */
    val filePath: String,
    val mimeType: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceReferences: List<SourceReference> = emptyList(),
    val storage: ArtifactStorage = ArtifactStorage.MANAGED_FILE,
    val sizeBytes: Long = 0
)

data class CreateArtifactRequest(
    val workspaceId: String,
    val conversationId: String,
    val type: ArtifactType,
    val title: String,
    val content: String,
    val taskId: String? = null,
    val sourceReferences: List<SourceReference> = emptyList(),
    val preferredFileName: String? = null,
    val mimeType: String? = null
)

data class UpdateArtifactRequest(
    val title: String? = null,
    val content: String? = null,
    val sourceReferences: List<SourceReference>? = null,
    val preferredFileName: String? = null
)

data class ArtifactReadResult(val artifact: Artifact, val content: String, val truncated: Boolean)

data class ArtifactListFilter(
    val workspaceId: String,
    val conversationId: String? = null,
    val taskId: String? = null,
    val type: ArtifactType? = null
)

data class ScreenshotReferenceRequest(
    val workspaceId: String,
    val conversationId: String,
    val title: String,
    val workspaceRelativePath: String,
    val taskId: String? = null,
    val mimeType: String = ArtifactType.SCREENSHOT.defaultMimeType,
    val sourceReferences: List<SourceReference> = emptyList()
)

open class ArtifactException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class ArtifactNotFound(id: String) : ArtifactException("Artifact not found: $id")
class ArtifactWriteError(message: String, cause: Throwable? = null) : ArtifactException(message, cause)
class UnsafeArtifactPath(message: String) : ArtifactException(message)
class InvalidCitation(message: String) : ArtifactException(message)
