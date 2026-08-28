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
        assertTrue("Android shell detection must pass", shell!!.detected)
        assertTrue("Android shell must execute a bounded probe through ProcessRunner", shell.executableByAgent)
        assertEquals(RuntimeEvidenceKind.EXECUTION_PROBE_PASSED, shell.evidenceKind)
        assertTrue("runtime.shell", "runtime.shell" in plannerCapabilities)

        listOf("python", "node").forEach { id ->
            val item = byId.getValue(id)
            val capability = "runtime.$id"
            assertEquals(
                "$capability may be advertised iff its Android execution probe passed",
                item.executableByAgent,
                capability in plannerCapabilities
            )
            if (item.detected && !item.executableByAgent) {
                assertEquals(RuntimeEvidenceKind.DETECTED_ONLY, item.evidenceKind)
            }
        }

        listOf("rust", "go").forEach { id ->
            val item = byId.getValue(id)
            assertFalse("$id toolchain detection is not an Agent runtime capability", "runtime.$id" in plannerCapabilities)
            assertFalse("$id is not executableByAgent without a real AgentDroid subsystem", item.executableByAgent)
        }

        evidence.runtimes.forEach { item ->
            Log.i(
                TAG,
                "id=${item.id} detected=${item.detected} executableByAgent=${item.executableByAgent} " +
                    "evidence=${item.evidenceKind} version=${item.version.orEmpty()} executable=${item.executable.orEmpty()}"
            )
        }
        Log.i(TAG, "plannerCapabilities=${plannerCapabilities.joinToString(",")}")
    }

    companion object { private const val TAG = "Phase5RuntimeEvidence" }
}
