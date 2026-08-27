package com.agentdroid.core.git

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class GitStatus(
    val initialized: Boolean,
    val branch: String? = null,
    val modified: List<String> = emptyList(),
    val added: List<String> = emptyList(),
    val deleted: List<String> = emptyList(),
    val untracked: List<String> = emptyList(),
    val staged: List<String> = emptyList(),
    val conflicting: List<String> = emptyList()
) {
    val clean: Boolean get() = initialized && modified.isEmpty() && added.isEmpty() && deleted.isEmpty() && untracked.isEmpty() && staged.isEmpty() && conflicting.isEmpty()
}

@Serializable
data class GitCommitInfo(val id: String, val shortId: String, val message: String, val author: String, val timestamp: Long)

@Serializable
data class GitBranchInfo(val name: String, val current: Boolean)

@Serializable
data class GitDiffResult(val patch: String, val files: List<String>, val truncated: Boolean = false)

interface GitEngine {
    suspend fun isRepository(root: File): Boolean
    suspend fun init(root: File): Result<Unit>
    suspend fun status(root: File): Result<GitStatus>
    suspend fun diff(root: File, path: String? = null, staged: Boolean = false, maxChars: Int = 100_000): Result<GitDiffResult>
    suspend fun log(root: File, limit: Int = 30): Result<List<GitCommitInfo>>
    suspend fun branches(root: File): Result<List<GitBranchInfo>>
    suspend fun checkout(root: File, branch: String, create: Boolean = false): Result<Unit>
    suspend fun add(root: File, paths: List<String>): Result<Unit>
    suspend fun commit(root: File, message: String, authorName: String? = null, authorEmail: String? = null): Result<GitCommitInfo>
    suspend fun restore(root: File, paths: List<String>, staged: Boolean = false): Result<Unit>
}

fun validateGitPath(root: File, path: String): String {
    require(path.isNotBlank()) { "Git path is blank" }
    require(!File(path).isAbsolute) { "Absolute paths are not allowed" }
    require(path.replace('\\', '/').split('/').none { it == ".." }) { "Path traversal is not allowed" }
    val canonicalRoot = root.canonicalFile
    val target = File(canonicalRoot, path).canonicalFile
    require(target == canonicalRoot || target.path.startsWith(canonicalRoot.path + File.separator)) { "Path escapes workspace" }
    return target.relativeTo(canonicalRoot).invariantSeparatorsPath.ifBlank { "." }
}

fun validateCommitMessage(message: String): String {
    val trimmed = message.trim()
    require(trimmed.isNotEmpty()) { "Commit message is required" }
    require(trimmed.length <= 5_000) { "Commit message is too long" }
    require('\u0000' !in trimmed) { "Commit message contains NUL" }
    return trimmed
}
