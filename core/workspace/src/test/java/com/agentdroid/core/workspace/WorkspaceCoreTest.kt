package com.agentdroid.core.workspace

import com.agentdroid.core.agent.AgentErrorCode
import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.ToolCall
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolRegistryException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WorkspaceCoreTest {
    @Test fun unsafePathsAndEscapingSymlinksAreRejected() {
        val root = Files.createTempDirectory("ws")
        val outside = Files.createTempDirectory("outside")
        val fs = WorkspaceFileSystem(root.toFile())
        listOf("../secret", "/system/build.prop", "file:///tmp/a", "content://authority/a", "intent:test").forEach { path ->
            assertError(AgentErrorCode.WORKSPACE_VIOLATION) { fs.resolve(path) }
        }
        Files.writeString(outside.resolve("secret.txt"), "secret")
        Files.createSymbolicLink(root.resolve("escape"), outside)
        assertError(AgentErrorCode.WORKSPACE_VIOLATION) { fs.resolve("escape/secret.txt", true) }
    }

    @Test fun readSupportsRangesUnicodeBinaryAndLimits() {
        val fs = newFs()
        fs.writeText("notes.txt", "one\nمرحبا\nthree\nfour", false, false)
        val result = fs.read("notes.txt", 2, 3)
        assertEquals("مرحبا\nthree", result.content)
        assertEquals(4, result.totalLines)
        assertTrue(result.truncated)
        assertNotNull(result.sha256)
        fs.resolve("binary.bin").writeBytes(byteArrayOf(0, 1, 2))
        assertTrue(fs.read("binary.bin").binary)

        val limited = WorkspaceFileSystem(Files.createTempDirectory("limited").toFile(), WorkspaceLimits(maxReadBytes = 4))
        limited.resolve("large.txt").writeText("12345")
        assertError(AgentErrorCode.FILE_TOO_LARGE) { limited.read("large.txt") }
    }

    @Test fun searchSupportsFilenameTextCaseGlobAndMaxResults() {
        val fs = newFs()
        fs.writeText("src/ProviderRegistry.kt", "class ProviderRegistry", true, false)
        fs.writeText("src/Other.kt", "providerregistry lower", true, false)
        fs.writeText("README.md", "ProviderRegistry docs", false, false)
        assertEquals(1, fs.search("ProviderRegistry", glob = "**/*.kt", caseSensitive = true).count { it.line != null })
        assertEquals(2, fs.search("providerregistry", glob = "**/*.kt", caseSensitive = false).count { it.line != null })
        assertTrue(fs.search("", fileNameQuery = "registry").any { it.fileNameMatch })
        assertEquals(1, fs.search("providerregistry", maxResults = 1).size)
    }

    @Test fun fileToolsStageUntilAcceptedAndCanRevert() = runBlocking {
        val f = fixture()
        val write = f.registry.execute(ToolCall("w", "write_file", buildJsonObject {
            put("path", "src/a.txt"); put("content", "old\nline"); put("createParents", true)
        }), f.context)
        assertTrue(write.success)
        assertFalse(f.fs.exists("src/a.txt"))
        f.manager.accept(write.changeSetId!!)
        assertEquals("old\nline", f.fs.read("src/a.txt").content)

        val patch = f.registry.execute(ToolCall("p", "patch_file", buildJsonObject {
            put("path", "src/a.txt"); put("oldContent", "old"); put("newContent", "new"); put("expectedHash", f.fs.sha256("src/a.txt"))
        }), f.context)
        f.manager.accept(patch.changeSetId!!)
        assertEquals("new\nline", f.fs.read("src/a.txt").content)

        val move = f.registry.execute(ToolCall("m", "move_file", buildJsonObject { put("source", "src/a.txt"); put("destination", "src/b.txt") }), f.context)
        f.manager.accept(move.changeSetId!!)
        assertTrue(f.fs.exists("src/b.txt"))
        f.manager.revert(move.changeSetId!!)
        assertTrue(f.fs.exists("src/a.txt"))

        val delete = f.registry.execute(ToolCall("d", "delete_file", buildJsonObject { put("path", "src/a.txt") }), f.context)
        val applied = f.manager.accept(delete.changeSetId!!)
        assertFalse(f.fs.exists("src/a.txt"))
        assertTrue(applied.files.single().trashPath?.startsWith(".workspace-trash/") == true)
        f.manager.revert(delete.changeSetId!!)
        assertEquals("new\nline", f.fs.read("src/a.txt").content)
    }

    @Test fun writeHonorsOverwriteAndCreateParents() = runBlocking {
        val f = fixture()
        f.fs.writeText("existing.txt", "a", false, false)
        val overwriteDenied = f.registry.execute(ToolCall("1", "write_file", buildJsonObject { put("path", "existing.txt"); put("content", "b") }), f.context)
        assertEquals(AgentErrorCode.TOOL_VALIDATION_ERROR, overwriteDenied.error?.code)
        val parentDenied = f.registry.execute(ToolCall("2", "write_file", buildJsonObject {
            put("path", "nested/file.txt"); put("content", "x"); put("createParents", false)
        }), f.context)
        assertFalse(parentDenied.success)
        assertEquals(AgentErrorCode.IO_ERROR, parentDenied.error?.code)
    }

    @Test fun patchSupportsRangeUnifiedDiffAndDetectsStalePreview() = runBlocking {
        val f = fixture()
        f.fs.writeText("a.txt", "one\ntwo\nthree", false, false)
        val range = f.registry.execute(ToolCall("r", "patch_file", buildJsonObject {
            put("path", "a.txt"); put("startLine", 2); put("endLine", 2); put("newContent", "TWO")
        }), f.context)
        f.manager.accept(range.changeSetId!!)
        val unifiedText = f.diff.diff("a.txt", "one\nTWO\nthree", "one\nTWO\nTHREE").unifiedDiff
        val unified = f.registry.execute(ToolCall("u", "patch_file", buildJsonObject { put("path", "a.txt"); put("unifiedDiff", unifiedText) }), f.context)
        f.manager.accept(unified.changeSetId!!)
        assertEquals("one\nTWO\nTHREE", f.fs.read("a.txt").content)

        val staleCall = ToolCall("stale", "patch_file", buildJsonObject { put("path", "a.txt"); put("oldContent", "THREE"); put("newContent", "3") })
        assertTrue(f.registry.preview(staleCall, f.context).isSuccess)
        f.fs.writeText("a.txt", "external", false, true)
        assertEquals(AgentErrorCode.PATCH_CONFLICT, f.registry.execute(staleCall, f.context).error?.code)
    }

    @Test fun patchRejectsAmbiguousAndMissingMatches() = runBlocking {
        val f = fixture()
        f.fs.writeText("a.txt", "same\nsame", false, false)
        val ambiguous = f.registry.execute(ToolCall("a", "patch_file", buildJsonObject { put("path", "a.txt"); put("oldContent", "same"); put("newContent", "new") }), f.context)
        assertEquals(AgentErrorCode.PATCH_CONFLICT, ambiguous.error?.code)
        val missing = f.registry.execute(ToolCall("b", "patch_file", buildJsonObject { put("path", "a.txt"); put("oldContent", "missing"); put("newContent", "new") }), f.context)
        assertEquals(AgentErrorCode.PATCH_CONFLICT, missing.error?.code)
    }

    @Test fun diffAndChangeSetEditRejectWork() = runBlocking {
        val f = fixture()
        val diff = f.diff.diff("a.kt", "a\nb\nc", "a\nB\nc\nd")
        assertTrue(diff.unifiedDiff.contains("--- a/a.kt"))
        assertTrue(diff.modified > 0 && diff.added > 0)

        f.fs.writeText("a.txt", "before", false, false)
        val set = f.manager.propose(listOf(FileChange(
            path = "a.txt", beforeHash = f.fs.sha256("a.txt"), afterHash = hashText("after"), beforeContent = "before", afterContent = "after",
            diff = f.diff.diff("a.txt", "before", "after").unifiedDiff, changeType = FileChangeType.MODIFY
        )))
        assertEquals("edited", f.manager.edit(set.id, "a.txt", "edited").files.single().afterContent)
        assertEquals(ChangeSetStatus.REJECTED, f.manager.reject(set.id).status)
        assertEquals("before", f.fs.read("a.txt").content)
    }

    private data class Fixture(val fs: WorkspaceFileSystem, val manager: ChangeSetManager, val registry: com.agentdroid.core.agent.ToolRegistry, val diff: DiffEngine, val context: ToolContext)
    private fun newFs() = WorkspaceFileSystem(Files.createTempDirectory("ws").toFile())
    private fun fixture(): Fixture {
        val fs = newFs(); val diff = DiffEngine(); val manager = ChangeSetManager("w1", fs, InMemoryChangeSetStore(), diff)
        return Fixture(fs, manager, createWorkspaceToolRegistry(StaticWorkspaceServices("w1", fs, manager), diff), diff, ToolContext("w1", "c1", "s1", AgentMode.AGENT))
    }
    private fun assertError(code: AgentErrorCode, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: ToolRegistryException) { assertEquals(code, error.agentError.code) }
    }
}
