package com.agentdroid.core.git

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class JGitEngineTest {
    @Test fun initAddCommitModifyDiffAndRestoreWorkflow() = runBlocking {
        val root = Files.createTempDirectory("agentdroid-git").toFile()
        val engine = JGitEngine()
        assertTrue(engine.init(root).isSuccess)
        assertTrue(engine.isRepository(root))

        val file = java.io.File(root, "sample.txt")
        file.writeText("one\n")
        assertTrue(engine.add(root, listOf("sample.txt")).isSuccess)
        val staged = engine.status(root).getOrThrow()
        assertTrue("sample.txt" in staged.staged)

        val commit = engine.commit(root, "Initial commit", "AgentDroid Test", "test@example.invalid").getOrThrow()
        assertEquals(8, commit.shortId.length)
        assertTrue(engine.status(root).getOrThrow().clean)

        file.writeText("one\ntwo\n")
        val changed = engine.status(root).getOrThrow()
        assertTrue("sample.txt" in changed.modified)
        val diff = engine.diff(root, "sample.txt").getOrThrow()
        assertTrue(diff.patch.contains("+two"))

        assertTrue(engine.restore(root, listOf("sample.txt"), staged = false).isSuccess)
        assertEquals("one\n", file.readText())
        assertTrue(engine.status(root).getOrThrow().clean)
    }

    @Test fun branchesCheckoutAndLogAreRealRepositoryOperations() = runBlocking {
        val root = Files.createTempDirectory("agentdroid-git-branches").toFile()
        val engine = JGitEngine()
        engine.init(root).getOrThrow()
        java.io.File(root, "a.txt").writeText("a")
        engine.add(root, listOf("a.txt")).getOrThrow()
        engine.commit(root, "base", "AgentDroid Test", "test@example.invalid").getOrThrow()
        assertTrue(engine.checkout(root, "feature/runtime", create = true).isSuccess)
        assertTrue(engine.branches(root).getOrThrow().any { it.name == "feature/runtime" && it.current })
        assertEquals("base", engine.log(root, 1).getOrThrow().single().message)
    }

    @Test fun validationRejectsEscapesAndBadCommitMessages() {
        val root = Files.createTempDirectory("agentdroid-git-validation").toFile()
        assertThrows(IllegalArgumentException::class.java) { validateGitPath(root, "../outside") }
        assertThrows(IllegalArgumentException::class.java) { validateGitPath(root, "/etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { validateCommitMessage("   ") }
        assertEquals("message", validateCommitMessage(" message "))
    }
}
