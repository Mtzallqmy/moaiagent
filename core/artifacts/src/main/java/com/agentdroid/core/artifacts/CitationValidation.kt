package com.agentdroid.core.artifacts

/** Implemented by the Research module; deliberately contains no Research model dependency. */
fun interface CitationSourceCatalog {
    suspend fun contains(researchSessionId: String, sourceId: String, canonicalUrl: String): Boolean
}

class CitationValidator(private val catalog: CitationSourceCatalog) {
    suspend fun validate(references: List<SourceReference>) {
        val identities = HashSet<Triple<String, String, String>>()
        references.forEach { reference ->
            val canonicalUrl = canonicalizeUrl(reference.url)
            val identity = Triple(reference.researchSessionId, reference.sourceId, canonicalUrl)
            if (!identities.add(identity)) throw InvalidCitation("Duplicate source reference: ${reference.sourceId}")
            if (!catalog.contains(reference.researchSessionId, reference.sourceId, canonicalUrl)) {
                throw InvalidCitation("Source '${reference.sourceId}' does not exist in research session '${reference.researchSessionId}'")
            }
        }
    }

    private fun canonicalizeUrl(value: String): String {
        val uri = runCatching { java.net.URI(value.trim()).normalize() }
            .getOrElse { throw InvalidCitation("Invalid citation URL") }
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw InvalidCitation("Citation URL must be an HTTP(S) source URL")
        }
        return uri.toASCIIString()
    }

    companion object {
        val REJECT_ALL = CitationValidator(CitationSourceCatalog { _, _, _ -> false })
    }
}
