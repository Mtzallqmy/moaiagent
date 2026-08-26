package com.agentdroid.core.agent

import com.agentdroid.core.model.Usage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class AgentMode { CHAT, PLAN, AGENT }

@Serializable
enum class RiskLevel { SAFE, MODIFY, DESTRUCTIVE, EXTERNAL, SENSITIVE }

@Serializable
enum class ToolCategory { FILE_READ, FILE_SEARCH, FILE_MODIFY, FILE_DESTRUCTIVE, WORKSPACE, EXTERNAL, SENSITIVE }

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val riskLevel: RiskLevel,
    val category: ToolCategory
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject,
    val rawArguments: String? = null
)

@Serializable
data class ToolPreview(
    val summary: String,
    val path: String? = null,
    val diff: String? = null,
    val changeSetId: String? = null
)

@Serializable
data class ToolResult(
    val success: Boolean,
    val summary: String,
    val output: JsonObject = JsonObject(emptyMap()),
    val error: AgentError? = null,
    val changeSetId: String? = null,
    val truncated: Boolean = false
) {
    companion object {
        fun success(summary: String, output: JsonObject = JsonObject(emptyMap()), changeSetId: String? = null, truncated: Boolean = false) =
            ToolResult(true, summary, output, changeSetId = changeSetId, truncated = truncated)

        fun failure(error: AgentError, output: JsonObject = JsonObject(emptyMap())) =
            ToolResult(false, error.userMessage, output, error = error)
    }
}

@Serializable
enum class AgentErrorCode {
    TOOL_NOT_FOUND,
    TOOL_VALIDATION_ERROR,
    PERMISSION_DENIED,
    WORKSPACE_VIOLATION,
    PATCH_CONFLICT,
    FILE_TOO_LARGE,
    BINARY_FILE_UNSUPPORTED,
    AGENT_TURN_LIMIT_REACHED,
    AGENT_TOOL_CALL_LIMIT_REACHED,
    AGENT_TIMEOUT,
    CONSECUTIVE_FAILURE_LIMIT_REACHED,
    MODE_RESTRICTION,
    IO_ERROR,
    PROVIDER_ERROR,
    INTERNAL_ERROR
}

@Serializable
data class AgentError(
    val code: AgentErrorCode,
    val technicalMessage: String,
    val userMessage: String,
    val recoverable: Boolean
) {
    companion object {
        fun toolNotFound(name: String) = AgentError(AgentErrorCode.TOOL_NOT_FOUND, "Tool not registered: $name", "The requested tool is not available.", false)
        fun validation(message: String) = AgentError(AgentErrorCode.TOOL_VALIDATION_ERROR, message, "The tool input is invalid.", true)
        fun permissionDenied(tool: String) = AgentError(AgentErrorCode.PERMISSION_DENIED, "Permission denied for $tool", "Permission was denied for this operation.", true)
        fun workspaceViolation(message: String) = AgentError(AgentErrorCode.WORKSPACE_VIOLATION, message, "The requested path is outside the workspace or is unsafe.", false)
        fun patchConflict(message: String) = AgentError(AgentErrorCode.PATCH_CONFLICT, message, "The file changed and the patch can no longer be applied safely.", true)
        fun fileTooLarge(limit: Long) = AgentError(AgentErrorCode.FILE_TOO_LARGE, "File exceeds $limit bytes", "The file is too large for this operation.", true)
        fun binaryUnsupported() = AgentError(AgentErrorCode.BINARY_FILE_UNSUPPORTED, "Binary content cannot be patched as text", "Binary files cannot be edited with text tools.", false)
        fun modeRestriction(tool: String, mode: AgentMode) = AgentError(AgentErrorCode.MODE_RESTRICTION, "$tool is not available in $mode mode", "This tool is not allowed in the current mode.", false)
        fun io(message: String) = AgentError(AgentErrorCode.IO_ERROR, message, "The file operation failed.", true)
        fun provider(message: String) = AgentError(AgentErrorCode.PROVIDER_ERROR, message, "The AI provider could not complete the agent turn.", true)
        fun internal(message: String) = AgentError(AgentErrorCode.INTERNAL_ERROR, message, "The agent encountered an internal error.", true)
    }
}

@Serializable
data class ToolContext(
    val workspaceId: String,
    val conversationId: String,
    val sessionId: String,
    val mode: AgentMode,
    val attributes: Map<String, String> = emptyMap()
)

interface AgentTool {
    val definition: ToolDefinition
    suspend fun preview(input: JsonObject, context: ToolContext): ToolPreview? = null
    suspend fun execute(input: JsonObject, context: ToolContext): ToolResult
}

class ToolRegistry(tools: Iterable<AgentTool> = emptyList()) {
    private val entries = ConcurrentHashMap<String, AgentTool>()

    init { tools.forEach(::register) }

    fun register(tool: AgentTool) {
        require(tool.definition.name.matches(Regex("[a-zA-Z0-9_.-]+"))) { "Invalid tool name ${tool.definition.name}" }
        val previous = entries.putIfAbsent(tool.definition.name, tool)
        require(previous == null) { "Tool already registered: ${tool.definition.name}" }
    }

    fun get(name: String): AgentTool? = entries[name]
    fun list(): List<ToolDefinition> = entries.values.map { it.definition }.sortedBy { it.name }

    fun toolsForMode(mode: AgentMode): List<ToolDefinition> = when (mode) {
        AgentMode.CHAT -> emptyList()
        AgentMode.PLAN -> list().filter { it.riskLevel == RiskLevel.SAFE }
        AgentMode.AGENT -> list()
    }

    suspend fun preview(call: ToolCall, context: ToolContext): Result<ToolPreview?> {
        val tool = entries[call.name] ?: return Result.failure(ToolRegistryException(AgentError.toolNotFound(call.name)))
        validateMode(tool.definition, context.mode)?.let { return Result.failure(ToolRegistryException(it)) }
        validate(tool.definition.inputSchema, call.input)?.let { return Result.failure(ToolRegistryException(it)) }
        return runCatching { tool.preview(call.input, context) }
    }

    suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        val tool = entries[call.name] ?: return ToolResult.failure(AgentError.toolNotFound(call.name))
        validateMode(tool.definition, context.mode)?.let { return ToolResult.failure(it) }
        validate(tool.definition.inputSchema, call.input)?.let { return ToolResult.failure(it) }
        return try {
            tool.execute(call.input, context)
        } catch (failure: ToolRegistryException) {
            ToolResult.failure(failure.agentError)
        } catch (failure: Throwable) {
            ToolResult.failure(AgentError.internal(failure.message ?: failure::class.java.simpleName))
        }
    }

    private fun validateMode(definition: ToolDefinition, mode: AgentMode): AgentError? = when {
        mode == AgentMode.CHAT -> AgentError.modeRestriction(definition.name, mode)
        mode == AgentMode.PLAN && definition.riskLevel != RiskLevel.SAFE -> AgentError.modeRestriction(definition.name, mode)
        else -> null
    }

    private fun validate(schema: JsonObject, input: JsonObject): AgentError? {
        val required = schema["required"] as? JsonArray
        required?.forEach { item ->
            val key = (item as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (!input.containsKey(key) || input[key] is JsonNull) return AgentError.validation("Missing required field: $key")
        }
        val properties = schema["properties"] as? JsonObject ?: return null
        for ((key, definitionElement) in properties) {
            val value = input[key] ?: continue
            if (value is JsonNull) continue
            val definition = definitionElement as? JsonObject ?: continue
            val expected = definition["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val valid = when (expected) {
                "string" -> value is JsonPrimitive && value.isString
                "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
                "integer" -> value is JsonPrimitive && value.content.toLongOrNull() != null
                "number" -> value is JsonPrimitive && value.doubleOrNull != null
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                else -> true
            }
            if (!valid) return AgentError.validation("Field '$key' must be $expected")
        }
        return null
    }
}

class ToolRegistryException(val agentError: AgentError) : IllegalStateException(agentError.technicalMessage)

@Serializable
enum class PermissionDecision { ALLOW, ASK, DENY }

@Serializable
enum class PermissionScope { ONCE, SESSION, ALWAYS }

@Serializable
data class PermissionRequest(
    val requestId: String,
    val toolCall: ToolCall,
    val definition: ToolDefinition,
    val workspaceId: String,
    val conversationId: String,
    val sessionId: String,
    val reason: String? = null,
    val preview: ToolPreview? = null
)

@Serializable
data class PermissionOutcome(
    val decision: PermissionDecision,
    val scope: PermissionScope = PermissionScope.ONCE,
    val source: String = "default"
)

interface PermissionGateway {
    suspend fun authorize(request: PermissionRequest): PermissionOutcome
    fun clearSession(sessionId: String) = Unit
}

@Serializable
data class AuditEntry(
    val toolCallId: String,
    val toolName: String,
    val inputSummary: String,
    val resultSummary: String,
    val durationMs: Long,
    val status: String,
    val permissionDecision: PermissionDecision,
    val timestamp: Long,
    val workspaceId: String,
    val conversationId: String
)

fun interface AuditSink {
    suspend fun record(entry: AuditEntry)

    companion object { val NOOP = AuditSink { } }
}

@Serializable
data class AgentConfig(
    val maxTurns: Int = 12,
    val maxToolCalls: Int = 32,
    val maxExecutionTimeMs: Long = 120_000,
    val maxConsecutiveFailures: Int = 4,
    val maxRepeatedFailureSignature: Int = 2,
    val contextCharacterBudget: Int = 60_000,
    val toolResultCharacterLimit: Int = 24_000
) {
    init {
        require(maxTurns > 0 && maxToolCalls > 0 && maxExecutionTimeMs > 0 && maxConsecutiveFailures > 0)
    }
}

@Serializable
data class AgentSession(
    val id: String,
    val conversationId: String,
    val workspaceId: String,
    val mode: AgentMode,
    val providerId: String,
    val modelId: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class AgentStepStatus { RUNNING, WAITING_PERMISSION, SUCCEEDED, FAILED }

@Serializable
data class AgentStep(
    val label: String,
    val status: AgentStepStatus,
    val toolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class AgentState(
    val session: AgentSession,
    val turn: Int = 0,
    val toolCalls: Int = 0,
    val consecutiveFailures: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val completed: Boolean = false,
    val lastError: AgentError? = null
)

sealed interface AgentEvent {
    data class StateChanged(val state: AgentState) : AgentEvent
    data class Timeline(val step: AgentStep) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ToolCallStarted(val call: ToolCall) : AgentEvent
    data class ToolCallArgumentsDelta(val toolCallId: String, val delta: String) : AgentEvent
    data class ToolCallCompleted(val call: ToolCall) : AgentEvent
    data class PermissionRequired(val request: PermissionRequest) : AgentEvent
    data class ToolFinished(val call: ToolCall, val result: ToolResult, val durationMs: Long) : AgentEvent
    data class FinalAnswer(val text: String) : AgentEvent
    data class Failed(val error: AgentError) : AgentEvent
    data object Done : AgentEvent
}

@Serializable
enum class AgentMessageRole { USER, ASSISTANT, TOOL }

@Serializable
data class AgentMessage(
    val role: AgentMessageRole,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null
)

@Serializable
data class AgentModelRequest(
    val systemPrompt: String,
    val messages: List<AgentMessage>,
    val tools: List<ToolDefinition>,
    val modelId: String
)

@Serializable
data class AgentModelResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: Usage? = null
)

sealed interface AgentModelEvent {
    data object Started : AgentModelEvent
    data class TextDelta(val text: String) : AgentModelEvent
    data class ToolCallStarted(val id: String, val name: String) : AgentModelEvent
    data class ToolCallDelta(val id: String, val argumentsDelta: String) : AgentModelEvent
    data class ToolCallCompleted(val call: ToolCall) : AgentModelEvent
}

interface AgentModelClient {
    val supportsToolCalling: Boolean
    suspend fun complete(request: AgentModelRequest, onEvent: suspend (AgentModelEvent) -> Unit = {}): Result<AgentModelResponse>
}

@Serializable
data class ContextSnapshot(
    val conversation: List<AgentMessage> = emptyList(),
    val workspaceSummary: String = "",
    val selectedFiles: List<ContextFile> = emptyList(),
    val memories: List<String> = emptyList(),
    val skills: List<String> = emptyList()
)

@Serializable
data class ContextFile(val path: String, val content: String, val truncated: Boolean = false)

fun interface ContextSource {
    suspend fun load(session: AgentSession): ContextSnapshot
}

class ContextManager(
    private val source: ContextSource,
    private val config: AgentConfig = AgentConfig()
) {
    suspend fun build(session: AgentSession, userRequest: String): Pair<String, MutableList<AgentMessage>> {
        val snapshot = source.load(session)
        var remaining = config.contextCharacterBudget.coerceAtLeast(4_000)
        val system = StringBuilder("You are AgentDroid. Follow the active mode and use only advertised tools. Never invent tool results.\n")

        fun appendSection(title: String, values: Iterable<String>) {
            val joined = values.filter { it.isNotBlank() }.joinToString("\n\n")
            if (joined.isBlank() || remaining <= 0) return
            val clipped = joined.take(remaining)
            system.append("\n## ").append(title).append('\n').append(clipped).append('\n')
            remaining -= clipped.length
        }

        appendSection("Active skills", snapshot.skills)
        appendSection("Relevant memory", snapshot.memories)
        appendSection("Workspace summary", listOf(snapshot.workspaceSummary))
        snapshot.selectedFiles.forEach { file ->
            if (remaining <= 0) return@forEach
            val header = "\n## Selected file: ${file.path}${if (file.truncated) " (truncated)" else ""}\n"
            val content = file.content.take((remaining - header.length).coerceAtLeast(0))
            system.append(header).append(content).append('\n')
            remaining -= header.length + content.length
        }

        val messages = snapshot.conversation.toMutableList()
        messages += AgentMessage(AgentMessageRole.USER, userRequest)
        return system.toString() to messages
    }
}
