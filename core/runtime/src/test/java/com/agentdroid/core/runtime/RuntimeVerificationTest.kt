package com.agentdroid.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RuntimeVerificationTest {
    @Test fun `detected python is not advertised when code execution probe fails`(): Unit = runBlocking {
        val runner = ProbeRunner { request ->
            ProcessResult(
                stdout = "Python 3.12",
                stderr = "probe blocked",
                exitCode = 1,
                durationMs = 1,
                timedOut = false,
                stdoutTruncated = false,
                stderrTruncated = false,
                status = ProcessStatus.FAILED
            )
        }
        val root = Files.createTempDirectory("runtime-evidence").toFile()
        val verifier = RuntimeVerifier(runner, root)

        val evidence = verifier.verify(
            listOf(RuntimeComponent("python", "Python", true, "Python 3.12", "/runtime/python3"))
        )

        assertTrue(evidence.runtimes.single().detected)
        assertFalse(evidence.runtimes.single().executableByAgent)
        assertEquals(RuntimeEvidenceKind.DETECTED_ONLY, evidence.runtimes.single().evidenceKind)
        assertTrue("runtime.python" !in evidence.plannerCapabilities())
    }

    @Test fun `python and node are advertised only after real code probes pass`(): Unit = runBlocking {
        val runner = ProbeRunner { request ->
            val marker = "AGENTDROID_RUNTIME_OK"
            when (request.argv.firstOrNull()) {
                "/runtime/python3" -> success("$marker\n")
                "/runtime/node" -> success(marker)
                else -> success("")
            }
        }
        val root = Files.createTempDirectory("runtime-evidence").toFile()
        val verifier = RuntimeVerifier(runner, root)

        val evidence = verifier.verify(
            listOf(
                RuntimeComponent("python", "Python", true, "Python 3.12", "/runtime/python3"),
                RuntimeComponent("node", "Node", true, "v22", "/runtime/node")
            )
        )

        assertEquals(listOf("runtime.node", "runtime.python"), evidence.plannerCapabilities())
        assertTrue(evidence.runtimes.all { it.evidenceKind == RuntimeEvidenceKind.EXECUTION_PROBE_PASSED })
        assertTrue(runner.requests.any { it.argv.drop(1).contains("-c") })
        assertTrue(runner.requests.any { it.argv.drop(1).contains("-e") })
    }

    @Test fun `rust and go detection never claims Agent runtime support by itself`(): Unit = runBlocking {
        val runner = ProbeRunner { success("unused") }
        val root = Files.createTempDirectory("runtime-evidence").toFile()
        val verifier = RuntimeVerifier(runner, root)

        val evidence = verifier.verify(
            listOf(
                RuntimeComponent("rust", "Rust", true, "rustc 1.80", "/runtime/rustc"),
                RuntimeComponent("go", "Go", true, "go1.23", "/runtime/go")
            )
        )

        assertTrue(evidence.plannerCapabilities().isEmpty())
        assertTrue(evidence.runtimes.all { it.detected && !it.executableByAgent })
        assertTrue(evidence.runtimes.all { it.detail?.contains("Toolchain detection only") == true })
        assertTrue(runner.requests.isEmpty())
    }

    @Test fun `embedded git is classified as component not language runtime`(): Unit = runBlocking {
        val runner = ProbeRunner { success("unused") }
        val root = Files.createTempDirectory("runtime-evidence").toFile()
        val verifier = RuntimeVerifier(runner, root)

        val evidence = verifier.verify(RuntimeComponent("git", "Git", true, "JGit embedded", "embedded:jgit"))

        assertEquals(RuntimeEvidenceKind.EMBEDDED_COMPONENT, evidence.evidenceKind)
        assertFalse(evidence.executableByAgent)
        assertTrue(runner.requests.isEmpty())
    }

    private fun success(stdout: String) = ProcessResult(
        stdout = stdout,
        stderr = "",
        exitCode = 0,
        durationMs = 1,
        timedOut = false,
        stdoutTruncated = false,
        stderrTruncated = false,
        status = ProcessStatus.EXITED
    )

    private class ProbeRunner(
        private val responder: suspend (ProcessRequest) -> ProcessResult
    ) : ProcessRunner {
        val requests = mutableListOf<ProcessRequest>()
        override suspend fun run(request: ProcessRequest): ProcessResult {
            requests += request
            return responder(request)
        }

        override suspend fun start(request: ProcessRequest): RunningProcess =
            error("RuntimeVerifier must use bounded foreground probes")
    }
}
