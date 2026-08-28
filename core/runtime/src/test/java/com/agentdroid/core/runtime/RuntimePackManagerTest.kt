package com.agentdroid.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePackManagerTest {
    @Test fun `embedded Node is not executable until runtime probe succeeds`(): Unit = runBlocking {
        val installer = object : RuntimePackInstaller {
            override suspend fun install(manifest: RuntimePackManifest) = error("embedded Node is packaged with the APK")
            override suspend fun uninstall(state: RuntimePackState) = error("embedded Node cannot be uninstalled")
        }
        val noProbe = RuntimePackManager(listOf(RuntimePackManifests.node), installer, RuntimePackExecutionVerifier { false })
        val passed = RuntimePackManager(listOf(RuntimePackManifests.node), installer, RuntimePackExecutionVerifier { it.manifest.id == "node" })

        assertEquals(RuntimePackStatus.BUNDLED, noProbe.state("node")!!.state.status)
        assertFalse(noProbe.state("node")!!.agentExecutable)
        assertTrue(passed.state("node")!!.agentExecutable)
    }

    @Test fun `embedded Python is advertised only when execution verifier passes`(): Unit = runBlocking {
        val installer = object : RuntimePackInstaller {
            override suspend fun install(manifest: RuntimePackManifest) = error("not used")
            override suspend fun uninstall(state: RuntimePackState) = error("not used")
        }
        val noProbe = RuntimePackManager(listOf(RuntimePackManifests.python), installer, RuntimePackExecutionVerifier { false })
        val passed = RuntimePackManager(listOf(RuntimePackManifests.python), installer, RuntimePackExecutionVerifier { it.manifest.id == "python" })

        assertFalse(noProbe.state("python")!!.agentExecutable)
        assertTrue(passed.state("python")!!.agentExecutable)
    }
}
