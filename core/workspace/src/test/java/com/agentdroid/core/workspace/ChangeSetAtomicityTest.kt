package com.agentdroid.core.workspace

import com.agentdroid.core.agent.AgentErrorCode
import com.agentdroid.core.agent.ToolRegistryException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ChangeSetAtomicityTest {
    @Test fun conflictInSecondFilePreventsFirstFileFromBeingApplied() = runBlocking {
        val fs = WorkspaceFileSystem(Files.createTempDirectory("changeset-atomic").toFile())
        fs.writeText("a.txt", "a0", false, false)
        fs.writeText("b.txt", "b0", false, false)
        val diff = DiffEngine()
        val manager = ChangeSetManager("w", fs, InMemoryChangeSetStore(), diff)
        val proposed = manager.propose(listOf(
            FileChange(
                path = "a.txt",
                beforeHash = fs.sha256("a.txt"),
                afterHash = hashText("a1"),
                beforeContent = "a0",
                afterContent = "a1",
                diff = diff.diff("a.txt", "a0", "a1").unifiedDiff,
                changeType = FileChangeType.MODIFY
            ),
            FileChange(
                path = "b.txt",
                beforeHash = fs.sha256("b.txt"),
                afterHash = hashText("b1"),
                beforeContent = "b0",
                afterContent = "b1",
                diff = diff.diff("b.txt", "b0", "b1").unifiedDiff,
                changeType = FileChangeType.MODIFY
            )
        ))
        fs.writeText("b.txt", "external", false, true)
        try {
            manager.accept(proposed.id)
            throw AssertionError("Expected conflict")
        } catch (error: ToolRegistryException) {
            assertEquals(AgentErrorCode.PATCH_CONFLICT, error.agentError.code)
        }
        assertEquals("a0", fs.read("a.txt").content)
        assertEquals("external", fs.read("b.txt").content)
        assertEquals(ChangeSetStatus.CONFLICTED, manager.get(proposed.id)?.status)
    }

    @Test fun overlappingTargetsAreRejectedAtProposalTime() = runBlocking {
        val fs = WorkspaceFileSystem(Files.createTempDirectory("changeset-overlap").toFile())
        val manager = ChangeSetManager("w", fs, InMemoryChangeSetStore())
        try {
            manager.propose(listOf(
                FileChange("same.txt", afterHash = hashText("a"), afterContent = "a", changeType = FileChangeType.CREATE),
                FileChange("same.txt", afterHash = hashText("b"), afterContent = "b", changeType = FileChangeType.CREATE)
            ))
            throw AssertionError("Expected validation error")
        } catch (error: ToolRegistryException) {
            assertEquals(AgentErrorCode.TOOL_VALIDATION_ERROR, error.agentError.code)
        }
    }
}
