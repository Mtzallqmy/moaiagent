package com.agentdroid.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

@Serializable
enum class RuntimePackSourceKind { PLATFORM, EMBEDDED, TRUSTED_DOWNLOAD }

@Serializable
data class RuntimePackManifest(
    val id: String,
    val displayName: String,
    val version: String,
    val architectures: List<String>,
    val sizeBytes: Long,
    val checksum: String,
    val source: String,
    val license: String,
    val installedAt: Long? = null,
    val executables: List<String>,
    val sourceKind: RuntimePackSourceKind
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")))
        require(displayName.isNotBlank() && version.isNotBlank())
        require(sizeBytes >= 0)
        require(architectures.isNotEmpty())
        require(source.isNotBlank() && license.isNotBlank() && checksum.isNotBlank())
        require(executables.distinct().size == executables.size)
        if (sourceKind == RuntimePackSourceKind.TRUSTED_DOWNLOAD) {
            require(checksum.matches(Regex("[a-f0-9]{64}"))) { "Downloadable runtime packs require SHA-256" }
            require(source.startsWith("https://")) { "Runtime pack downloads require HTTPS" }
        }
    }
}

@Serializable
enum class RuntimePackStatus { NOT_INSTALLED, INSTALLING, INSTALLED, DISABLED, FAILED, BUNDLED }

@Serializable
data class RuntimePackState(
    val manifest: RuntimePackManifest,
    val status: RuntimePackStatus,
    val installedAt: Long? = null,
    val installPath: String? = null,
    val verifiedChecksum: String? = null,
    val error: String? = null
) {
    /** Installation/package state only. Runtime execution support requires a separate execution probe. */
    val installedOrBundled: Boolean get() = status == RuntimePackStatus.INSTALLED || status == RuntimePackStatus.BUNDLED
}

interface RuntimePackInstaller {
    suspend fun install(manifest: RuntimePackManifest): Result<RuntimePackState>
    suspend fun uninstall(state: RuntimePackState): Result<Unit>
}

class VerifiedZipRuntimePackInstaller(
    private val installRoot: File,
    private val openSource: suspend (String) -> InputStream,
    private val maxArchiveBytes: Long = 512L * 1024 * 1024,
    private val maxExpandedBytes: Long = 2L * 1024 * 1024 * 1024
) : RuntimePackInstaller {
    init {
        require(maxArchiveBytes > 0 && maxExpandedBytes >= maxArchiveBytes)
        installRoot.mkdirs()
    }

    override suspend fun install(manifest: RuntimePackManifest): Result<RuntimePackState> = withContext(Dispatchers.IO) {
        runCatching {
            require(manifest.sourceKind == RuntimePackSourceKind.TRUSTED_DOWNLOAD) { "Only trusted-download manifests can be installed" }
            val staging = File(installRoot, ".${manifest.id}-${System.nanoTime()}").apply { mkdirs() }
            val archive = File(staging, "pack.zip")
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            try {
                openSource(manifest.source).use { input ->
                    archive.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            bytes += count
                            require(bytes <= maxArchiveBytes) { "Runtime pack archive is too large" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual == manifest.checksum) { "Runtime pack SHA-256 mismatch" }
                if (manifest.sizeBytes > 0) require(bytes == manifest.sizeBytes) { "Runtime pack size mismatch" }

                val expanded = File(staging, "expanded").apply { mkdirs() }
                var expandedBytes = 0L
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        require(name.isNotBlank() && !name.startsWith('/') && !name.contains("../") && name != "..") { "Unsafe runtime pack entry" }
                        val target = File(expanded, name).canonicalFile
                        require(target == expanded.canonicalFile || target.path.startsWith(expanded.canonicalPath + File.separator)) { "Runtime pack entry escapes install directory" }
                        if (entry.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    expandedBytes += count
                                    require(expandedBytes <= maxExpandedBytes) { "Runtime pack expands beyond safety limit" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
                manifest.executables.forEach { relative ->
                    require(relative.isNotBlank() && !File(relative).isAbsolute && ".." !in relative.replace('\\', '/').split('/'))
                    val executable = File(expanded, relative).canonicalFile
                    require(executable.exists() && executable.isFile) { "Declared runtime executable is missing: $relative" }
                    executable.setExecutable(true, true)
                }
                val destination = File(installRoot, manifest.id)
                if (destination.exists()) require(destination.deleteRecursively()) { "Could not replace installed runtime pack" }
                require(expanded.renameTo(destination)) { "Could not commit runtime pack installation" }
                val installedAt = System.currentTimeMillis()
                RuntimePackState(
                    manifest = manifest.copy(installedAt = installedAt),
                    status = RuntimePackStatus.INSTALLED,
                    installedAt = installedAt,
                    installPath = destination.canonicalPath,
                    verifiedChecksum = actual
                )
            } finally { staging.deleteRecursively() }
        }
    }

    override suspend fun uninstall(state: RuntimePackState): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(state.manifest.sourceKind == RuntimePackSourceKind.TRUSTED_DOWNLOAD) { "Bundled/platform runtimes cannot be uninstalled" }
            val path = state.installPath ?: return@runCatching
            val target = File(path).canonicalFile
            val root = installRoot.canonicalFile
            require(target.parentFile == root) { "Runtime uninstall target is outside runtime root" }
            require(!target.exists() || target.deleteRecursively()) { "Could not remove runtime pack" }
        }
    }
}

object RuntimePackManifests {
    val baseShell = RuntimePackManifest(
        id = "base-shell", displayName = "Android shell", version = "platform",
        architectures = listOf("platform"), sizeBytes = 0, checksum = "platform-managed",
        source = "Android platform", license = "Android platform component", executables = listOf("sh"),
        sourceKind = RuntimePackSourceKind.PLATFORM
    )
    val git = RuntimePackManifest(
        id = "git", displayName = "Git", version = "JGit embedded",
        architectures = listOf("jvm"), sizeBytes = 0, checksum = "apk-signature-bound",
        source = "org.eclipse.jgit", license = "Eclipse Distribution License 1.0", executables = listOf("embedded:jgit"),
        sourceKind = RuntimePackSourceKind.EMBEDDED
    )
    val python = RuntimePackManifest(
        id = "python", displayName = "Python", version = "3.13 / Chaquopy 17.0.0",
        architectures = listOf("arm64-v8a", "x86_64"), sizeBytes = 0, checksum = "apk-signature-bound",
        source = "com.chaquo.python 17.0.0", license = "MIT + CPython PSF-2.0", executables = listOf("embedded:python3"),
        sourceKind = RuntimePackSourceKind.EMBEDDED
    )
    val node = RuntimePackManifest(
        id = "node", displayName = "Node.js Mobile", version = "18.20.4",
        architectures = listOf("arm64-v8a", "x86_64"), sizeBytes = 57_287_354,
        checksum = "bd7321eaa1a7602fbe0bb87302df2d79d87835cf4363fbdd17c350dbb485c2af",
        source = "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v18.20.4/nodejs-mobile-v18.20.4-android.zip",
        license = "MIT (Node.js Mobile) + bundled Node.js third-party notices",
        executables = listOf("bin/arm64-v8a/libnode.so", "bin/x86_64/libnode.so"),
        sourceKind = RuntimePackSourceKind.TRUSTED_DOWNLOAD
    )
}
