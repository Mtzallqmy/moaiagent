package com.agentdroid.core.workspace

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.ToolRegistryException
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class ChangeSetStatus { PROPOSED, APPLIED, REJECTED, REVERTED, CONFLICTED }

@Serializable
enum class FileChangeType { CREATE, MODIFY, MOVE, DELETE, CREATE_DIRECTORY }

@Serializable
data class FileChange(
    val path: String,
    val destinationPath: String? = null,
    val beforeHash: String? = null,
    val afterHash: String? = null,
    val beforeContent: String? = null,
    val afterContent: String? = null,
    val diff: String = "",
    val changeType: FileChangeType,
    val trashPath: String? = null,
    val createParents: Boolean = true
)

@Serializable
data class ChangeSet(
    val id: String = UUID.randomUUID().toString(),
    val workspaceId: String,
    val files: List<FileChange>,
    val createdAt: Long = System.currentTimeMillis(),
    val status: ChangeSetStatus = ChangeSetStatus.PROPOSED,
    val originatingToolCallId: String? = null,
    val appliedAt: Long? = null,
    val revertedAt: Long? = null
) {
    val addedLines: Int get() = files.sumOf { change -> change.diff.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") } }
    val removedLines: Int get() = files.sumOf { change -> change.diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") } }
}

interface ChangeSetStore {
    suspend fun save(changeSet: ChangeSet)
    suspend fun get(id: String): ChangeSet?
    suspend fun list(workspaceId: String? = null): List<ChangeSet>
}

class InMemoryChangeSetStore(initial: List<ChangeSet> = emptyList()) : ChangeSetStore {
    private val data = ConcurrentHashMap(initial.associateBy { it.id })
    override suspend fun save(changeSet: ChangeSet) { data[changeSet.id] = changeSet }
    override suspend fun get(id: String): ChangeSet? = data[id]
    override suspend fun list(workspaceId: String?): List<ChangeSet> = data.values
        .filter { workspaceId == null || it.workspaceId == workspaceId }
        .sortedByDescending { it.createdAt }
}

class ChangeSetManager(
    private val workspaceId: String,
    private val fileSystem: WorkspaceFileSystem,
    private val store: ChangeSetStore,
    private val diffEngine: DiffEngine = DiffEngine()
) {
    suspend fun propose(files: List<FileChange>, originatingToolCallId: String? = null): ChangeSet {
        require(files.isNotEmpty()) { "ChangeSet must contain at least one change" }
        validateNoOverlappingTargets(files)
        val changeSet = ChangeSet(workspaceId = workspaceId, files = files, originatingToolCallId = originatingToolCallId)
        store.save(changeSet)
        return changeSet
    }

    suspend fun get(id: String): ChangeSet? = store.get(id)
    suspend fun list(): List<ChangeSet> = store.list(workspaceId)

    suspend fun accept(id: String): ChangeSet {
        val current = requireProposed(id)
        val completed = mutableListOf<FileChange>()
        var applied = current
        return try {
            preflightApply(current.files)
            current.files.forEachIndexed { index, change ->
                val appliedChange = applyChange(current.id, change)
                completed += appliedChange
                if (appliedChange !== change) applied = applied.copy(files = applied.files.toMutableList().also { it[index] = appliedChange })
            }
            applied = applied.copy(status = ChangeSetStatus.APPLIED, appliedAt = System.currentTimeMillis())
            store.save(applied)
            applied
        } catch (failure: Throwable) {
            val rollbackFailure = rollbackApplied(current.id, completed)
            store.save(current.copy(status = ChangeSetStatus.CONFLICTED))
            if (rollbackFailure != null) {
                throw ToolRegistryException(AgentError.io("ChangeSet $id failed and rollback also failed: ${rollbackFailure.message}"))
            }
            if (failure is ToolRegistryException) throw failure
            throw ToolRegistryException(AgentError.io("Failed to apply ChangeSet $id: ${failure.message}"))
        }
    }

    suspend fun reject(id: String): ChangeSet {
        val current = requireProposed(id)
        val rejected = current.copy(status = ChangeSetStatus.REJECTED)
        store.save(rejected)
        return rejected
    }

    suspend fun edit(id: String, path: String, replacementContent: String): ChangeSet {
        val current = requireProposed(id)
        val index = current.files.indexOfFirst { it.path == path && it.changeType in setOf(FileChangeType.CREATE, FileChangeType.MODIFY) }
        if (index < 0) throw ToolRegistryException(AgentError.validation("No editable text change for $path"))
        val old = current.files[index]
        val before = old.beforeContent.orEmpty()
        val updated = old.copy(
            afterContent = replacementContent,
            afterHash = hashText(replacementContent),
            diff = diffEngine.diff(path, before, replacementContent).unifiedDiff
        )
        val changed = current.copy(files = current.files.toMutableList().also { it[index] = updated })
        store.save(changed)
        return changed
    }

    suspend fun revert(id: String): ChangeSet {
        val current = store.get(id) ?: throw ToolRegistryException(AgentError.validation("Unknown ChangeSet: $id"))
        if (current.workspaceId != workspaceId) throw ToolRegistryException(AgentError.workspaceViolation("ChangeSet belongs to another workspace"))
        if (current.status != ChangeSetStatus.APPLIED) throw ToolRegistryException(AgentError.validation("Only applied ChangeSets can be reverted"))
        val revertedChanges = mutableListOf<FileChange>()
        return try {
            preflightRevert(current)
            current.files.asReversed().forEach { change ->
                revertChange(current.id, change)
                revertedChanges += change
            }
            val reverted = current.copy(status = ChangeSetStatus.REVERTED, revertedAt = System.currentTimeMillis())
            store.save(reverted)
            reverted
        } catch (failure: Throwable) {
            val restoreFailure = restoreAppliedState(current.id, revertedChanges)
            store.save(current.copy(status = ChangeSetStatus.CONFLICTED))
            if (restoreFailure != null) {
                throw ToolRegistryException(AgentError.io("Revert of ChangeSet $id failed and applied-state recovery also failed: ${restoreFailure.message}"))
            }
            if (failure is ToolRegistryException) throw failure
            throw ToolRegistryException(AgentError.io("Failed to revert ChangeSet $id: ${failure.message}"))
        }
    }

    private suspend fun requireProposed(id: String): ChangeSet {
        val current = store.get(id) ?: throw ToolRegistryException(AgentError.validation("Unknown ChangeSet: $id"))
        if (current.workspaceId != workspaceId) throw ToolRegistryException(AgentError.workspaceViolation("ChangeSet belongs to another workspace"))
        if (current.status != ChangeSetStatus.PROPOSED) throw ToolRegistryException(AgentError.validation("ChangeSet is ${current.status}, expected PROPOSED"))
        return current
    }

    private fun validateNoOverlappingTargets(files: List<FileChange>) {
        val claimed = mutableSetOf<String>()
        files.forEach { change ->
            val source = normalized(change.path)
            if (!claimed.add(source)) throw ToolRegistryException(AgentError.validation("ChangeSet contains overlapping path: ${change.path}"))
            change.destinationPath?.let { destination ->
                val target = normalized(destination)
                if (!claimed.add(target)) throw ToolRegistryException(AgentError.validation("ChangeSet contains overlapping destination: $destination"))
            }
        }
    }

    private fun preflightApply(files: List<FileChange>) {
        files.forEach { change ->
            when (change.changeType) {
                FileChangeType.CREATE -> {
                    if (fileSystem.exists(change.path)) conflict("Cannot create ${change.path}: path now exists")
                    if (!change.createParents) ensureParentExists(change.path)
                }
                FileChangeType.MODIFY -> ensureHash(change.path, change.beforeHash)
                FileChangeType.MOVE -> {
                    ensureFingerprint(change.path, change.beforeHash)
                    val destination = change.destinationPath ?: throw ToolRegistryException(AgentError.validation("Move destination is missing"))
                    if (fileSystem.exists(destination)) conflict("Cannot move to $destination: destination now exists")
                }
                FileChangeType.DELETE -> ensureFingerprint(change.path, change.beforeHash)
                FileChangeType.CREATE_DIRECTORY -> {
                    if (fileSystem.exists(change.path)) conflict("Cannot create directory ${change.path}: path now exists")
                    if (!change.createParents) ensureParentExists(change.path)
                }
            }
        }
    }

    private fun preflightRevert(changeSet: ChangeSet) {
        changeSet.files.asReversed().forEach { change ->
            when (change.changeType) {
                FileChangeType.CREATE -> ensureHash(change.path, change.afterHash)
                FileChangeType.MODIFY -> ensureHash(change.path, change.afterHash)
                FileChangeType.MOVE -> {
                    val destination = change.destinationPath ?: throw ToolRegistryException(AgentError.validation("Move destination is missing"))
                    ensureFingerprint(destination, change.beforeHash)
                    if (fileSystem.exists(change.path)) conflict("Cannot revert move: ${change.path} now exists")
                }
                FileChangeType.DELETE -> {
                    if (fileSystem.exists(change.path)) conflict("Cannot restore ${change.path}: destination now exists")
                    val trashPath = change.trashPath ?: ".workspace-trash/${changeSet.id}/${File(change.path).name}"
                    fileSystem.resolveInternal(trashPath, mustExist = true, allowInternal = true)
                }
                FileChangeType.CREATE_DIRECTORY -> {
                    if (!fileSystem.exists(change.path)) conflict("Cannot revert directory creation: ${change.path} is missing")
                    if (fileSystem.list(change.path, recursive = false, maxResults = 1).isNotEmpty()) conflict("Cannot revert directory creation: ${change.path} is not empty")
                }
            }
        }
    }

    private fun applyChange(changeSetId: String, change: FileChange): FileChange = when (change.changeType) {
        FileChangeType.CREATE -> {
            fileSystem.writeText(change.path, change.afterContent.orEmpty(), createParents = change.createParents, overwrite = false)
            change
        }
        FileChangeType.MODIFY -> {
            fileSystem.writeText(change.path, change.afterContent.orEmpty(), createParents = false, overwrite = true)
            change
        }
        FileChangeType.MOVE -> {
            val destination = change.destinationPath ?: throw ToolRegistryException(AgentError.validation("Move destination is missing"))
            fileSystem.move(change.path, destination, overwrite = false)
            change
        }
        FileChangeType.DELETE -> {
            val trashPath = fileSystem.moveToTrash(change.path, changeSetId)
            change.copy(trashPath = trashPath)
        }
        FileChangeType.CREATE_DIRECTORY -> {
            fileSystem.createDirectory(change.path, createParents = change.createParents)
            change
        }
    }

    private fun revertChange(changeSetId: String, change: FileChange) {
        when (change.changeType) {
            FileChangeType.CREATE -> fileSystem.deleteRecursively(change.path)
            FileChangeType.MODIFY -> fileSystem.writeText(change.path, change.beforeContent.orEmpty(), createParents = false, overwrite = true)
            FileChangeType.MOVE -> {
                val destination = change.destinationPath ?: throw ToolRegistryException(AgentError.validation("Move destination is missing"))
                fileSystem.move(destination, change.path, overwrite = false)
            }
            FileChangeType.DELETE -> {
                val trashPath = change.trashPath ?: ".workspace-trash/$changeSetId/${File(change.path).name}"
                fileSystem.restoreFromTrash(trashPath, change.path)
                ensureFingerprint(change.path, change.beforeHash)
            }
            FileChangeType.CREATE_DIRECTORY -> fileSystem.deleteRecursively(change.path)
        }
    }

    private fun rollbackApplied(changeSetId: String, completed: List<FileChange>): Throwable? {
        var failure: Throwable? = null
        completed.asReversed().forEach { change ->
            if (failure == null) {
                runCatching { revertChange(changeSetId, change) }.onFailure { failure = it }
            }
        }
        return failure
    }

    private fun restoreAppliedState(changeSetId: String, reverted: List<FileChange>): Throwable? {
        var failure: Throwable? = null
        reverted.asReversed().forEach { change ->
            if (failure == null) {
                runCatching { applyChange(changeSetId, change) }.onFailure { failure = it }
            }
        }
        return failure
    }

    private fun ensureParentExists(path: String) {
        val target = fileSystem.resolve(path)
        val parent = target.parentFile ?: conflict("Invalid destination: $path")
        if (!parent.exists() || !parent.isDirectory) conflict("Parent directory does not exist for $path")
        fileSystem.relative(parent)
    }

    private fun normalized(path: String): String = fileSystem.relative(fileSystem.resolve(path))

    private fun ensureHash(path: String, expected: String?) {
        if (!fileSystem.exists(path)) conflict("$path no longer exists")
        if (expected != null && fileSystem.sha256(path) != expected) conflict("$path changed since the proposal was created")
    }

    private fun ensureFingerprint(path: String, expected: String?) {
        if (!fileSystem.exists(path)) conflict("$path no longer exists")
        if (expected != null && fileSystem.fingerprint(path) != expected) conflict("$path changed since the proposal was created")
    }

    private fun conflict(message: String): Nothing = throw ToolRegistryException(AgentError.patchConflict(message))
}

fun hashText(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
