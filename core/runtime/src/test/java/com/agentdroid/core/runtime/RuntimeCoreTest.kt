package com.agentdroid.core.runtime

import com.agentdroid.core.agent.RiskLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RuntimeCoreTest {
    @Test fun classifierSeparatesSafeModifyDestructiveAndExternal() {
        val cwd = Files.createTempDirectory("runtime-policy").toFile()
        val classifier = CommandClassifier()
        assertEquals(RiskLevel.SAFE, classifier.classify("ls -la", cwd).risk)
        assertEquals(RiskLevel.SAFE, classifier.classify("git diff -- README.md", cwd).risk)
        assertEquals(RiskLevel.MODIFY, classifier.classify("mkdir build", cwd).risk)
        assertEquals(RiskLevel.DESTRUCTIVE, classifier.classify("rm -rf build", cwd).risk)
        assertEquals(RiskLevel.EXTERNAL, classifier.classify("curl https://example.com", cwd).risk)
        assertFalse(classifier.classify("sh -c 'rm -rf x'", cwd).allowed)
    }

    @Test fun policyEnforcesWorkspaceCwdAndBlocksTraversal() {
        val root = Files.createTempDirectory("runtime-workspace").toFile()
        java.io.File(root, "sub").mkdirs()
        val policy = CommandPolicy()
        assertTrue(policy.assess("pwd", root, "sub").allowed)
        assertFalse(policy.assess("cat ../secret", root).allowed)
        assertFalse(policy.assess("cat /etc/passwd", root).allowed)
        assertFalse(policy.assess("echo $(pwd)", root).allowed)
    }

    @Test fun redactionRemovesCredentials() {
        val redacted = CommandRedactor.redact("curl -H 'Authorization: Bearer abc.def' --token secret https://user:pass@example.com")
        assertFalse(redacted.contains("abc.def"))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("pass@"))
        assertTrue(redacted.contains("***"))
    }

    @Test fun processRunnerCapturesTimeoutAndTruncation() = runBlocking {
        val cwd = Files.createTempDirectory("process-runner").toFile()
        val runner = DefaultProcessRunner(RuntimeLimits(maxStdoutBytes = 32, maxStderrBytes = 32, defaultTimeoutMs = 100, maxRuntimeMs = 5_000))
        val normal = runner.run(ProcessRequest(command = "printf 'abcdefghijklmnopqrstuvwxyz0123456789'", cwd = cwd, timeoutMs = 2_000, maxStdoutBytes = 12))
        assertEquals(0, normal.exitCode)
        assertTrue(normal.stdoutTruncated)
        assertTrue(normal.stdout.toByteArray().size <= 12)
        val timeout = runner.run(ProcessRequest(command = "sleep 2", cwd = cwd, timeoutMs = 80))
        assertTrue(timeout.timedOut)
        assertEquals(ProcessStatus.TIMED_OUT, timeout.status)
    }

    @Test fun processManagerKeepsBackgroundProcessAcrossConsumersAndCanStop() = runBlocking {
        val cwd = Files.createTempDirectory("process-manager").toFile()
        val manager = ProcessManager(DefaultProcessRunner(RuntimeLimits(defaultTimeoutMs = 1_000, maxRuntimeMs = 5_000)), limits = RuntimeLimits(defaultTimeoutMs = 1_000, maxRuntimeMs = 5_000))
        val started = manager.startBackground(ProcessRequest(command = "sleep 3", cwd = cwd, timeoutMs = 4_000), "ws", "agent-session")
        assertEquals(ProcessStatus.RUNNING, manager.get(started.processId, "ws")?.status)
        assertTrue(manager.stop(started.processId, "ws"))
        repeat(50) {
            if (manager.get(started.processId, "ws")?.status != ProcessStatus.RUNNING) return@repeat
            delay(20)
        }
        assertNotEquals(ProcessStatus.RUNNING, manager.get(started.processId, "ws")?.status)
    }
}
