package com.agentdroid.core.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface RuntimePackExecutionVerifier {
    suspend fun verify(state: RuntimePackState): Boolean
}

data class RuntimePackCapabilityState(
    val state: RuntimePackState,
    /** True only after an Agent execution probe or a deliberately embedded executable adapter succeeds. */
    val agentExecutable: Boolean
)

class RuntimePackManager(
    manifests: List<RuntimePackManifest>,
    private val installer: RuntimePackInstaller,
    private val executionVerifier: RuntimePackExecutionVerifier
) {
    private val lock = Mutex()
    private val manifestsById = manifests.associateBy { it.id }.also { require(it.size == manifests.size) }
    private val states = LinkedHashMap<String, RuntimePackState>()

    init {
        manifests.forEach { manifest ->
            states[manifest.id] = when (manifest.sourceKind) {
                RuntimePackSourceKind.PLATFORM, RuntimePackSourceKind.EMBEDDED -> RuntimePackState(manifest, RuntimePackStatus.BUNDLED, installedAt = System.currentTimeMillis())
                RuntimePackSourceKind.TRUSTED_DOWNLOAD -> RuntimePackState(manifest, RuntimePackStatus.NOT_INSTALLED)
            }
        }
    }

    suspend fun list(): List<RuntimePackCapabilityState> = lock.withLock {
        states.values.map { state -> RuntimePackCapabilityState(state, verifyExecutable(state)) }
    }

    suspend fun state(id: String): RuntimePackCapabilityState? = lock.withLock {
        states[id]?.let { RuntimePackCapabilityState(it, verifyExecutable(it)) }
    }

    suspend fun install(id: String): Result<RuntimePackCapabilityState> {
        val manifest = manifestsById[id] ?: return Result.failure(IllegalArgumentException("Unknown runtime pack: $id"))
        require(manifest.sourceKind == RuntimePackSourceKind.TRUSTED_DOWNLOAD) { "Bundled/platform packs do not require installation" }
        lock.withLock { states[id] = RuntimePackState(manifest, RuntimePackStatus.INSTALLING) }
        return installer.install(manifest).fold(
            { installed ->
                lock.withLock { states[id] = installed }
                Result.success(RuntimePackCapabilityState(installed, verifyExecutable(installed)))
            },
            { failure ->
                lock.withLock { states[id] = RuntimePackState(manifest, RuntimePackStatus.FAILED, error = failure.message) }
                Result.failure(failure)
            }
        )
    }

    suspend fun uninstall(id: String): Result<Unit> {
        val current = lock.withLock { states[id] } ?: return Result.failure(IllegalArgumentException("Unknown runtime pack: $id"))
        require(current.manifest.sourceKind == RuntimePackSourceKind.TRUSTED_DOWNLOAD) { "Bundled/platform packs cannot be uninstalled" }
        return installer.uninstall(current).onSuccess {
            lock.withLock { states[id] = RuntimePackState(current.manifest.copy(installedAt = null), RuntimePackStatus.NOT_INSTALLED) }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> = runCatching {
        lock.withLock {
            val current = states[id] ?: error("Unknown runtime pack: $id")
            require(current.status != RuntimePackStatus.NOT_INSTALLED && current.status != RuntimePackStatus.INSTALLING) { "Runtime pack is not installed" }
            states[id] = current.copy(status = if (enabled) {
                if (current.manifest.sourceKind == RuntimePackSourceKind.TRUSTED_DOWNLOAD) RuntimePackStatus.INSTALLED else RuntimePackStatus.BUNDLED
            } else RuntimePackStatus.DISABLED)
        }
    }

    private suspend fun verifyExecutable(state: RuntimePackState): Boolean {
        if (!state.installedOrBundled) return false
        return runCatching { executionVerifier.verify(state) }.getOrDefault(false)
    }
}
