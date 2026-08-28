package com.agentdroid.core.research

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.Locale
import java.util.UUID

class DefaultResearchEngine(
    private val searchProvider: WebSearchProvider,
    private val sourceFetcher: ResearchSourceFetcher,
    private val repository: ResearchSessionRepository = InMemoryResearchSessionRepository(),
    private val extractor: ResearchExtractor = RelevantTextExtractor(),
    private val limits: ResearchLimits = ResearchLimits(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) : ResearchEngine {
    private val mutation = Mutex()

    override suspend fun start(query: String): ResearchSession {
        val clean = query.trim()
        require(clean.isNotEmpty()) { "Research query is required" }
        if (clean.length > limits.maxQueryChars) throw ResearchLimitExceeded("query length")
        val timestamp = now()
        val session = ResearchSession(newId(), clean, timestamp, timestamp)
        repository.create(session)
        return session
    }

    override suspend fun get(sessionId: String): ResearchSession =
        repository.get(sessionId) ?: throw ResearchSessionNotFound(sessionId)

    override suspend fun search(sessionId: String, query: String?, limit: Int?): List<WebSearchResult> {
        val session = get(sessionId)
        val clean = (query ?: session.query).trim()
        require(clean.isNotEmpty()) { "Search query is required" }
        if (clean.length > limits.maxQueryChars) throw ResearchLimitExceeded("query length")
        val boundedLimit = (limit ?: limits.maxSearchResults).coerceIn(1, limits.maxSearchResults)
        return searchProvider.search(clean, boundedLimit)
            .asSequence()
            .filter { runCatching { validateHttpUrl(it.url) }.isSuccess }
            .distinctBy { canonicalUrl(it.url) }
            .take(boundedLimit)
            .map { it.copy(snippet = bound(it.snippet, limits.maxExcerptChars), relevance = relevance(it.relevance)) }
            .toList()
    }

    override suspend fun openSource(sessionId: String, url: String, relevance: Double): ResearchSource {
        val safeUrl = validateHttpUrl(url)
        get(sessionId).sources.firstOrNull { canonicalUrl(it.url) == canonicalUrl(safeUrl) }?.let { return it }
        val page = try {
            sourceFetcher.fetch(safeUrl, limits.maxFetchedTextChars)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw ResearchSourceUnavailable(safeUrl, error)
        }
        val finalUrl = validateHttpUrl(page.finalUrl)
        val boundedText = bound(page.text, limits.maxFetchedTextChars)
        if (boundedText.isBlank()) throw ResearchSourceUnavailable(finalUrl)
        val source = ResearchSource(
            id = newId(),
            url = finalUrl,
            title = bound(page.title.trim().ifBlank { URI(finalUrl).host }, 500),
            domain = URI(finalUrl).host.lowercase(Locale.ROOT),
            retrievedAt = page.retrievedAt,
            excerpt = bound(boundedText.trim(), limits.maxExcerptChars),
            relevance = relevance(relevance)
        )
        mutation.withLock {
            val current = get(sessionId)
            current.sources.firstOrNull { canonicalUrl(it.url) == canonicalUrl(source.url) }?.let { return it }
            if (current.sources.size >= limits.maxSources) throw ResearchLimitExceeded("max sources (${limits.maxSources})")
            repository.update(current.copy(sources = current.sources + source, updatedAt = now()))
        }
        return source
    }

    override suspend fun extract(sessionId: String, sourceId: String, question: String): ResearchFinding {
        val session = get(sessionId)
        val source = session.sources.firstOrNull { it.id == sourceId } ?: throw ResearchSourceNotFound(sourceId)
        val text = extractor.extract(source.excerpt, question.trim().ifBlank { session.query }, limits.maxFindingChars)
        require(text.isNotBlank()) { "No relevant text could be extracted" }
        return addFinding(sessionId, text, listOf(source.id), source.relevance)
    }

    override suspend fun addFinding(sessionId: String, text: String, sourceIds: List<String>, relevance: Double): ResearchFinding {
        val clean = bound(text.trim().replace(Regex("\\s+"), " "), limits.maxFindingChars)
        require(clean.isNotEmpty()) { "Finding text is required" }
        require(sourceIds.isNotEmpty()) { "At least one source is required" }
        return mutation.withLock {
            val current = get(sessionId)
            val known = current.sources.mapTo(hashSetOf()) { it.id }
            sourceIds.distinct().firstOrNull { it !in known }?.let { throw ResearchCitationError(it) }
            validateInlineReferences(clean, current)
            if (current.findings.size >= limits.maxFindings) throw ResearchLimitExceeded("max findings (${limits.maxFindings})")
            if (current.findings.sumOf { it.text.length } + clean.length > limits.maxTotalFindingChars) {
                throw ResearchLimitExceeded("total finding text (${limits.maxTotalFindingChars} chars)")
            }
            val finding = ResearchFinding(newId(), clean, sourceIds.distinct(), relevance(relevance), now())
            repository.update(current.copy(findings = current.findings + finding, updatedAt = now()))
            finding
        }
    }

    override suspend fun compare(sessionId: String): String = mutation.withLock {
        val session = get(sessionId)
        require(session.findings.isNotEmpty()) { "At least one finding is required for comparison" }
        validateCitations(session)
        val sourceById = session.sources.associateBy { it.id }
        val comparison = session.findings
            .sortedByDescending { it.relevance }
            .joinToString("\n") { finding ->
                val citations = finding.sourceIds.joinToString(", ") { id ->
                    val source = sourceById.getValue(id)
                    "[${source.domain}](${source.url})"
                }
                "- ${finding.text} — $citations"
            }
        repository.update(session.copy(comparison = comparison, updatedAt = now()))
        comparison
    }

    override suspend fun finalize(sessionId: String, title: String?): ResearchReport = mutation.withLock {
        val session = get(sessionId)
        require(session.sources.isNotEmpty()) { "At least one retrieved source is required" }
        require(session.findings.isNotEmpty()) { "At least one sourced finding is required" }
        validateCitations(session)
        val orderedSources = session.sources.sortedByDescending { it.relevance }
        val sourceNumbers = orderedSources.mapIndexed { index, source -> source.id to index + 1 }.toMap()
        val findingLines = session.findings.sortedByDescending { it.relevance }.map { finding ->
            val marks = finding.sourceIds.joinToString("") { "[${sourceNumbers.getValue(it)}]" }
            "${finding.text} $marks"
        }
        val comparison = session.comparison ?: findingLines.joinToString("\n") { "- $it" }
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: "Research: ${session.query}"
        val summary = findingLines.take(3).joinToString(" ").take(2_000)
        val conclusion = findingLines.first()
        val markdown = buildString {
            appendLine("# $cleanTitle")
            appendLine(); appendLine("## Summary"); appendLine(summary)
            appendLine(); appendLine("## Findings"); findingLines.forEach { appendLine("- $it") }
            appendLine(); appendLine("## Comparison"); appendLine(comparison)
            appendLine(); appendLine("## Conclusion"); appendLine(conclusion)
            appendLine(); appendLine("## Sources")
            orderedSources.forEachIndexed { index, source ->
                appendLine("${index + 1}. [${source.title}](${source.url}) — ${source.domain}; retrieved ${source.retrievedAt}")
            }
        }
        val report = ResearchReport(cleanTitle, summary, findingLines, comparison, conclusion, orderedSources, markdown)
        repository.update(session.copy(comparison = comparison, report = report, updatedAt = now()))
        report
    }

    private fun validateCitations(session: ResearchSession) {
        val known = session.sources.mapTo(hashSetOf()) { it.id }
        session.findings.flatMap { it.sourceIds }.firstOrNull { it !in known }?.let { throw ResearchCitationError(it) }
    }

    private fun validateInlineReferences(text: String, session: ResearchSession) {
        Regex("\\[(\\d+)]").find(text)?.let { throw ResearchCitationError(it.value) }
        val knownUrls = session.sources.mapTo(hashSetOf()) { canonicalUrl(it.url) }
        Regex("https?://[^\\s)>]+", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            val candidate = match.value.trimEnd('.', ',', ';', ':', '!', '?')
            val canonical = runCatching { canonicalUrl(candidate) }.getOrElse { throw ResearchCitationError(candidate) }
            if (canonical !in knownUrls) throw ResearchCitationError(candidate)
        }
    }
}

class RelevantTextExtractor : ResearchExtractor {
    override fun extract(pageText: String, question: String, maxChars: Int): String {
        val terms = question.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }.toSet()
        val blocks = pageText.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map(String::trim).filter(String::isNotEmpty)
        val ranked = blocks.mapIndexed { index, text ->
            val lower = text.lowercase(Locale.ROOT)
            Triple(text, terms.count { term -> lower.contains(term) }, index)
        }.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
        val useful = if (ranked.any { it.second > 0 }) ranked.filter { it.second > 0 } else ranked
        return bound(useful.take(8).joinToString(" ") { it.first }, maxChars)
    }
}

internal fun validateHttpUrl(raw: String): String {
    val uri = runCatching { URI(raw.trim()) }.getOrElse { throw UnsafeResearchUrl(raw) }
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
        throw UnsafeResearchUrl(raw)
    }
    return uri.normalize().toASCIIString()
}

private fun canonicalUrl(raw: String): String {
    val uri = URI(validateHttpUrl(raw))
    return URI(uri.scheme.lowercase(Locale.ROOT), null, uri.host.lowercase(Locale.ROOT), uri.port, uri.path, uri.query, null).toASCIIString()
}

internal fun bound(value: String, maxChars: Int): String = if (value.length <= maxChars) value else value.take(maxChars)
private fun relevance(value: Double): Double = if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0
