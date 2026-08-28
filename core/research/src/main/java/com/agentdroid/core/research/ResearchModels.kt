package com.agentdroid.core.research

import kotlinx.serialization.Serializable

@Serializable
data class ResearchSource(
    val id: String,
    val url: String,
    val title: String,
    val domain: String,
    val retrievedAt: Long,
    val excerpt: String,
    val relevance: Double
)

@Serializable
data class ResearchFinding(
    val id: String,
    val text: String,
    val sourceIds: List<String>,
    val relevance: Double,
    val createdAt: Long
)

@Serializable
data class ResearchSession(
    val id: String,
    val query: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sources: List<ResearchSource> = emptyList(),
    val findings: List<ResearchFinding> = emptyList(),
    val comparison: String? = null,
    val report: ResearchReport? = null
)

@Serializable
data class ResearchReport(
    val title: String,
    val summary: String,
    val findings: List<String>,
    val comparison: String,
    val conclusion: String,
    val sources: List<ResearchSource>,
    val markdown: String
)

data class WebSearchResult(
    val url: String,
    val title: String,
    val snippet: String = "",
    val relevance: Double = 0.5
)

data class FetchedResearchPage(
    val finalUrl: String,
    val title: String,
    val text: String,
    val retrievedAt: Long = System.currentTimeMillis()
)

data class ResearchLimits(
    val maxSources: Int = 20,
    val maxSearchResults: Int = 10,
    val maxFindings: Int = 100,
    val maxExcerptChars: Int = 8_000,
    val maxFetchedTextChars: Int = 200_000,
    val maxQueryChars: Int = 1_000,
    val maxFindingChars: Int = 8_000,
    val maxTotalFindingChars: Int = 100_000
) {
    init {
        require(maxSources > 0 && maxSearchResults > 0 && maxFindings > 0)
        require(maxExcerptChars > 0 && maxFetchedTextChars > 0 && maxQueryChars > 0 && maxFindingChars > 0)
        require(maxTotalFindingChars >= maxFindingChars)
    }
}

open class ResearchError(message: String, cause: Throwable? = null) : Exception(message, cause)
class ResearchSessionNotFound(id: String) : ResearchError("Research session not found: $id")
class ResearchSourceUnavailable(url: String, cause: Throwable? = null) : ResearchError("Research source unavailable: $url", cause)
class ResearchSourceNotFound(id: String) : ResearchError("Research source not found in session: $id")
class ResearchLimitExceeded(limit: String) : ResearchError("Research limit exceeded: $limit")
class ResearchCitationError(reference: String) : ResearchError("Finding contains an untracked citation or URL: $reference")
class UnsafeResearchUrl(url: String) : ResearchError("Only safe HTTP(S) research URLs are supported: $url")
