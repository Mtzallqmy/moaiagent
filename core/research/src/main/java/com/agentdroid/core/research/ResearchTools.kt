package com.agentdroid.core.research

/** Typed tool boundary used by ToolRegistry adapters. Every result comes from ResearchEngine state. */
sealed interface ResearchTool<I, O> {
    val name: String
    suspend fun execute(input: I): O
}

data class ResearchStartInput(val query: String)
data class ResearchSearchInput(val sessionId: String, val query: String? = null, val limit: Int? = null)
data class ResearchAddSourceInput(val sessionId: String, val url: String, val relevance: Double = 0.5)
data class ResearchExtractInput(val sessionId: String, val sourceId: String, val question: String)
data class ResearchCompareInput(val sessionId: String)
data class ResearchFinalizeInput(val sessionId: String, val title: String? = null)

class ResearchStartTool(private val engine: ResearchEngine) : ResearchTool<ResearchStartInput, ResearchSession> {
    override val name = "research_start"
    override suspend fun execute(input: ResearchStartInput) = engine.start(input.query)
}

class WebSearchTool(private val engine: ResearchEngine) : ResearchTool<ResearchSearchInput, List<WebSearchResult>> {
    override val name = "web_search"
    override suspend fun execute(input: ResearchSearchInput) = engine.search(input.sessionId, input.query, input.limit)
}

class ResearchAddSourceTool(private val engine: ResearchEngine) : ResearchTool<ResearchAddSourceInput, ResearchSource> {
    override val name = "research_add_source"
    override suspend fun execute(input: ResearchAddSourceInput) = engine.openSource(input.sessionId, input.url, input.relevance)
}

class ResearchExtractTool(private val engine: ResearchEngine) : ResearchTool<ResearchExtractInput, ResearchFinding> {
    override val name = "research_extract"
    override suspend fun execute(input: ResearchExtractInput) = engine.extract(input.sessionId, input.sourceId, input.question)
}

class ResearchCompareTool(private val engine: ResearchEngine) : ResearchTool<ResearchCompareInput, String> {
    override val name = "research_compare"
    override suspend fun execute(input: ResearchCompareInput) = engine.compare(input.sessionId)
}

class ResearchFinalizeTool(private val engine: ResearchEngine) : ResearchTool<ResearchFinalizeInput, ResearchReport> {
    override val name = "research_finalize"
    override suspend fun execute(input: ResearchFinalizeInput) = engine.finalize(input.sessionId, input.title)
}

data class ResearchToolSet(
    val start: ResearchStartTool,
    val search: WebSearchTool,
    val addSource: ResearchAddSourceTool,
    val extract: ResearchExtractTool,
    val compare: ResearchCompareTool,
    val finalize: ResearchFinalizeTool
)

fun createResearchTools(engine: ResearchEngine) = ResearchToolSet(
    ResearchStartTool(engine), WebSearchTool(engine), ResearchAddSourceTool(engine),
    ResearchExtractTool(engine), ResearchCompareTool(engine), ResearchFinalizeTool(engine)
)
