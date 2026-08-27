package com.agentdroid.core.artifacts

import kotlinx.serialization.json.Json

data class ArtifactGenerationRequest(
    val type: ArtifactType,
    val title: String,
    val body: String = "",
    val summary: String? = null,
    val findings: List<ReportFinding> = emptyList(),
    val comparison: String? = null,
    val conclusion: String? = null,
    val sourceReferences: List<SourceReference> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val codeLanguage: String? = null
)

data class ReportFinding(val heading: String, val detail: String, val sourceIds: List<String> = emptyList())
data class GeneratedArtifact(val content: String, val mimeType: String, val extension: String)

interface ArtifactGenerator { fun generate(request: ArtifactGenerationRequest): GeneratedArtifact }

class DefaultArtifactGenerator : ArtifactGenerator {
    override fun generate(request: ArtifactGenerationRequest): GeneratedArtifact {
        require(request.title.isNotBlank()) { "title must not be blank" }
        val content = when (request.type) {
            ArtifactType.MARKDOWN -> request.body
            ArtifactType.PLAIN_TEXT -> request.body
            ArtifactType.JSON -> validateJson(request.body)
            ArtifactType.CSV -> if (request.rows.isNotEmpty()) csv(request.rows) else request.body
            ArtifactType.HTML -> if (request.body.isNotBlank()) request.body else htmlDocument(request)
            ArtifactType.CODE -> request.body
            ArtifactType.REPORT -> researchReport(request)
            ArtifactType.SCREENSHOT -> throw IllegalArgumentException("Screenshots must be registered as file references")
        }
        return GeneratedArtifact(content, request.type.defaultMimeType, request.type.defaultExtension)
    }

    private fun validateJson(value: String): String = value.also { Json.parseToJsonElement(it) }

    private fun csv(rows: List<List<String>>): String = rows.joinToString("\n") { row ->
        row.joinToString(",") { cell ->
            if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${cell.replace("\"", "\"\"")}\"" else cell
        }
    }

    private fun htmlDocument(request: ArtifactGenerationRequest): String = buildString {
        append("<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>")
        append(escapeHtml(request.title)).append("</title></head><body><h1>")
        append(escapeHtml(request.title)).append("</h1>")
        request.summary?.let { append("<p>").append(escapeHtml(it)).append("</p>") }
        append("</body></html>\n")
    }

    /** Produces the required Title/Summary/Findings/Comparison/Conclusion/Sources structure. */
    private fun researchReport(request: ArtifactGenerationRequest): String {
        val knownSources = request.sourceReferences.associateBy { it.sourceId }
        request.findings.flatMap { it.sourceIds }.forEach { sourceId ->
            require(sourceId in knownSources) { "Finding cites source '$sourceId' that is not attached to this artifact" }
        }
        return buildString {
            append("# ").append(request.title.trim()).append("\n\n")
            append("## Summary\n\n").append(request.summary.orEmpty().trim()).append("\n\n")
            append("## Findings\n\n")
            if (request.findings.isEmpty()) append(request.body.trim()).append("\n\n") else request.findings.forEach { finding ->
                append("### ").append(finding.heading.trim()).append("\n\n").append(finding.detail.trim())
                if (finding.sourceIds.isNotEmpty()) append(" ").append(finding.sourceIds.joinToString(" ") { "[$it]" })
                append("\n\n")
            }
            append("## Comparison\n\n").append(request.comparison.orEmpty().trim()).append("\n\n")
            append("## Conclusion\n\n").append(request.conclusion.orEmpty().trim()).append("\n\n")
            append("## Sources\n\n")
            request.sourceReferences.forEach { source ->
                append("- [").append(source.sourceId).append("] ")
                source.title?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append(" — ") }
                append(source.url).append("\n")
            }
        }
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
