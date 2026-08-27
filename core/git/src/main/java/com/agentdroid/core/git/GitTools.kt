package com.agentdroid.core.git

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.AgentErrorCode
import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.AgentTool
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCategory
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolDefinition
import com.agentdroid.core.agent.ToolPreview
import com.agentdroid.core.agent.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

interface GitServices {
    val engine: GitEngine
    fun workspaceRoot(workspaceId: String): File
}

fun createGitTools(services: GitServices): List<AgentTool> = listOf(
    GitStatusTool(services), GitDiffTool(services), GitLogTool(services), GitBranchesTool(services),
    GitCheckoutTool(services), GitAddTool(services), GitCommitTool(services), GitRestoreTool(services), GitInitTool(services)
)

private abstract class GitTool(protected val services: GitServices) : AgentTool {
    protected fun root(context: ToolContext): File = services.workspaceRoot(context.workspaceId).canonicalFile
    protected fun failure(action: String, error: Throwable): ToolResult = ToolResult.failure(
        AgentError(AgentErrorCode.GIT_ERROR, "$action: ${error.message ?: error::class.java.simpleName}", "The Git operation failed.", true)
    )
    protected fun notRepo(): ToolResult = ToolResult.failure(AgentError(AgentErrorCode.GIT_NOT_REPOSITORY, "Workspace is not a Git repository", "This workspace is not a Git repository.", true))
    protected fun base(action: String) = buildJsonObject { put("gitAction", action) }
}

private class GitStatusTool(services: GitServices) : GitTool(services) {
    override val definition = ToolDefinition("git_status", "Read Git repository status for the workspace.", schema(), RiskLevel.SAFE, ToolCategory.GIT_READ)
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val root = root(context)
        if (!services.engine.isRepository(root)) return notRepo()
        return services.engine.status(root).fold(
            onSuccess = { s -> ToolResult.success(if (s.clean) "Git working tree clean" else "Git working tree has changes", statusJson(s)) },
            onFailure = { failure("status", it) }
        )
    }
}

private class GitDiffTool(services: GitServices) : GitTool(services) {
    override val definition = ToolDefinition("git_diff", "Read a bounded Git diff for the workspace.", schema(fields = mapOf("path" to "string", "staged" to "boolean", "maxChars" to "integer")), RiskLevel.SAFE, ToolCategory.GIT_READ)
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val root = root(context)
        if (!services.engine.isRepository(root)) return notRepo()
        val path = input.string("path")?.takeIf { it.isNotBlank() }
        path?.let { runCatching { validateGitPath(root, it) }.getOrElse { return ToolResult.failure(AgentError.workspaceViolation(it.message.orEmpty())) } }
        val staged = input.bool("staged") ?: false
        val maxChars = (input["maxChars"]?.jsonPrimitive?.intOrNull ?: 100_000).coerceIn(1_000, 200_000)
        return services.engine.diff(root, path, staged, maxChars).fold(
            onSuccess = { d -> ToolResult.success("Git diff: ${d.files.size} files", buildJsonObject { put("gitAction", "diff"); put("patch", d.patch); put("files", buildJsonArray { d.files.forEach { add(JsonPrimitive(it)) } }); put("staged", staged); put("truncated", d.truncated) }, truncated = d.truncated) },
            onFailure = { failure("diff", it) }
        )
    }
}

private class GitLogTool(services: GitServices) : GitTool(services) {
    override val definition = ToolDefinition("git_log", "Read recent Git commits.", schema(fields = mapOf("limit" to "integer")), RiskLevel.SAFE, ToolCategory.GIT_READ)
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val root = root(context); if (!services.engine.isRepository(root)) return notRepo()
        val limit = (input["limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 100)
        return services.engine.log(root, limit).fold(
            onSuccess = { commits -> ToolResult.success("${commits.size} commits", buildJsonObject { put("gitAction", "log"); put("commits", buildJsonArray { commits.forEach { c -> add(buildJsonObject { put("id", c.id); put("shortId", c.shortId); put("message", c.message); put("author", c.author); put("timestamp", c.timestamp) }) } }) }) },
            onFailure = { failure("log", it) }
        )
    }
}

private class GitBranchesTool(services: GitServices) : GitTool(services) {
    override val definition = ToolDefinition("git_branches", "List local Git branches.", schema(), RiskLevel.SAFE, ToolCategory.GIT_READ)
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val root = root(context); if (!services.engine.isRepository(root)) return notRepo()
        return services.engine.branches(root).fold(
            onSuccess = { branches -> ToolResult.success("${branches.size} branches", buildJsonObject { put("gitAction", "branches"); put("branches", buildJsonArray { branches.forEach { b -> add(buildJsonObject { put("name", b.name); put("current", b.current) }) } }) }) },
            onFailure = { failure("branches", it) }
        )
    }
}

private class GitCheckoutTool(services: GitServices) : GitTool(services) {
    override val definition = ToolDefinition("git_checkout", "Checkout or create a local branch.", schema(listOf("branch"), mapOf("branch" to "string", "create" to "boolean", "reason" to "string")), RiskLevel.MODIFY, ToolCategory.GIT_MODIFY)
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override suspend fun preview(input: JsonObject, context: ToolContext) = ToolPreview("Checkout ${input.string("branch").orEmpty()}", metadata = mapOf("gitAction" to "checkout"))
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val root = root(context); if (!services.engine.isRepository(root)) return notRepo()
        return services.engine.checkout(root, input.string("branch").orEmpty(), input.bool("create") ?: false).fold(
            onSuccess = { ToolResult.success("Git checkout complete", buildJsonObject { put("gitAction", "checkout"); put("branch", input.string("branch").orEmpty()) }) },
            onFailure = { failure("checkout", it) }
        )
    }
}

private class GitAddTool(services: GitServices) : GitTool(services) {
    override val definition = ToolDefinition("git_add", "Stage workspace paths in Git.", schema(listOf("paths"), mapOf("paths" to "array", "reason" to "string")), RiskLevel.MODIFY, ToolCategory.GIT_MODIFY)
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override suspend fun preview(input: JsonObject, context: ToolContext): ToolPreview {
        val paths = input.strings("paths")
        paths.forEach { validateGitPath(root(context), it) }
        return ToolPreview("Stage ${paths.size} paths", metadata = mapOf("gitAction" to "add"))
    }
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val root = root(context); if (!services.engine.isRepository(root)) return notRepo()
        val paths = input.strings("paths")
        return services.engine.add(root, paths).fold(
            onSuccess = { ToolResult.success("Staged ${paths.size} paths", buildJsonObject { put("gitAction", "add"); put("paths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } }) }) },
            onFailure = { failure("add", it) }
        )
    }
}

private class GitCommitTool(services: GitServices) : GitTool(services) {
    override val definition = ToolDefinition("git_commit", "Create a local Git commit from staged files.", schema(listOf("message"), mapOf("message" to "string", "authorName" to "string", "authorEmail" to "string", "reason" to "string")), RiskLevel.MODIFY, ToolCategory.GIT_MODIFY)
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override suspend fun preview(input: JsonObject, context: ToolContext): ToolPreview {
        val message = validateCommitMessage(input.string("message").orEmpty())
        return ToolPreview("Commit: ${message.lineSequence().first().take(120)}", metadata = mapOf("gitAction" to "commit"))
    }
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val root = root(context); if (!services.engine.isRepository(root)) return notRepo()
        return services.engine.commit(root, input.string("message").orEmpty(), input.string("authorName"), input.string("authorEmail")).fold(
            onSuccess = { c -> ToolResult.success("Committed ${c.shortId}", buildJsonObject { put("gitAction", "commit"); put("commitId", c.id); put("shortId", c.shortId); put("message", c.message) }) },
            onFailure = { failure("commit", it) }
        )
    }
}

private class GitRestoreTool(services: GitServices) : GitTool(services) {
    override val definition = ToolDefinition("git_restore", "Restore workspace paths or unstage them. Worktree restore discards local changes.", schema(listOf("paths"), mapOf("paths" to "array", "staged" to "boolean", "reason" to "string")), RiskLevel.DESTRUCTIVE, ToolCategory.GIT_DESTRUCTIVE)
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override suspend fun effectiveRisk(input: JsonObject, context: ToolContext) = if (input.bool("staged") == true) RiskLevel.MODIFY else RiskLevel.DESTRUCTIVE
    override suspend fun preview(input: JsonObject, context: ToolContext): ToolPreview {
        val paths = input.strings("paths"); paths.forEach { validateGitPath(root(context), it) }
        return ToolPreview(if (input.bool("staged") == true) "Unstage ${paths.size} paths" else "Discard changes in ${paths.size} paths", metadata = mapOf("gitAction" to "restore"))
    }
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val root = root(context); if (!services.engine.isRepository(root)) return notRepo()
        val paths = input.strings("paths"); val staged = input.bool("staged") ?: false
        return services.engine.restore(root, paths, staged).fold(
            onSuccess = { ToolResult.success(if (staged) "Paths unstaged" else "Paths restored", buildJsonObject { put("gitAction", "restore"); put("staged", staged); put("paths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } }) }) },
            onFailure = { failure("restore", it) }
        )
    }
}

private class GitInitTool(services: GitServices) : GitTool(services) {
    override val definition = ToolDefinition("git_init", "Initialize a Git repository at the workspace root.", schema(fields = mapOf("reason" to "string")), RiskLevel.MODIFY, ToolCategory.GIT_MODIFY)
    override fun availableInMode(mode: AgentMode) = mode == AgentMode.AGENT
    override suspend fun preview(input: JsonObject, context: ToolContext) = ToolPreview("Initialize Git repository", metadata = mapOf("gitAction" to "init"))
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult = services.engine.init(root(context)).fold(
        onSuccess = { ToolResult.success("Git repository initialized", buildJsonObject { put("gitAction", "init") }) },
        onFailure = { failure("init", it) }
    )
}

private fun statusJson(s: GitStatus) = buildJsonObject {
    put("gitAction", "status"); put("initialized", s.initialized); s.branch?.let { put("branch", it) }; put("clean", s.clean)
    put("modified", array(s.modified)); put("added", array(s.added)); put("deleted", array(s.deleted)); put("untracked", array(s.untracked)); put("staged", array(s.staged)); put("conflicting", array(s.conflicting))
}
private fun array(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
private fun schema(required: List<String> = emptyList(), fields: Map<String, String> = emptyMap()) = buildJsonObject {
    put("type", "object"); put("properties", buildJsonObject { fields.forEach { (key, type) -> put(key, buildJsonObject { put("type", type) }) } }); if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
}
private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().also { require(it.isNotEmpty()) { "$key must contain at least one path" } }
