package com.agentdroid.core.workspace

import com.agentdroid.core.agent.AgentErrorCode
import com.agentdroid.core.agent.AgentMode
import com.agentdroid.core.agent.ToolCall
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolRegistryException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WorkspaceCoreTest {
    @Test fun pathValidationRejectsTraversalAbsoluteUrisAndEscapingSymlinks() {
        val root = Files.createTempDirectory("agentdroid-workspace")
        val outside = Files.createTempDirectory("agentdroid-outside")
        val fs = WorkspaceFileSystem(root.toFile())
        assertError(AgentErrorCode.WORKSPACE_VIOLATION) { fs.resolve("../secret.txt") }
        assertError(AgentErrorCode.WORKSPACE_VIOLATION) { fs.resolve("/system/build.prop") }
        assertError(AgentErrorCode.WORKSPACE_VIOLATION) { fs.resolve("file:///tmp/a") }
        assertError(AgentErrorCode.WORKSPACE_VIOLATION) { fs.resolve("content://authority/a") }
        Files.writeString(outside.resolve("secret.txt"), "secret")
        Files.createSymbolicLink(root.resolve("escape"), outside)
        assertError(AgentErrorCode.WORKSPACE_VIOLATION) { fs.resolve("escape/secret.txt", mustExist = true) }
    }

    @Test fun readFileSupportsRangesUnicodeBinaryAndLargeFileLimits() {
        val fs = newFs()
        fs.writeText("notes.txt", "one\nمرحبا\nthree\nfour", createParents = false, overwrite = false)
        val ranged = fs.read("notes.txt", 2, 3)
        assertEquals("مرحبا\nthree", ranged.content)
        assertEquals(4, ranged.totalLines)
        assertTrue(ranged.truncated)
        assertNotNull(ranged.sha256)

        fs.resolve("binary.bin").writeBytes(byteArrayOf(0, 1, 2, 3))
        val binary = fs.read("binary.bin")
        assertTrue(binary.binary)
        assertEquals(null, binary.content)

        val limited = WorkspaceFileSystem(Files.createTempDirectory("agentdroid-limited").toFile(), WorkspaceLimits(maxReadBytes = 4))
        limited.resolve("large.txt").writeText("12345")
        assertError(AgentErrorCode.FILE_TOO_LARGE) { limited.read("large.txt") }
    }

    @Test fun searchFilesSupportsFilenameTextCaseGlobAndMaxResults() {
        val fs = newFs()
        fs.writeText("src/ProviderRegistry.kt", "class ProviderRegistry\nval VALUE = 1", true, false)
        fs.writeText("src/Other.kt", "providerregistry lower", true, false)
        fs.writeText("README.md", "ProviderRegistry docs", false, false)
        val kt = fs.search("ProviderRegistry", glob = "**/*.kt", caseSensitive = true)
        assertEquals(1, kt.count { it.line != null })
        assertEquals("src/ProviderRegistry.kt", kt.single { it.line != null }.path)
        val insensitive = fs.search("providerregistry", glob = "**/*.kt", caseSensitive = false)
        assertEquals(2, insensitive.count { it.line != null })
        val filenames = fs.search("", fileNameQuery = "registry", caseSensitive = false)
        assertTrue(filenames.any { it.fileNameMatch && it.path.endsWith("ProviderRegistry.kt") })
        assertEquals(1, fs.search("providerregistry", caseSensitive = false, maxResults = 1).size)
    }

    @Test fun writePatchMoveDeleteAndRevertUseStagedChangeSets() = runBlocking {
        val fixture = fixture()
        val context = fixture.context

        val write = fixture.registry.execute(ToolCall("w1", "write_file", buildJsonObject {
            put("path", "src/a.txt"); put("content", "old\nline"); put("createParents", true)
        }), context)
        assertTrue(write.success)
        assertFalse(fixture.fs.exists("src/a.txt"))
        val writeSet = fixture.manager.accept(write.changeSetId!!)
        assertEquals(ChangeSetStatus.APPLIED, writeSet.status)
        assertEquals("old\nline", fixture.fs.read("src/a.txt").content)

        val patch = fixture.registry.execute(ToolCall("p1", "patch_file", buildJsonObject {
            put("path", "src/a.txt"); put("oldContent", "old"); put("newContent", "new"); put("expectedHash", fixture.fs.sha256("src/a.txt"))
        }), context)
        assertTrue(patch.success)
        fixture.manager.accept(patch.changeSetId!!)
        assertEquals("new\nline", fixture.fs.read("src/a.txt").content)

        val move = fixture.registry.execute(ToolCall("m1", "move_file", buildJsonObject {
            put("source", "src/a.txt"); put("destination", "src/b.txt")
        }), context)
        fixture.manager.accept(move.changeSetId!!)
        assertFalse(fixture.fs.exists("src/a.txt"))
        assertTrue(fixture.fs.exists("src/b.txt"))
        fixture.manager.revert(move.changeSetId!!)
        assertTrue(fixture.fs.exists("src/a.txt"))
        assertFalse(fixture.fs.exists("src/b.txt"))

        val delete = fixture.registry.execute(ToolCall("d1", "delete_file", buildJsonObject { put("path", "src/a.txt") }), context)
        val appliedDelete = fixture.manager.accept(delete.changeSetId!!)
        assertFalse(fixture.fs.exists("src/a.txt"))
        assertTrue(appliedDelete.files.single().trashPath?.startsWith(".workspace-trash/") == true)
        fixture.manager.revert(delete.changeSetId!!)
        assertEquals("new\nline", fixture.fs.read("src/a.txt").content)
    }

    @Test fun writeFileHonorsOverwriteAndCreateParentsPolicy() = runBlocking {
        val fixture = fixture()
        fixture.fs.writeText("existing.txt", "a", false, false)
        val noOverwrite = fixture.registry.execute(ToolCall("1", "write_file", buildJsonObject {
            put("path", "existing.txt"); put("content", "b")
        }), fixture.context)
        assertEquals(AgentErrorCode.TOOL_VALIDATION_ERROR, noOverwrite.error?.code)

        val missingParents = fixture.registry.execute(ToolCall("2", "write_file", buildJsonObject {
            put("path", "nested/file.txt"); put("content", "x"); put("createParents", false)
        }), fixture.context)
        assertTrue(missingParents.success)
        val failure = try {
            fixture.manager.accept(missingParents.changeSetId!!)
            null
        } catch (error: ToolRegistryException) { error }
        assertNotNull(failure)
        assertEquals(AgentErrorCode.IO_ERROR, failure?.agentError?.code)
    }

    @Test fun patchSupportsRangeAndUnifiedDiffAndDetectsStalePermissionWindow() = runBlocking {
        val fixture = fixture()
        fixture.fs.writeText("a.txt", "one\ntwo\nthree", false, false)

        val range = fixture.registry.execute(ToolCall("r1", "patch_file", buildJsonObject {
            put("path", "a.txt"); put("startLine", 2); put("endLine", 2); put("newContent", "TWO")
        }), fixture.context)
        fixture.manager.accept(range.changeSetId!!)
        assertEquals("one\nTWO\nthree", fixture.fs.read("a.txt").content)

        val diff = fixture.diff.diff("a.txt", "one\nTWO\nthree", "one\nTWO\nTHREE").unifiedDiff
        val unified = fixture.registry.execute(ToolCall("u1", "patch_file", buildJsonObject { put("path", "a.txt"); put("unifiedDiff", diff) }), fixture.context)
        fixture.manager.accept(unified.changeSetId!!)
        assertEquals("one\nTWO\nTHREE", fixture.fs.read("a.txt").content)

        val staleCall = ToolCall("stale", "patch_file", buildJsonObject { put("path", "a.txt"); put("oldContent", "THREE"); put("newContent", "3") })
        assertTrue(fixture.registry.preview(staleCall, fixture.context).isSuccess)
        fixture.fs.writeText("a.txt", "externally changed", false, true)
        val stale = fixture.registry.execute(staleCall, fixture.context)
        assertEquals(AgentErrorCode.PATCH_CONFLICT, stale.error?.code)
    }

    @Test fun ambiguousOrMismatchedPatchReturnsPatchConflict() = runBlocking {
        val fixture = fixture()
        fixture.fs.writeText("a.txt", "same\nsame", false, false)
        val ambiguous = fixture.registry.execute(ToolCall("a", "patch_file", buildJsonObject {
            put("path", "a.txt"); put("oldContent", "same"); put("newContent", "new")
        }), fixture.context)
        assertEquals(AgentErrorCode.PATCH_CONFLICT, ambiguous.error?.code)

        val mismatched = fixture.registry.execute(ToolCall("b", "patch_file", buildJsonObject {
            put("path", "a.txt"); put("oldContent", "missing"); put("newContent", "new")
        }), fixture.context)
        assertEquals(AgentErrorCode.PATCH_CONFLICT, mismatched.error?.code)
    }

    @Test fun diffEngineProvidesUnifiedAndStructuredChanges() {
        val diff = DiffEngine().diff("a.kt", "a\nb\nc", "a\nB\nc\nd")
        assertTrue(diff.unifiedDiff.contains("--- a/a.kt"))
        assertTrue(diff.unifiedDiff.contains("+++ b/a.kt"))
        assertTrue(diff.modified >= 1)
        assertTrue(diff.added >= 1)
        assertTrue(diff.changes.isNotEmpty())
    }

    @Test fun changeSetRejectAndEditBehaveWithoutTouchingWorkspace() = runBlocking {
        val fixture = fixture()
        fixture.fs.writeText("a.txt", "before", false, false)
        val proposed = fixture.manager.propose(listOf(FileChange(
            path = "a.txt",
            beforeHash = fixture.fs.sha256("a.txt"),
            afterHash = hashText("after"),
            beforeContent = "before",
            afterContent = "after",
            diff = fixture.diff.diff("a.txt", "before", "after").unifiedDiff,
            changeType = FileChangeType.MODIFY
        )))
        val edited = fixture.manager.edit(proposed.id, "a.txt", "edited")
        assertEquals("edited", edited.files.single().afterContent)
        assertTrue(edited.files.single().diff.contains("edited"))
        val rejected = fixture.manager.reject(proposed.id)
        assertEquals(ChangeSetStatus.REJECTED, rejected.status)
        assertEquals("before", fixture.fs.read("a.txt").content)
    }

    private fun newFs() = WorkspaceFileSystem(Files.createTempDirectory("agentdroid-workspace").toFile())

    private data class Fixture(
        val fs: WorkspaceFileSystem,
        val manager: ChangeSetManager,
        val registry: com.agentdroid.core.agent.ToolRegistry,
        val diff: DiffEngine,
        val context: ToolContext
    )

    private fun fixture(): Fixture {
        val fs = newFs()
        val diff = DiffEngine()
        val manager = ChangeSetManager("w1", fs, InMemoryChangeSetStore(), diff)
        val registry = createWorkspaceToolRegistry(StaticWorkspaceServices("w1", fs, manager), diff)
        return Fixture(fs, manager, registry, diff, ToolContext("w1", "c1", "s1", AgentMode.AGENT))
    }

    private fun assertError(code: AgentErrorCode, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected $code")
        } catch (error: ToolRegistryException) {
            assertEquals(code, error.agentError.code)
        }
    }
}
