package com.agentdroid.core.artifacts

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class FileArtifactRepositoryTest {
    private lateinit var root: File
    private lateinit var repository: FileArtifactRepository
    private val counter = AtomicInteger()

    @Before fun setUp() {
        root = Files.createTempDirectory("artifact-repository").toFile()
        repository = FileArtifactRepository(
            ArtifactWorkspaceProvider { workspaceId -> require(workspaceId == "w1"); root },
            CitationValidator(CitationSourceCatalog { sessionId, sourceId, url ->
                sessionId == "research-1" && sourceId == "source-1" && url == "https://example.com/source"
            }),
            clock = { 1234L }, newId = { "artifact-${counter.incrementAndGet()}" }
        )
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun lifecyclePersistsContentMetadataRenameCopyExportAndDelete(): Unit = runBlocking {
        val citation = SourceReference("research-1", "source-1", "https://example.com/source")
        val created = repository.create(CreateArtifactRequest(
            "w1", "conversation-1", ArtifactType.MARKDOWN, "Unsafe / Report", "first",
            taskId = "task-1", sourceReferences = listOf(citation), preferredFileName = "../unsafe report.md"
        ))
        assertTrue(created.filePath.startsWith("Artifacts/"))
        assertFalse(created.filePath.contains(".."))
        assertEquals("first", repository.read("w1", created.id).content)

        val updated = repository.update("w1", created.id, UpdateArtifactRequest(
            title = "Renamed", content = "second", preferredFileName = "final.md"
        ))
        assertEquals("Artifacts/final.md", updated.filePath)
        assertEquals("second", repository.read("w1", created.id).content)
        assertFalse(File(root, created.filePath).exists())

        val copy = repository.copy("w1", created.id)
        assertEquals("second", repository.read("w1", copy.id).content)
        assertEquals(2, repository.list(ArtifactListFilter("w1", taskId = "task-1")).size)

        val exported = repository.export("w1", created.id, "Exports/report.md")
        assertEquals("Exports/report.md", exported)
        assertEquals("second", File(root, exported).readText())

        val reloaded = FileArtifactRepository(
            ArtifactWorkspaceProvider { root },
            CitationValidator(CitationSourceCatalog { _, _, _ -> true })
        )
        assertEquals(2, reloaded.list(ArtifactListFilter("w1")).size)
        repository.delete("w1", created.id)
        assertFalse(File(root, updated.filePath).exists())
        assertThrows(ArtifactNotFound::class.java) { runBlocking { repository.get("w1", created.id) } }
    }

    @Test fun rejectsTraversalAndReservedExportPaths(): Unit = runBlocking {
        val artifact = repository.create(CreateArtifactRequest("w1", "c1", ArtifactType.PLAIN_TEXT, "A", "text"))
        assertThrows(UnsafeArtifactPath::class.java) {
            runBlocking { repository.export("w1", artifact.id, "../outside.txt") }
        }
        assertThrows(UnsafeArtifactPath::class.java) {
            runBlocking { repository.export("w1", artifact.id, ".agentdroid/stolen.txt") }
        }
        assertThrows(UnsafeArtifactPath::class.java) {
            runBlocking { repository.addScreenshotReference(ScreenshotReferenceRequest("w1", "c1", "Escape", "../outside.png")) }
        }
        assertFalse(File(root.parentFile, "outside.txt").exists())
    }

    @Test fun screenshotIsAReferenceAndDeleteDoesNotDeleteImage(): Unit = runBlocking {
        val image = File(root, "Screenshots/page.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val artifact = repository.addScreenshotReference(ScreenshotReferenceRequest("w1", "c1", "Page", "Screenshots/page.png"))
        assertEquals(ArtifactType.SCREENSHOT, artifact.type)
        assertEquals(ArtifactStorage.EXTERNAL_REFERENCE, artifact.storage)
        assertThrows(ArtifactException::class.java) { runBlocking { repository.read("w1", artifact.id) } }
        repository.delete("w1", artifact.id)
        assertTrue(image.exists())
    }

    @Test fun validatesJsonAndBoundsReads(): Unit = runBlocking {
        assertThrows(ArtifactWriteError::class.java) {
            runBlocking { repository.create(CreateArtifactRequest("w1", "c1", ArtifactType.JSON, "Bad", "not-json")) }
        }
        val artifact = repository.create(CreateArtifactRequest("w1", "c1", ArtifactType.PLAIN_TEXT, "Long", "abcdefghij"))
        val read = repository.read("w1", artifact.id, 5)
        assertEquals("abcde", read.content)
        assertTrue(read.truncated)
    }
}
