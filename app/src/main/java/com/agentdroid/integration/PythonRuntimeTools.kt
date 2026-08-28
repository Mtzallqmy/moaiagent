package com.agentdroid.integration

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.AgentTool
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCategory
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolDefinition
import com.agentdroid.core.agent.ToolPreview
import com.agentdroid.core.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

fun createPythonRuntimeTools(runtime: EmbeddedPythonRuntime): List<AgentTool> = listOf(
    PythonVersionTool(runtime), PythonRunTool(runtime), PythonInstallPackageTool(runtime)
)

private class PythonVersionTool(private val runtime: EmbeddedPythonRuntime) : AgentTool {
    override val definition = ToolDefinition(
        "python_version", "Read the embedded Python runtime version.", schema(emptyList(), emptyList()),
        RiskLevel.SAFE, ToolCategory.RUNTIME
    )
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult = runCatching { runtime.version() }
        .fold({ ToolResult.success("Python $it", buildJsonObject { put("version", it); put("runtime", "embedded") }) },
            { ToolResult.failure(AgentError.commandFailed(it.message ?: "Python runtime unavailable")) })
}

private class PythonRunTool(private val runtime: EmbeddedPythonRuntime) : AgentTool {
    override val definition = ToolDefinition(
        "python_run", "Run bounded Python code or a workspace .py file using AgentDroid's embedded Python runtime.",
        schema(emptyList(), listOf("code", "path", "timeoutMs")), RiskLevel.MODIFY, ToolCategory.RUNTIME
    )
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override suspend fun permissionKey(input: JsonObject, context: ToolContext) = "python_run:${context.workspaceId}"
    override fun auditInputSummary(input: JsonObject, context: ToolContext): String =
        "python runtime execution in workspace ${context.workspaceId}; code redacted; bytes=${input.string("code")?.toByteArray()?.size ?: 0}"
    override suspend fun preview(input: JsonObject, context: ToolContext) = ToolPreview("Run Python inside the selected workspace", input.string("path"))
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult = runCatching {
        val timeout = input.long("timeoutMs") ?: 30_000
        val path = input.string("path")
        val code = input.string("code")
        require(!path.isNullOrBlank() || !code.isNullOrBlank()) { "Provide code or path" }
        require(path.isNullOrBlank() || code.isNullOrBlank()) { "Provide code or path, not both" }
        val result = if (!path.isNullOrBlank()) runtime.runFile(context.workspaceId, path, timeout)
        else runtime.runCode(context.workspaceId, checkNotNull(code), timeout)
        val output = buildJsonObject {
            put("stdout", result.stdout.take(1_048_576)); put("stderr", result.stderr.take(524_288)); put("exitCode", result.exitCode)
            put("timedOut", result.timedOut); result.error?.let { put("error", it) }
        }
        if (result.exitCode == 0) ToolResult.success("Python completed", output, truncated = result.stdout.length > 1_048_576 || result.stderr.length > 524_288)
        else ToolResult.failure(AgentError.commandFailed(result.error ?: "Python exited ${result.exitCode}"), output)
    }.getOrElse { ToolResult.failure(AgentError.commandFailed(it.message ?: "Python execution failed")) }
}

private class PythonInstallPackageTool(private val runtime: EmbeddedPythonRuntime) : AgentTool {
    override val definition = ToolDefinition(
        "python_install_package", "Install a Python package into this workspace when an Android-compatible distribution is available.",
        schema(listOf("package"), listOf("package")), RiskLevel.EXTERNAL, ToolCategory.RUNTIME
    )
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override suspend fun permissionKey(input: JsonObject, context: ToolContext) = "python_install_package:${input.string("package").orEmpty()}"
    override fun auditInputSummary(input: JsonObject, context: ToolContext) = "python package install: ${input.string("package").orEmpty()}"
    override suspend fun preview(input: JsonObject, context: ToolContext) = ToolPreview("Download/install Python package into workspace", ".agentdroid/python-packages")
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult = runCatching {
        val result = runtime.installPackage(context.workspaceId, requireNotNull(input.string("package")))
        val output = buildJsonObject { put("stdout", result.stdout.take(1_048_576)); put("stderr", result.stderr.take(524_288)); put("exitCode", result.exitCode) }
        if (result.exitCode == 0) ToolResult.success("Python package installed", output)
        else ToolResult.failure(AgentError.commandFailed(result.error ?: "pip install failed"), output)
    }.getOrElse { ToolResult.failure(AgentError.commandFailed(it.message ?: "pip install failed")) }
}

private fun schema(required: List<String>, fields: List<String>) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        fields.forEach { name -> put(name, buildJsonObject { put("type", if (name == "timeoutMs") "integer" else "string") }) }
    })
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
}
private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
