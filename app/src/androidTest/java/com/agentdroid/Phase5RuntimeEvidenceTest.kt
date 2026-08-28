package com.agentdroid

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agentdroid.core.runtime.DefaultProcessRunner
import com.agentdroid.core.runtime.RuntimeDiscovery
import com.agentdroid.core.runtime.RuntimeEvidenceKind
import com.agentdroid.core.runtime.RuntimeVerifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Phase5RuntimeEvidenceTest {
    @Test fun runtimeCapabilitiesRequireExecutionEvidenceOnAndroid(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val probeDirectory = File(context.cacheDir, "phase5-runtime-evidence").apply { mkdirs() }
        val runner = DefaultProcessRunner()
        val discovery = RuntimeDiscovery(runner, probeDirectory)
        val detected = discovery.list()
        val evidence = RuntimeVerifier(runner, probeDirectory).verify(detected)
        val byId = evidence.runtimes.associateBy { it.id }
        val plannerCapabilities = evidence.plannerCapabilities()
        val shell = byId["shell"]
        assertNotNull("Android shell must be discoverable", shell)
        assertTrue(shell!!.detected); assertTrue(shell.executableByAgent)
        assertEquals(RuntimeEvidenceKind.EXECUTION_PROBE_PASSED, shell.evidenceKind)
        assertTrue("runtime.shell" in plannerCapabilities)
        listOf("python", "node").forEach { id ->
            val item = byId.getValue(id); val capability = "runtime.$id"
            assertEquals(item.executableByAgent, capability in plannerCapabilities)
            if (item.detected && !item.executableByAgent) assertEquals(RuntimeEvidenceKind.DETECTED_ONLY, item.evidenceKind)
        }
        listOf("rust", "go").forEach { id ->
            val item = byId.getValue(id); assertFalse("runtime.$id" in plannerCapabilities); assertFalse(item.executableByAgent)
        }
        evidence.runtimes.forEach { item -> Log.i(TAG, "id=${item.id} detected=${item.detected} executableByAgent=${item.executableByAgent} evidence=${item.evidenceKind}") }
    }

    @Test fun embeddedPythonExecutesCodeAndIsRegisteredAsAgentTools(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AgentDroidApplication>()
        val workspaceId = "python_emulator_probe"; app.container.workspaceRoot(workspaceId).mkdirs()
        val version = app.pythonRuntime.version()
        val execution = app.pythonRuntime.runCode(workspaceId, "print('AGENTDROID_PYTHON_OK')", 5_000)
        assertTrue(version.startsWith("3.13")); assertEquals(0, execution.exitCode); assertTrue(execution.stderr, "AGENTDROID_PYTHON_OK" in execution.stdout)
        assertNotNull(app.container.toolRegistry.get("python_version")); assertNotNull(app.container.toolRegistry.get("python_run")); assertNotNull(app.container.toolRegistry.get("python_install_package"))
        val pack = app.runtimePacks.manager.state("python"); assertNotNull(pack); assertTrue(pack!!.agentExecutable)
    }

    @Test fun embeddedNodeExecutesJavaScriptAndIsRegisteredAsAgentTools(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AgentDroidApplication>()
        val workspaceId = "node_emulator_probe"; app.container.workspaceRoot(workspaceId).mkdirs()
        val version = app.nodeRuntime.version()
        val execution = app.nodeRuntime.runCode(workspaceId, "console.log('AGENTDROID_NODE_OK')", 8_000)
        assertTrue("Embedded Node.js must report a version", version.isNotBlank())
        assertEquals(execution.stderr, 0, execution.exitCode)
        assertFalse("Node execution must not time out", execution.timedOut)
        assertTrue(execution.stderr, "AGENTDROID_NODE_OK" in execution.stdout)
        assertNotNull(app.container.toolRegistry.get("node_version")); assertNotNull(app.container.toolRegistry.get("node_run")); assertNotNull(app.container.toolRegistry.get("node_run_script"))
        val pack = app.runtimePacks.manager.state("node"); assertNotNull(pack); assertTrue("Node Runtime Pack must be execution-verified on Android", pack!!.agentExecutable)
        Log.i(TAG, "embeddedNode=$version executionVerified=${pack.agentExecutable}")
    }

    companion object { private const val TAG = "Phase5RuntimeEvidence" }
}
