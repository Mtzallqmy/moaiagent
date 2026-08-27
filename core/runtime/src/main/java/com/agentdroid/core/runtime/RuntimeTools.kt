package com.agentdroid.core.runtime

import com.agentdroid.core.agent.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

interface RuntimeServices {
    val processManager: ProcessManager
    val commandPolicy: CommandPolicy
    val limits: RuntimeLimits
    fun workspaceRoot(workspaceId: String): File
}

fun createRuntimeTools(services: RuntimeServices): List<AgentTool> = listOf(
    RunCommandTool(services),
    StartProcessTool(services),
    ProcessStatusTool(services),
    ProcessOutputTool(services),
    SendProcessInputTool(services),
    StopProcessTool(services),
    ListProcessesTool(services)
)

private abstract class CommandToolBase(protected val services: RuntimeServices) : AgentTool {
    override fun availableInMode(mode: AgentMode): Boolean = mode != AgentMode.CHAT

    protected fun assessment(input: JsonObject, context: ToolContext): CommandAssessment {
        val command = input.string("command") ?: throw ToolRegistryException(AgentError.validation("command is required"))
        val cwd = input.string("cwd") ?: "."
        val assessed = services.commandPolicy.assess(command, services.workspaceRoot(context.workspaceId), cwd)
        if (!assessed.allowed) throw ToolRegistryException(AgentError.commandBlocked(assessed.blockedReason ?: "Command blocked"))
        return assessed
    }

    override suspend fun permissionKey(input: JsonObject, context: ToolContext): String? = "${definition.name}:${assessment(input, context).pattern}"

    override fun auditInputSummary(input: JsonObject, context: ToolContext): String = buildJsonObject {
        put("command", CommandRedactor.redact(input.string("command").orEmpty()))
        put("cwd", input.string("cwd") ?: ".")
    }.toString()

    protected fun environment(root: File, cwd: File): Map<String, String> = buildMap {
        put("HOME", root.canonicalPath)
        put("PWD", cwd.canonicalPath)
        put("TMPDIR", File(root, ".agentdroid/tmp").apply { mkdirs() }.canonicalPath)
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
        val inherited = System.getenv("PATH").orEmpty()
        put("PATH", listOf(inherited, "/system/bin", "/system/xbin", "/vendor/bin", "/product/bin").filter { it.isNotBlank() }.joinToString(":"))
    }
}

private class RunCommandTool(services: RuntimeServices) : CommandToolBase(services) {
    override val definition = ToolDefinition(
        "run_command",
        "Run one bounded shell command inside the current workspace. The command is classified before permission and cannot use absolute/traversal paths or nested shell wrappers.",
        schema(
            required = listOf("command"),
            fields = mapOf("command" to "string", "cwd" to "string", "timeoutMs" to "integer", "reason" to "string")
        ),
        RiskLevel.SENSITIVE,
        ToolCategory.SHELL
    )

    override suspend fun effectiveRisk(input: JsonObject, context: ToolContext): RiskLevel = assessment(input, context).risk

    override suspend fun preview(input: JsonObject, context: ToolContext): ToolPreview {
        val assessed = assessment(input, context)
        val redacted = CommandRedactor.redact(input.string("command").orEmpty())
        return ToolPreview(
            summary = "▶ run_command $redacted",
            path = relativeCwd(services.workspaceRoot(context.workspaceId), assessed.cwd),
            metadata = mapOf("command" to redacted, "risk" to assessed.risk.name, "pattern" to assessed.pattern)
        )
    }

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val assessed = assessment(input, context)
        val timeout = (input["timeoutMs"]?.jsonPrimitive?.longOrNull ?: services.limits.defaultTimeoutMs).coerceIn(1, services.limits.maxRuntimeMs)
        val redacted = CommandRedactor.redact(input.string("command").orEmpty())
        val snapshot = try {
            services.processManager.runForeground(
                ProcessRequest(
                    command = input.string("command"),
                    cwd = assessed.cwd,
                    environment = environment(services.workspaceRoot(context.workspaceId), assessed.cwd),
                    timeoutMs = timeout,
                    maxStdoutBytes = services.limits.maxStdoutBytes,
                    maxStderrBytes = services.limits.maxStderrBytes
                ),
                context.workspaceId,
                context.sessionId
            )
        } catch (failure: ProcessLimitException) {
            return ToolResult.failure(AgentError(AgentErrorCode.PROCESS_LIMIT_REACHED, failure.message.orEmpty(), "The process limit was reached.", true))
        } catch (failure: Throwable) {
            return ToolResult.failure(AgentError.commandFailed(failure.message ?: failure::class.java.simpleName))
        }
        val output = processJson(snapshot, redacted, relativeCwd(services.workspaceRoot(context.workspaceId), assessed.cwd))
        return when {
            snapshot.timedOut -> ToolResult(false, "run_command timed out", output, AgentError.commandTimeout(timeout), truncated = snapshot.truncated)
            snapshot.status == ProcessStatus.FAILED -> ToolResult(false, "run_command failed", output, AgentError.commandFailed(snapshot.stderr.ifBlank { "Process failed" }), truncated = snapshot.truncated)
            snapshot.exitCode != 0 -> ToolResult(false, "run_command exit ${snapshot.exitCode}", output, AgentError.commandFailed("Command exited with ${snapshot.exitCode}"), truncated = snapshot.truncated)
            else -> ToolResult.success("run_command exit 0", output, truncated = snapshot.truncated)
        }
    }
}

private class StartProcessTool(services: RuntimeServices) : CommandToolBase(services) {
    override val definition = ToolDefinition(
        "start_process",
        "Start a bounded background process inside the workspace and return a process id.",
        schema(required = listOf("command"), fields = mapOf("command" to "string", "cwd" to "string", "timeoutMs" to "integer", "reason" to "string")),
        RiskLevel.MODIFY,
        ToolCategory.PROCESS
    )

    override fun availableInMode(mode: AgentMode): Boolean = mode == AgentMode.AGENT
    override suspend fun effectiveRisk(input: JsonObject, context: ToolContext): RiskLevel = maxRisk(RiskLevel.MODIFY, assessment(input, context).risk)

    override suspend fun preview(input: JsonObject, context: ToolContext): ToolPreview {
        val assessed = assessment(input, context)
        val command = CommandRedactor.redact(input.string("command").orEmpty())
        return ToolPreview("▶ start_process $command", relativeCwd(services.workspaceRoot(context.workspaceId), assessed.cwd), metadata = mapOf("command" to command))
    }

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val assessed = assessment(input, context)
        val timeout = (input["timeoutMs"]?.jsonPrimitive?.longOrNull ?: services.limits.maxRuntimeMs).coerceIn(1, services.limits.maxRuntimeMs)
        return try {
            val snapshot = services.processManager.startBackground(
                ProcessRequest(
                    command = input.string("command"),
                    cwd = assessed.cwd,
                    environment = environment(services.workspaceRoot(context.workspaceId), assessed.cwd),
                    timeoutMs = timeout
                ),
                context.workspaceId,
                context.sessionId
            )
            ToolResult.success("Background process started", processJson(snapshot, snapshot.command, relativeCwd(services.workspaceRoot(context.workspaceId), assessed.cwd)))
        } catch (failure: ProcessLimitException) {
            ToolResult.failure(AgentError(AgentErrorCode.PROCESS_LIMIT_REACHED, failure.message.orEmpty(), "The background process limit was reached.", true))
        } catch (failure: Throwable) {
            ToolResult.failure(AgentError.commandFailed(failure.message ?: failure::class.java.simpleName))
        }
    }
}

private class ProcessStatusTool(private val services: RuntimeServices) : AgentTool {
    override val definition = ToolDefinition("process_status", "Get status and metadata for a workspace process.", schema(listOf("processId"), mapOf("processId" to "string")), RiskLevel.SAFE, ToolCategory.PROCESS)
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val id = input.string("processId")!!
        val snapshot = services.processManager.get(id, context.workspaceId) ?: return ToolResult.failure(AgentError.processNotFound(id))
        return ToolResult.success("Process ${snapshot.status.name.lowercase()}", processJson(snapshot, snapshot.command, snapshot.cwd))
    }
}

private class ProcessOutputTool(private val services: RuntimeServices) : AgentTool {
    override val definition = ToolDefinition("process_output", "Read bounded stdout/stderr accumulated for a workspace process.", schema(listOf("processId"), mapOf("processId" to "string", "maxChars" to "integer")), RiskLevel.SAFE, ToolCategory.PROCESS)
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val id = input.string("processId")!!
        val snapshot = services.processManager.get(id, context.workspaceId) ?: return ToolResult.failure(AgentError.processNotFound(id))
        val maxChars = (input["maxChars"]?.jsonPrimitive?.longOrNull ?: 24_000L).coerceIn(1, 100_000).toInt()
        val clipped = snapshot.copy(stdout = snapshot.stdout.takeLast(maxChars), stderr = snapshot.stderr.takeLast(maxChars))
        return ToolResult.success("Process output", processJson(clipped, clipped.command, clipped.cwd), truncated = snapshot.stdout.length > maxChars || snapshot.stderr.length > maxChars || snapshot.truncated)
    }
}

private class SendProcessInputTool(private val services: RuntimeServices) : AgentTool {
    override val definition = ToolDefinition("send_process_input", "Send UTF-8 stdin to a running background process.", schema(listOf("processId", "input"), mapOf("processId" to "string", "input" to "string")), RiskLevel.MODIFY, ToolCategory.PROCESS)
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override fun auditInputSummary(input: JsonObject, context: ToolContext): String = buildJsonObject { put("processId", input.string("processId").orEmpty()); put("input", LogRedactor.redact(input.string("input").orEmpty()).take(500)) }.toString()
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val id = input.string("processId")!!
        val result = services.processManager.sendInput(id, context.workspaceId, input.string("input").orEmpty())
        return result.fold(
            onSuccess = { ToolResult.success("Input sent", buildJsonObject { put("processId", id) }) },
            onFailure = { ToolResult.failure(AgentError(AgentErrorCode.PROCESS_BROKEN_PIPE, it.message.orEmpty(), "Could not write to the process.", true)) }
        )
    }
}

private class StopProcessTool(private val services: RuntimeServices) : AgentTool {
    override val definition = ToolDefinition("stop_process", "Terminate or force-kill a workspace background process.", schema(listOf("processId"), mapOf("processId" to "string", "force" to "boolean")), RiskLevel.MODIFY, ToolCategory.PROCESS)
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val id = input.string("processId")!!
        val force = input["force"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (!services.processManager.stop(id, context.workspaceId, force)) return ToolResult.failure(AgentError.processNotFound(id))
        return ToolResult.success(if (force) "Process killed" else "Process terminated", buildJsonObject { put("processId", id) })
    }
}

private class ListProcessesTool(private val services: RuntimeServices) : AgentTool {
    override val definition = ToolDefinition("list_processes", "List foreground/background process metadata for the current workspace.", schema(), RiskLevel.SAFE, ToolCategory.PROCESS)
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val values = services.processManager.list(context.workspaceId)
        val output = buildJsonObject {
            put("processes", buildJsonArray {
                values.forEach { item -> add(buildJsonObject {
                    put("processId", item.processId); put("command", item.command); put("cwd", item.cwd); put("status", item.status.name); item.exitCode?.let { put("exitCode", it) }; put("startedAt", item.startedAt); put("background", item.background)
                }) }
            })
        }
        return ToolResult.success("${values.size} processes", output)
    }
}

private fun processJson(snapshot: ProcessSnapshot, command: String, cwd: String) = buildJsonObject {
    put("processId", snapshot.processId)
    snapshot.sessionId?.let { put("sessionId", it) }
    put("command", command)
    put("cwd", cwd)
    put("status", snapshot.status.name)
    put("stdout", snapshot.stdout)
    put("stderr", snapshot.stderr)
    snapshot.exitCode?.let { put("exitCode", it) }
    put("durationMs", snapshot.durationMs)
    put("timedOut", snapshot.timedOut)
    put("truncated", snapshot.truncated)
    put("background", snapshot.background)
}

private fun schema(required: List<String> = emptyList(), fields: Map<String, String> = emptyMap()) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { fields.forEach { (key, type) -> put(key, buildJsonObject { put("type", type) }) } })
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach(::add) })
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun relativeCwd(root: File, cwd: File): String = runCatching { cwd.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath.ifBlank { "." } }.getOrDefault(".")
private fun maxRisk(a: RiskLevel, b: RiskLevel): RiskLevel {
    fun rank(value: RiskLevel) = when (value) { RiskLevel.SAFE -> 0; RiskLevel.MODIFY -> 1; RiskLevel.EXTERNAL -> 2; RiskLevel.SENSITIVE -> 3; RiskLevel.DESTRUCTIVE -> 4 }
    return if (rank(a) >= rank(b)) a else b
}
