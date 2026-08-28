package com.agentdroid.core.artifacts

import com.agentdroid.core.tasks.ArtifactRef
import com.agentdroid.core.tasks.ConciseTaskPlanner
import com.agentdroid.core.tasks.InMemoryTaskRepository
import com.agentdroid.core.tasks.TaskClock
import com.agentdroid.core.tasks.TaskEngine
import com.agentdroid.core.tasks.TaskIdGenerator
import com.agentdroid.core.tasks.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real Task and Artifact repositories together without network access. */
class TaskArtifactWorkflowIntegrationTest {
    private lateinit var workspace: File

    @Before fun setUp() {
        workspace = Files.createTempDirectory("task-artifact-workflow").toFile()
    }

    @After fun tearDown() {
        workspace.deleteRecursively()
    }

    @Test fun `three step task creates attaches and retains artifact before completion`(): Unit = runBlocking {
        val taskSequence = AtomicInteger()
        val taskIds = TaskIdGenerator { "task-id-${taskSequence.incrementAndGet()}" }
        val clock = TaskClock { 1_000L + taskSequence.get() }
        val tasks = TaskEngine(
            InMemoryTaskRepository(clock, taskIds),
            ConciseTaskPlanner(taskIds),
            clock
        )
        val artifacts = FileArtifactRepository(
            workspaces = ArtifactWorkspaceProvider { workspace },
            clock = { 2_000L },
            newId = { "artifact-1" }
        )

        var task = tasks.create(
            title = "Android Markdown comparison",
            workspaceId = "workspace-1",
            conversationId = "conversation-1",
            summary = "Search, compare, and save the result",
            steps = listOf("Search", "Compare", "Create report")
        )

        task.plan.steps.take(2).forEach { step ->
            task = tasks.start(task.id, task.workspaceId, step.id)
            task = tasks.completeStep(task.id, task.workspaceId, step.id)
        }
        val artifactStep = task.plan.steps.last()
        task = tasks.start(task.id, task.workspaceId, artifactStep.id)

        val artifact = artifacts.create(
            CreateArtifactRequest(
                workspaceId = task.workspaceId,
                conversationId = task.conversationId,
                taskId = task.id,
                type = ArtifactType.REPORT,
                title = "Markdown libraries report",
                content = "# Markdown libraries report\n\nVerified comparison."
            )
        )
        task = tasks.attachArtifact(
            task.id,
            task.workspaceId,
            ArtifactRef(
                artifactId = artifact.id,
                title = artifact.title,
                type = artifact.type.name,
                uri = artifact.filePath
            )
        )
        task = tasks.completeStep(task.id, task.workspaceId, artifactStep.id)

        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(100, task.progress)
        assertEquals(listOf(artifact.id), task.artifacts.map(ArtifactRef::artifactId))
        assertEquals(listOf(artifact.id), artifacts.list(ArtifactListFilter("workspace-1", taskId = task.id)).map(Artifact::id))
        assertTrue(artifacts.read("workspace-1", artifact.id).content.contains("Verified comparison"))
    }
}
