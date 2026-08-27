package com.agentdroid.core.research

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.AgentTool
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCategory
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolDefinition
import com.agentdroid.core.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun createResearchAgentTools(engine: ResearchEngine): List<AgentTool> = listOf(
    ResearchStartAgentTool(engine), WebSearchAgentTool(engine), ResearchAddSourceAgentTool(engine),
    ResearchExtractAgentTool(engine), ResearchCompareAgentTool(engine), ResearchFinalizeAgentTool(engine)
)

private abstract class EngineAgentTool(protected val engine: ResearchEngine) : AgentTool {
    protected suspend fun result(block: suspend () -> ToolResult): ToolResult = try {
        block()
    } catch (error: ResearchSourceUnavailable) {
        ToolResult.failure(AgentError.io(error.message ?: "Research source unavailable"))
    } catch (error: ResearchError) {
        ToolResult.failure(AgentError.validation(error.message ?: "Research operation failed"))
    } catch (error: IllegalArgumentException) {
        ToolResult.failure(AgentError.validation(error.message ?: "Invalid research input"))
    }
}

private class ResearchStartAgentTool(engine: ResearchEngine) : EngineAgentTool(engine) {
    override val definition = definition("research_start", "Start a bounded, source-tracked research session.", RiskLevel.SAFE, listOf("query"), "query" to "string")
    override suspend fun execute(input: JsonObject, context: ToolContext) = result {
        val session = engine.start(input.string("query"))
        ToolResult.success("Research session started", buildJsonObject { put("sessionId", session.id); put("query", session.query) })
    }
}

private class WebSearchAgentTool(engine: ResearchEngine) : EngineAgentTool(engine) {
    override val definition = definition("web_search", "Search through the configured vendor-neutral web search provider.", RiskLevel.EXTERNAL, listOf("sessionId"), "sessionId" to "string", "query" to "string", "limit" to "integer")
    override suspend fun execute(input: JsonObject, context: ToolContext) = result {
        val results = engine.search(input.string("sessionId"), input.optionalString("query"), input["limit"]?.jsonPrimitive?.intOrNull)
        ToolResult.success("Found ${results.size} candidate sources", buildJsonObject {
            put("results", buildJsonArray { results.forEach { item -> add(buildJsonObject { put("url", item.url); put("title", item.title); put("snippet", item.snippet); put("relevance", item.relevance) }) } })
        })
    }
}

private class ResearchAddSourceAgentTool(engine: ResearchEngine) : EngineAgentTool(engine) {
    override val definition = definition("research_add_source", "Retrieve one selected HTTP(S) source and record bounded source metadata.", RiskLevel.EXTERNAL, listOf("sessionId", "url"), "sessionId" to "string", "url" to "string", "relevance" to "number")
    override suspend fun execute(input: JsonObject, context: ToolContext) = result {
        val source = engine.openSource(input.string("sessionId"), input.string("url"), input["relevance"]?.jsonPrimitive?.doubleOrNull ?: 0.5)
        ToolResult.success("Retrieved ${source.domain}", sourceJson(source))
    }
}

private class ResearchExtractAgentTool(engine: ResearchEngine) : EngineAgentTool(engine) {
    override val definition = definition("research_extract", "Extract a bounded finding from a previously retrieved source.", RiskLevel.SAFE, listOf("sessionId", "sourceId", "question"), "sessionId" to "string", "sourceId" to "string", "question" to "string")
    override suspend fun execute(input: JsonObject, context: ToolContext) = result {
        val finding = engine.extract(input.string("sessionId"), input.string("sourceId"), input.string("question"))
        ToolResult.success("Extracted a source-backed finding", findingJson(finding))
    }
}

private class ResearchCompareAgentTool(engine: ResearchEngine) : EngineAgentTool(engine) {
    override val definition = definition("research_compare", "Compare findings while preserving their source links.", RiskLevel.SAFE, listOf("sessionId"), "sessionId" to "string")
    override suspend fun execute(input: JsonObject, context: ToolContext) = result {
        val comparison = engine.compare(input.string("sessionId"))
        ToolResult.success("Research comparison created", buildJsonObject { put("comparison", comparison) })
    }
}

private class ResearchFinalizeAgentTool(engine: ResearchEngine) : EngineAgentTool(engine) {
    override val definition = definition("research_finalize", "Create a structured research report using only recorded sources.", RiskLevel.SAFE, listOf("sessionId"), "sessionId" to "string", "title" to "string")
    override suspend fun execute(input: JsonObject, context: ToolContext) = result {
        val report = engine.finalize(input.string("sessionId"), input.optionalString("title"))
        ToolResult.success("Research report finalized", buildJsonObject {
            put("title", report.title); put("markdown", report.markdown)
            put("sourceReferences", buildJsonArray { report.sources.forEach { add(sourceJson(it)) } })
        })
    }
}

private fun definition(name: String, description: String, risk: RiskLevel, required: List<String>, vararg fields: Pair<String, String>) = ToolDefinition(
    name, description, schema(required, fields.toMap()), risk, ToolCategory.EXTERNAL
)

private fun schema(required: List<String>, fields: Map<String, String>) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { fields.forEach { (name, type) -> put(name, buildJsonObject { put("type", type) }) } })
    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
}

private fun sourceJson(source: ResearchSource) = buildJsonObject {
    put("id", source.id); put("url", source.url); put("title", source.title); put("domain", source.domain)
    put("retrievedAt", source.retrievedAt); put("excerpt", source.excerpt); put("relevance", source.relevance)
}

private fun findingJson(finding: ResearchFinding) = buildJsonObject {
    put("id", finding.id); put("text", finding.text); put("relevance", finding.relevance); put("createdAt", finding.createdAt)
    put("sourceIds", buildJsonArray { finding.sourceIds.forEach { add(JsonPrimitive(it)) } })
}

private fun JsonObject.string(key: String): String = optionalString(key)?.takeIf { it.isNotBlank() }
    ?: throw IllegalArgumentException("$key is required")
private fun JsonObject.optionalString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
