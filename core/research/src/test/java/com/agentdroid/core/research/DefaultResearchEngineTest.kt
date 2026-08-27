package com.agentdroid.core.research

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultResearchEngineTest {
    private val provider = object : WebSearchProvider {
        override suspend fun search(query: String, limit: Int) = listOf(
            WebSearchResult("https://docs.example.com/a#fragment", "A", "first", 0.9),
            WebSearchResult("javascript:alert(1)", "unsafe", "bad", 1.0),
            WebSearchResult("https://other.example/b", "B", "second", 0.7)
        ).take(limit)
    }
    private val fetcher = object : ResearchSourceFetcher {
        override suspend fun fetch(url: String, maxChars: Int) = FetchedResearchPage(
            finalUrl = url,
            title = if (url.contains("other")) "Other evidence" else "Primary evidence",
            text = if (url.contains("other")) "Library B is stable and small." else "Library A supports Markdown and Android. It is actively maintained.",
            retrievedAt = 123L
        )
    }

    @Test
    fun `tracks retrieved sources through finding and finalized citations`() = runBlocking {
        val ids = ArrayDeque(listOf("session", "source-a", "finding-a"))
        val engine = DefaultResearchEngine(provider, fetcher, now = { 123L }, newId = { ids.removeFirst() })
        val session = engine.start("Android Markdown libraries")
        val results = engine.search(session.id)
        assertEquals(2, results.size)
        assertTrue(results.none { it.url.startsWith("javascript:") })

        val source = engine.openSource(session.id, results.first().url, results.first().relevance)
        val finding = engine.extract(session.id, source.id, "Markdown Android")
        val report = engine.finalize(session.id, "Android Markdown")

        assertEquals(listOf(source.id), finding.sourceIds)
        assertEquals(listOf(source), report.sources)
        assertTrue(report.markdown.contains("## Findings"))
        assertTrue(report.markdown.contains("[1]"))
        assertTrue(report.markdown.contains("https://docs.example.com/a#fragment"))
        assertFalse(report.markdown.contains("javascript:"))
    }

    @Test(expected = ResearchCitationError::class)
    fun `rejects invented source references`() = runBlocking {
        val engine = DefaultResearchEngine(provider, fetcher)
        val session = engine.start("query")
        engine.addFinding(session.id, "Unsupported claim", listOf("invented-source"))
    }

    @Test(expected = ResearchCitationError::class)
    fun `rejects invented inline url even when source id is valid`() = runBlocking {
        val engine = DefaultResearchEngine(provider, fetcher)
        val session = engine.start("query")
        val source = engine.openSource(session.id, "https://example.com/real")
        engine.addFinding(session.id, "Claim from https://invented.example/report", listOf(source.id))
    }

    @Test(expected = ResearchCitationError::class)
    fun `rejects agent supplied numeric citation markers`() = runBlocking {
        val engine = DefaultResearchEngine(provider, fetcher)
        val session = engine.start("query")
        val source = engine.openSource(session.id, "https://example.com/real")
        engine.addFinding(session.id, "Claim [99]", listOf(source.id))
    }

    @Test(expected = UnsafeResearchUrl::class)
    fun `rejects non http sources before fetch`() = runBlocking {
        val engine = DefaultResearchEngine(provider, fetcher)
        val session = engine.start("query")
        engine.openSource(session.id, "file:///etc/passwd")
    }

    @Test
    fun `compare only names sources actually linked to each finding`() = runBlocking {
        val ids = ArrayDeque(listOf("session", "source-a", "source-b", "finding-a", "finding-b"))
        val engine = DefaultResearchEngine(provider, fetcher, now = { 1L }, newId = { ids.removeFirst() })
        val session = engine.start("compare")
        val a = engine.openSource(session.id, "https://docs.example.com/a")
        val b = engine.openSource(session.id, "https://other.example/b")
        engine.addFinding(session.id, "A finding", listOf(a.id), 0.9)
        engine.addFinding(session.id, "B finding", listOf(b.id), 0.8)

        val comparison = engine.compare(session.id)
        assertTrue(comparison.contains("A finding"))
        assertTrue(comparison.contains("[docs.example.com](https://docs.example.com/a)"))
        assertTrue(comparison.contains("B finding"))
        assertTrue(comparison.contains("[other.example](https://other.example/b)"))
    }

    @Test
    fun `deduplicates a source by canonical url`() = runBlocking {
        var fetchCount = 0
        val countingFetcher = object : ResearchSourceFetcher {
            override suspend fun fetch(url: String, maxChars: Int): FetchedResearchPage {
                fetchCount++
                return FetchedResearchPage(url, "Title", "Text")
            }
        }
        val engine = DefaultResearchEngine(provider, countingFetcher)
        val session = engine.start("query")
        val first = engine.openSource(session.id, "https://EXAMPLE.com/path#one")
        val second = engine.openSource(session.id, "https://example.com/path#two")
        assertEquals(first.id, second.id)
        assertEquals(1, fetchCount)
    }
}
