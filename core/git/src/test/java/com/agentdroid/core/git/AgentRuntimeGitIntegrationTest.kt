package com.agentdroid.core.git

import com.agentdroid.core.agent.*
import com.agentdroid.core.runtime.*
import com.agentdroid.core.workspace.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentRuntimeGitIntegrationTest {
    @Test fun agentCanReadRunCommandInspectGitAndFinish() = runBlocking {
        val root = Files.createTempDirectory("agent-runtime-git").toFile()
        val fs = WorkspaceFileSystem(root)
        val diffEngine = DiffEngine()
        val changeManager = ChangeSetManager("ws", fs, InMemoryChangeSetStore(), diffEngine)
        val registry = createWorkspaceToolRegistry(StaticWorkspaceServices("ws", fs, changeManager), diffEngine)
        val manager = ProcessManager(FixedProcessRunner("tests passed\n"))
        val runtimeServices = object : RuntimeServices {
            override val processManager = manager
            override val commandPolicy = CommandPolicy()
            override val limits = RuntimeLimits()
            override fun workspaceRoot(workspaceId: String) = root
        }
        registry.registerAll(createRuntimeTools(runtimeServices))
        val git = JGitEngine()
        git.init(root).getOrThrow()
        File(root, "sample.txt").writeText("base\n")
        git.add(root, listOf("sample.txt")).getOrThrow()
        git.commit(root, "base", "AgentDroid Test", "test@example.invalid").getOrThrow()
        File(root, "sample.txt").writeText("base\nchanged\n")
        val gitServices = object : GitServices { override val engine: GitEngine = git; override fun workspaceRoot(workspaceId: String) = root }
        registry.registerAll(createGitTools(gitServices))

        val model = SequenceModel(
            listOf(
                ToolCall("1", "read_file", buildJsonObject { put("path", "sample.txt") }),
                ToolCall("2", "run_command", buildJsonObject { put("command", "printf runtime-ok"); put("cwd", ".") }),
                ToolCall("3", "git_status", buildJsonObject {}),
                ToolCall("4", "git_diff", buildJsonObject {})
            )
        )
        val loop = AgentLoop(registry, allowAll(), ContextManager(ContextSource { ContextSnapshot() }))
        val events = loop.run(AgentSession("s", "c", "ws", AgentMode.AGENT, "fake", "fake"), "verify project", model).toList()
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(listOf("read_file", "run_command", "git_status", "git_diff"), finished.map { it.call.name })
        assertTrue(finished.all { it.result.success })
        assertTrue(finished.first { it.call.name == "run_command" }.result.output.toString().contains("tests passed"))
        assertTrue(finished.first { it.call.name == "git_diff" }.result.output.toString().contains("changed"))
        assertEquals("All checks complete", events.filterIsInstance<AgentEvent.FinalAnswer>().single().text)
    }

    @Test fun destructiveRmCanBeDeniedBeforeExecution() = runBlocking {
        val root = Files.createTempDirectory("agent-rm-denied").toFile()
        val file = File(root, "keep.txt").apply { writeText("keep") }
        val fs = WorkspaceFileSystem(root)
        val registry = createWorkspaceToolRegistry(StaticWorkspaceServices("ws", fs, ChangeSetManager("ws", fs, InMemoryChangeSetStore(), DiffEngine())))
        val runner = FixedProcessRunner("should-not-run")
        val runtimeServices = object : RuntimeServices {
            override val processManager = ProcessManager(runner)
            override val commandPolicy = CommandPolicy()
            override val limits = RuntimeLimits()
            override fun workspaceRoot(workspaceId: String) = root
        }
        registry.registerAll(createRuntimeTools(runtimeServices))
        val model = SequenceModel(listOf(ToolCall("rm1", "run_command", buildJsonObject { put("command", "rm keep.txt"); put("cwd", ".") })))
        val gateway = object : PermissionGateway {
            override suspend fun authorize(request: PermissionRequest) = if (request.definition.riskLevel == RiskLevel.DESTRUCTIVE) PermissionOutcome(PermissionDecision.DENY) else PermissionOutcome(PermissionDecision.ALLOW)
        }
        val events = AgentLoop(registry, gateway, ContextManager(ContextSource { ContextSnapshot() }))
            .run(AgentSession("s", "c", "ws", AgentMode.AGENT, "fake", "fake"), "remove file", model).toList()
        val result = events.filterIsInstance<AgentEvent.ToolFinished>().first().result
        assertFalse(result.success)
        assertEquals(AgentErrorCode.PERMISSION_DENIED, result.error?.code)
        assertTrue(file.exists())
        assertEquals(0, runner.starts)
    }

    private fun allowAll() = object : PermissionGateway {
        override suspend fun authorize(request: PermissionRequest) = PermissionOutcome(PermissionDecision.ALLOW)
    }
}

private class SequenceModel(private val calls: List<ToolCall>) : AgentModelClient {
    override val supportsToolCalling = true
    private var index = 0
    override suspend fun complete(request: AgentModelRequest, onEvent: suspend (AgentModelEvent) -> Unit): Result<AgentModelResponse> {
        if (index < calls.size) return Result.success(AgentModelResponse("", listOf(calls[index++])))
        val toolNames = request.messages.filter { it.role == AgentMessageRole.TOOL }.mapNotNull { it.toolName }
        check(toolNames.containsAll(calls.map { it.name }))
        return Result.success(AgentModelResponse("All checks complete"))
    }
}

private class FixedProcessRunner(private val output: String) : ProcessRunner {
    var starts = 0
    override suspend fun run(request: ProcessRequest): ProcessResult = start(request).await()
    override suspend fun start(request: ProcessRequest): RunningProcess {
        starts++
        return object : RunningProcess {
            private val statusFlow = MutableStateFlow(ProcessStatus.EXITED)
            private val stdoutFlow = MutableStateFlow(output)
            private val stderrFlow = MutableStateFlow("")
            private val exitFlow = MutableStateFlow<Int?>(0)
            override val status: StateFlow<ProcessStatus> = statusFlow
            override val stdout: StateFlow<String> = stdoutFlow
            override val stderr: StateFlow<String> = stderrFlow
            override val exitCode: StateFlow<Int?> = exitFlow
            override val startedAt = System.currentTimeMillis()
            override suspend fun await() = ProcessResult(output, "", 0, 1, false, false, false, ProcessStatus.EXITED)
            override suspend fun sendInput(text: String) = Result.success(Unit)
            override fun terminate() { statusFlow.value = ProcessStatus.TERMINATED }
            override fun kill() { statusFlow.value = ProcessStatus.KILLED }
        }
    }
}
