package com.agentdroid.core.research

import kotlinx.coroutines.sync.withLock

/**
 * Search backends implement this boundary; AgentDroid core is not tied to a vendor.
 * Implementations must honor [limit] and return summaries, not crawl result pages.
 */
interface WebSearchProvider {
    suspend fun search(query: String, limit: Int): List<WebSearchResult>
}

/** Fetches a single selected source. Implementations must enforce their own byte/time limits too. */
interface ResearchSourceFetcher {
    suspend fun fetch(url: String, maxChars: Int): FetchedResearchPage
}

interface ResearchSessionRepository {
    suspend fun create(session: ResearchSession)
    suspend fun get(sessionId: String): ResearchSession?
    suspend fun update(session: ResearchSession)
}

interface ResearchExtractor {
    fun extract(pageText: String, question: String, maxChars: Int): String
}

interface ResearchEngine {
    suspend fun start(query: String): ResearchSession
    suspend fun get(sessionId: String): ResearchSession
    suspend fun search(sessionId: String, query: String? = null, limit: Int? = null): List<WebSearchResult>
    suspend fun openSource(sessionId: String, url: String, relevance: Double = 0.5): ResearchSource
    suspend fun extract(sessionId: String, sourceId: String, question: String): ResearchFinding
    suspend fun addFinding(sessionId: String, text: String, sourceIds: List<String>, relevance: Double = 0.5): ResearchFinding
    suspend fun compare(sessionId: String): String
    suspend fun finalize(sessionId: String, title: String? = null): ResearchReport
}

class InMemoryResearchSessionRepository : ResearchSessionRepository {
    private val sessions = linkedMapOf<String, ResearchSession>()
    private val mutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun create(session: ResearchSession) = mutex.withLock {
        check(sessions.putIfAbsent(session.id, session) == null) {
            "Duplicate research session: ${session.id}"
        }
    }

    override suspend fun get(sessionId: String): ResearchSession? = mutex.withLock { sessions[sessionId] }

    override suspend fun update(session: ResearchSession) = mutex.withLock {
        check(sessions.containsKey(session.id)) { "Unknown research session: ${session.id}" }
        sessions[session.id] = session
    }
}
