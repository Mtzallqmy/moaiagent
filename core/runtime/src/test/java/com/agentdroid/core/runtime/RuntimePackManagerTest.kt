package com.agentdroid.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePackManagerTest {
    @Test fun `installed Node component is not executable until runtime probe succeeds`(): Unit = runBlocking {
        val installer = object : RuntimePackInstaller {
            override suspend fun install(manifest: RuntimePackManifest) = Result.success(
                RuntimePackState(manifest, RuntimePackStatus.INSTALLED, installedAt = 1L, installPath = "/runtime/node", verifiedChecksum = manifest.checksum)
            )
            override suspend fun uninstall(state: RuntimePackState) = Result.success(Unit)
        }
        val manager = RuntimePackManager(listOf(RuntimePackManifests.node), installer, RuntimePackExecutionVerifier { false })

        val installed = manager.install("node").getOrThrow()

        assertTrue(installed.state.status == RuntimePackStatus.INSTALLED)
        assertFalse(installed.agentExecutable)
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
