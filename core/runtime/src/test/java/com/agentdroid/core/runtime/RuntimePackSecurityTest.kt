package com.agentdroid.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimePackSecurityTest {
    @Test fun `installer verifies checksum before extracting`(): Unit = runBlocking {
        val zip = zipOf("bin/tool" to "hello".toByteArray())
        val root = Files.createTempDirectory("runtime-pack").toFile()
        val manifest = manifest(zip, listOf("bin/tool"))
        val installer = VerifiedZipRuntimePackInstaller(root, { ByteArrayInputStream(zip) })

        val state = installer.install(manifest).getOrThrow()

        assertEquals(RuntimePackStatus.INSTALLED, state.status)
        assertEquals(manifest.checksum, state.verifiedChecksum)
        assertTrue(java.io.File(state.installPath, "bin/tool").exists())
    }

    @Test fun `installer rejects a modified archive`(): Unit = runBlocking {
        val zip = zipOf("bin/tool" to "hello".toByteArray())
        val root = Files.createTempDirectory("runtime-pack-bad-hash").toFile()
        val manifest = manifest(zip, listOf("bin/tool")).copy(checksum = "0".repeat(64))
        val installer = VerifiedZipRuntimePackInstaller(root, { ByteArrayInputStream(zip) })

        assertTrue(installer.install(manifest).isFailure)
        assertTrue(root.listFiles().orEmpty().none { it.name == manifest.id })
    }

    @Test fun `installer rejects zip traversal even with trusted checksum`(): Unit = runBlocking {
        val zip = zipOf("../escape" to "no".toByteArray())
        val root = Files.createTempDirectory("runtime-pack-traversal").toFile()
        val manifest = manifest(zip, emptyList())
        val installer = VerifiedZipRuntimePackInstaller(root, { ByteArrayInputStream(zip) })

        assertTrue(installer.install(manifest).isFailure)
        assertFalse(java.io.File(root.parentFile, "escape").exists())
    }

    @Test fun `official node manifest pins CI measured sha256`() {
        assertEquals(64, RuntimePackManifests.node.checksum.length)
        assertEquals(57_287_354, RuntimePackManifests.node.sizeBytes)
        assertTrue("arm64-v8a" in RuntimePackManifests.node.architectures)
        assertTrue("x86_64" in RuntimePackManifests.node.architectures)
    }

    private fun manifest(bytes: ByteArray, executables: List<String>): RuntimePackManifest = RuntimePackManifest(
        id = "test-pack", displayName = "Test", version = "1",
        architectures = listOf("x86_64"), sizeBytes = bytes.size.toLong(), checksum = sha256(bytes),
        source = "https://example.invalid/test.zip", license = "MIT", executables = executables,
        sourceKind = RuntimePackSourceKind.TRUSTED_DOWNLOAD
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
