package com.agentdroid.core.research

import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCall
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchLimitsAndToolsTest {
    private val provider = object : WebSearchProvider {
        override suspend fun search(query: String, limit: Int) = (1..20).map {
            WebSearchResult("https://example.com/$it", "Result $it", "x".repeat(100), 2.0)
        }
    }
    private val fetcher = object : ResearchSourceFetcher {
        override suspend fun fetch(url: String, maxChars: Int) =
            FetchedResearchPage(url, "Title", "Relevant sentence. " + "z".repeat(maxChars + 100))
    }

    @Test
    fun `bounds provider results excerpts and relevance`() = runBlocking {
        val engine = DefaultResearchEngine(
            provider, fetcher,
            limits = ResearchLimits(maxSources = 2, maxSearchResults = 3, maxExcerptChars = 12, maxFetchedTextChars = 30)
        )
        val session = engine.start("bounded")
        val results = engine.search(session.id, limit = 99)
        assertEquals(3, results.size)
        assertTrue(results.all { it.snippet.length <= 12 && it.relevance == 1.0 })
        val source = engine.openSource(session.id, results.first().url, 4.0)
        assertTrue(source.excerpt.length <= 12)
        assertEquals(1.0, source.relevance, 0.0)
    }

    @Test(expected = ResearchLimitExceeded::class)
    fun `enforces maximum source count`() = runBlocking {
        val engine = DefaultResearchEngine(provider, fetcher, limits = ResearchLimits(maxSources = 1))
        val session = engine.start("bounded")
        engine.openSource(session.id, "https://example.com/1")
        engine.openSource(session.id, "https://example.com/2")
    }

    @Test(expected = ResearchLimitExceeded::class)
    fun `enforces maximum finding count`() = runBlocking {
        val engine = DefaultResearchEngine(provider, fetcher, limits = ResearchLimits(maxFindings = 1))
        val session = engine.start("bounded")
        val source = engine.openSource(session.id, "https://example.com/1")
        engine.addFinding(session.id, "one", listOf(source.id))
        engine.addFinding(session.id, "two", listOf(source.id))
    }

    @Test(expected = ResearchLimitExceeded::class)
    fun `enforces total extracted text budget`() = runBlocking {
        val engine = DefaultResearchEngine(
            provider, fetcher,
            limits = ResearchLimits(maxFindingChars = 5, maxTotalFindingChars = 6)
        )
        val session = engine.start("bounded")
        val source = engine.openSource(session.id, "https://example.com/1")
        engine.addFinding(session.id, "12345", listOf(source.id))
        engine.addFinding(session.id, "67", listOf(source.id))
    }

    @Test
    fun `typed tools execute complete provider fetch extract compare finalize flow`() = runBlocking {
        val engine = DefaultResearchEngine(provider, fetcher)
        val tools = createResearchTools(engine)
        val session = tools.start.execute(ResearchStartInput("libraries"))
        val result = tools.search.execute(ResearchSearchInput(session.id, limit = 1)).single()
        val source = tools.addSource.execute(ResearchAddSourceInput(session.id, result.url, result.relevance))
        tools.extract.execute(ResearchExtractInput(session.id, source.id, "relevant"))
        assertTrue(tools.compare.execute(ResearchCompareInput(session.id)).isNotBlank())
        val report = tools.finalize.execute(ResearchFinalizeInput(session.id))
        assertTrue(report.markdown.contains("## Sources"))
        assertEquals("research_finalize", tools.finalize.name)
    }

    @Test
    fun `agent adapters expose real tools and classify network operations external`() = runBlocking {
        val engine = DefaultResearchEngine(provider, fetcher)
        val registry = ToolRegistry(createResearchAgentTools(engine))
        val context = ToolContext("workspace", "conversation", "agent-session", AgentMode.AGENT)
        val start = registry.execute(
            ToolCall("1", "research_start", buildJsonObject { put("query", "libraries") }), context
        )
        assertTrue(start.success)
        val researchId = start.output["sessionId"]!!.toString().trim('"')
        val searchCall = ToolCall("2", "web_search", buildJsonObject { put("sessionId", researchId); put("limit", 1) })
        assertEquals(RiskLevel.EXTERNAL, registry.effectiveRisk(searchCall, context).getOrThrow())
        assertTrue(registry.execute(searchCall, context).success)
    }
}
