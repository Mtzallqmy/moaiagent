package com.agentdroid.integration

import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.artifacts.Artifact
import com.agentdroid.core.artifacts.ArtifactListFilter
import com.agentdroid.core.artifacts.ArtifactReadResult
import com.agentdroid.core.artifacts.ArtifactRepository
import com.agentdroid.core.artifacts.ScreenshotReferenceRequest
import com.agentdroid.core.artifacts.UpdateArtifactRequest
import com.agentdroid.core.artifacts.CreateArtifactRequest
import com.agentdroid.core.browser.BrowserEngine
import com.agentdroid.core.browser.BrowserScreenshotReference
import com.agentdroid.core.browser.BrowserScreenshotSink
import com.agentdroid.core.browser.BrowserSession
import com.agentdroid.core.browser.BrowserSessionRequest
import com.agentdroid.core.browser.BrowserSessionService
import com.agentdroid.data.database.RoomArtifactMetadataStore
import com.agentdroid.data.database.RoomBrowserMetadataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Keeps Room's queryable metadata in sync while files remain the source of truth. */
class PersistedArtifactRepository(
    private val files: ArtifactRepository,
    private val metadata: RoomArtifactMetadataStore
) : ArtifactRepository {
    override suspend fun create(request: CreateArtifactRequest): Artifact =
        files.create(request).also { metadata.save(it) }

    override suspend fun addScreenshotReference(request: ScreenshotReferenceRequest): Artifact =
        files.addScreenshotReference(request).also { metadata.save(it) }

    override suspend fun get(workspaceId: String, id: String): Artifact = files.get(workspaceId, id)

    override suspend fun list(filter: ArtifactListFilter): List<Artifact> = files.list(filter)

    override suspend fun read(workspaceId: String, id: String, maxBytes: Int): ArtifactReadResult =
        files.read(workspaceId, id, maxBytes)

    override suspend fun update(workspaceId: String, id: String, request: UpdateArtifactRequest): Artifact =
        files.update(workspaceId, id, request).also { metadata.save(it) }

    override suspend fun rename(workspaceId: String, id: String, title: String, preferredFileName: String?): Artifact =
        files.rename(workspaceId, id, title, preferredFileName).also { metadata.save(it) }

    override suspend fun copy(workspaceId: String, id: String, title: String?): Artifact =
        files.copy(workspaceId, id, title).also { metadata.save(it) }

    override suspend fun delete(workspaceId: String, id: String): Artifact =
        files.delete(workspaceId, id).also { metadata.delete(id) }

    override suspend fun export(
        workspaceId: String,
        id: String,
        destinationRelativePath: String,
        overwrite: Boolean
    ): String = files.export(workspaceId, id, destinationRelativePath, overwrite)
}

/** Stores screenshot bytes inside the workspace and returns the created Artifact id only. */
class ArtifactBrowserScreenshotSink(
    private val workspaceRoot: (String) -> File,
    private val artifacts: ArtifactRepository
) : BrowserScreenshotSink {
    override suspend fun save(
        session: com.agentdroid.core.browser.BrowserSessionMetadata,
        pngBytes: ByteArray,
        width: Int,
        height: Int
    ): BrowserScreenshotReference {
        require(pngBytes.isNotEmpty()) { "Screenshot is empty" }
        val id = UUID.randomUUID().toString()
        val relativePath = "Artifacts/browser-screenshot-$id.png"
        val root = workspaceRoot(session.workspaceId).canonicalFile
        val target = File(root, relativePath).canonicalFile
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        require(target.path.startsWith(rootPrefix)) { "Screenshot path escapes workspace" }
        val parent = target.parentFile ?: error("Screenshot has no parent directory")
        check(parent.exists() || parent.mkdirs()) { "Could not create screenshot directory" }
        val temporary = File(parent, ".${target.name}.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(pngBytes)
                output.flush()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val artifact = artifacts.addScreenshotReference(
                ScreenshotReferenceRequest(
                    workspaceId = session.workspaceId,
                    conversationId = session.conversationId,
                    title = "Browser screenshot",
                    workspaceRelativePath = relativePath
                )
            )
            return BrowserScreenshotReference(artifact.id, artifact.mimeType, width, height, artifact.createdAt)
        } catch (failure: Throwable) {
            temporary.delete()
            target.delete()
            throw failure
        }
    }
}

/** Lazily restores one browser session per workspace/conversation and persists metadata only. */
class PersistedBrowserSessionService(
    private val engine: BrowserEngine,
    private val metadata: RoomBrowserMetadataStore,
    private val scope: CoroutineScope
) : BrowserSessionService {
    private val mutex = Mutex()
    private val sessionIds = ConcurrentHashMap<String, String>()

    override suspend fun session(context: ToolContext): BrowserSession =
        getOrCreate(context.workspaceId, context.conversationId)

    /** Shared by the UI and model-facing adapter so both always address the same session. */
    suspend fun getOrCreate(workspaceId: String, conversationId: String): BrowserSession = mutex.withLock {
        val key = "$workspaceId/$conversationId"
        sessionIds[key]?.let(engine::session)?.let { return it }
        engine.sessions().firstOrNull {
            val value = it.metadata.value
            value.workspaceId == workspaceId && value.conversationId == conversationId
        }?.let {
            sessionIds[key] = it.metadata.value.sessionId
            return it
        }
        val restored = metadata.list(workspaceId)
            .firstOrNull { it.conversationId == conversationId }
        val created = engine.createSession(
            BrowserSessionRequest(
                workspaceId = workspaceId,
                conversationId = conversationId,
                restoredSessionId = restored?.sessionId,
                restoredTabs = restored?.tabs.orEmpty(),
                restoredActiveTabId = restored?.activeTabId
            )
        )
        sessionIds[key] = created.metadata.value.sessionId
        scope.launch {
            created.metadata.collect(metadata::save)
        }
        created
    }
}
