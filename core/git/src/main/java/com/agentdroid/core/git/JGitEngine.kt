package com.agentdroid.core.git

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File

class JGitEngine : GitEngine {
    override suspend fun isRepository(root: File): Boolean = withContext(Dispatchers.IO) {
        val gitDir = File(root.canonicalFile, ".git")
        gitDir.exists()
    }

    override suspend fun init(root: File): Result<Unit> = ioResult {
        root.mkdirs()
        if (!File(root, ".git").exists()) Git.init().setDirectory(root).call().use { }
    }

    override suspend fun status(root: File): Result<GitStatus> = ioResult {
        repository(root).use { repo ->
            Git(repo).use { git ->
                val value = git.status().call()
                val branch = runCatching { repo.branch }.getOrNull()
                GitStatus(
                    initialized = true,
                    branch = branch,
                    modified = value.modified.sorted(),
                    added = value.added.sorted(),
                    deleted = (value.missing + value.removed).toSortedSet().toList(),
                    untracked = value.untracked.sorted(),
                    staged = (value.added + value.changed + value.removed).toSortedSet().toList(),
                    conflicting = value.conflicting.sorted()
                )
            }
        }
    }

    override suspend fun diff(root: File, path: String?, staged: Boolean, maxChars: Int): Result<GitDiffResult> = ioResult {
        repository(root).use { repo ->
            Git(repo).use { git ->
                val out = ByteArrayOutputStream()
                val command = git.diff()
                    .setCached(staged)
                    .setOutputStream(out)
                val normalized = path?.takeIf { it.isNotBlank() }?.let { validateGitPath(root, it) }
                if (normalized != null && normalized != ".") command.setPathFilter(PathFilter.create(normalized))

                // Let DiffCommand own the working-tree/index iterators while it formats the patch.
                // Formatting returned DiffEntry objects again with a separate DiffFormatter can try to
                // resolve working-tree pseudo object ids from the object database and cause MissingObjectException.
                val entries = command.call()
                val raw = out.toString(Charsets.UTF_8.name())
                GitDiffResult(
                    raw.take(maxChars),
                    entries.mapNotNull { entry ->
                        when {
                            entry.newPath != "/dev/null" -> entry.newPath
                            entry.oldPath != "/dev/null" -> entry.oldPath
                            else -> null
                        }
                    }.distinct(),
                    raw.length > maxChars
                )
            }
        }
    }

    override suspend fun log(root: File, limit: Int): Result<List<GitCommitInfo>> = ioResult {
        repository(root).use { repo ->
            Git(repo).use gitUse@ { git ->
                if (repo.resolve("HEAD") == null) return@gitUse emptyList()
                git.log().setMaxCount(limit.coerceIn(1, 200)).call().map { commit ->
                    GitCommitInfo(
                        id = commit.name,
                        shortId = commit.name.take(8),
                        message = commit.fullMessage,
                        author = commit.authorIdent?.let { "${it.name} <${it.emailAddress}>" }.orEmpty(),
                        timestamp = commit.commitTime.toLong() * 1000L
                    )
                }.toList()
            }
        }
    }

    override suspend fun branches(root: File): Result<List<GitBranchInfo>> = ioResult {
        repository(root).use { repo ->
            Git(repo).use { git ->
                val current = runCatching { repo.branch }.getOrNull()
                git.branchList().call().map { ref ->
                    val name = ref.name.removePrefix("refs/heads/")
                    GitBranchInfo(name, name == current)
                }.sortedWith(compareByDescending<GitBranchInfo> { it.current }.thenBy { it.name })
            }
        }
    }

    override suspend fun checkout(root: File, branch: String, create: Boolean): Result<Unit> = ioResult {
        val validated = validateBranch(branch)
        repository(root).use { repo -> Git(repo).use { git -> git.checkout().setName(validated).setCreateBranch(create).call() } }
    }

    override suspend fun add(root: File, paths: List<String>): Result<Unit> = ioResult {
        require(paths.isNotEmpty()) { "At least one path is required" }
        val normalized = paths.map { validateGitPath(root, it) }.distinct()
        repository(root).use { repo ->
            Git(repo).use { git ->
                normalized.forEach { path ->
                    if (File(root, path).exists()) git.add().addFilepattern(path).call()
                    else git.rm().addFilepattern(path).call()
                }
            }
        }
    }

    override suspend fun commit(root: File, message: String, authorName: String?, authorEmail: String?): Result<GitCommitInfo> = ioResult {
        val validated = validateCommitMessage(message)
        repository(root).use { repo ->
            Git(repo).use { git ->
                val configuredName = repo.config.getString("user", null, "name")
                val configuredEmail = repo.config.getString("user", null, "email")
                val ident = PersonIdent(
                    authorName?.takeIf { it.isNotBlank() } ?: configuredName ?: "AgentDroid",
                    authorEmail?.takeIf { it.isNotBlank() } ?: configuredEmail ?: "agentdroid@local"
                )
                val commit = git.commit().setMessage(validated).setAuthor(ident).setCommitter(ident).call()
                GitCommitInfo(commit.name, commit.name.take(8), commit.fullMessage, "${ident.name} <${ident.emailAddress}>", commit.commitTime.toLong() * 1000L)
            }
        }
    }

    override suspend fun restore(root: File, paths: List<String>, staged: Boolean): Result<Unit> = ioResult {
        require(paths.isNotEmpty()) { "At least one path is required" }
        val normalized = paths.map { validateGitPath(root, it) }.distinct()
        repository(root).use { repo ->
            Git(repo).use { git ->
                normalized.forEach { path ->
                    if (staged && repo.resolve("HEAD") == null) {
                        // An unborn repository has no HEAD tree to reset to. Removing the path only
                        // from the index preserves the working-tree file and correctly unstages it.
                        git.rm().setCached(true).addFilepattern(path).call()
                    } else if (staged) git.reset().setRef("HEAD").addPath(path).call()
                    else git.checkout().addPath(path).call()
                }
            }
        }
    }

    private fun repository(root: File) = FileRepositoryBuilder()
        .setGitDir(File(root.canonicalFile, ".git"))
        .setWorkTree(root.canonicalFile)
        .setMustExist(true)
        .build()

    private fun validateBranch(branch: String): String {
        val value = branch.trim()
        require(value.matches(Regex("[A-Za-z0-9._/-]{1,200}"))) { "Invalid branch name" }
        require(!value.startsWith("/") && !value.endsWith("/") && ".." !in value && "//" !in value) { "Invalid branch name" }
        return value
    }

    private suspend fun <T> ioResult(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }
}
