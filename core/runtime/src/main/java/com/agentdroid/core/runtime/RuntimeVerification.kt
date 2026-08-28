package com.agentdroid.core.runtime

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class RuntimeEvidenceKind {
    NOT_DETECTED,
    DETECTED_ONLY,
    EXECUTION_PROBE_PASSED,
    EMBEDDED_COMPONENT
}

@Serializable
data class RuntimeEvidence(
    val id: String,
    val label: String,
    val detected: Boolean,
    val executableByAgent: Boolean,
    val evidenceKind: RuntimeEvidenceKind,
    val version: String? = null,
    val executable: String? = null,
    val detail: String? = null
)

@Serializable
data class RuntimeCapabilityEvidence(
    val runtimes: List<RuntimeEvidence>
) {
    /** Only capabilities proven executable through AgentDroid's own ProcessRunner are advertised. */
    fun plannerCapabilities(): List<String> = buildList {
        runtimes.filter { it.executableByAgent }.forEach { evidence ->
            when (evidence.id) {
                "shell" -> add("runtime.shell")
                "python" -> add("runtime.python")
                "node" -> add("runtime.node")
            }
        }
    }.distinct().sorted()
}

/**
 * Converts runtime detection into execution evidence. Version/detection alone never becomes an
 * executable Agent capability. This prevents reporting a host dependency as a bundled/runtime feature.
 */
class RuntimeVerifier(
    private val runner: ProcessRunner,
    private val probeDirectory: File,
    private val timeoutMs: Long = 4_000
) {
    init { require(timeoutMs > 0) }

    suspend fun verify(components: List<RuntimeComponent>): RuntimeCapabilityEvidence =
        RuntimeCapabilityEvidence(components.map { component -> verify(component) })

    suspend fun verify(component: RuntimeComponent): RuntimeEvidence {
        if (!component.available) {
            return RuntimeEvidence(
                id = component.id,
                label = component.label,
                detected = false,
                executableByAgent = false,
                evidenceKind = RuntimeEvidenceKind.NOT_DETECTED
            )
        }

        if (component.executable?.startsWith("embedded:") == true) {
            return RuntimeEvidence(
                id = component.id,
                label = component.label,
                detected = true,
                executableByAgent = false,
                evidenceKind = RuntimeEvidenceKind.EMBEDDED_COMPONENT,
                version = component.version,
                executable = component.executable,
                detail = "Embedded application component; not a language runtime executable"
            )
        }

        val request = executionProbe(component)
        if (request == null) {
            return RuntimeEvidence(
                id = component.id,
                label = component.label,
                detected = true,
                executableByAgent = false,
                evidenceKind = RuntimeEvidenceKind.DETECTED_ONLY,
                version = component.version,
                executable = component.executable,
                detail = when (component.id) {
                    "rust", "go" -> "Toolchain detection only; no AgentDroid subsystem is backed by this language"
                    else -> "Detected component has no execution capability probe"
                }
            )
        }

        val result = runCatching { runner.run(request) }.getOrNull()
        val passed = result?.exitCode == 0 && !result.timedOut && PROBE_MARKER in result.stdout
        return RuntimeEvidence(
            id = component.id,
            label = component.label,
            detected = true,
            executableByAgent = passed,
            evidenceKind = if (passed) RuntimeEvidenceKind.EXECUTION_PROBE_PASSED else RuntimeEvidenceKind.DETECTED_ONLY,
            version = component.version,
            executable = component.executable,
            detail = if (passed) "Executed a bounded code probe through AgentDroid ProcessRunner" else
                "Detected but AgentDroid execution probe did not pass"
        )
    }

    private fun executionProbe(component: RuntimeComponent): ProcessRequest? {
        val executable = component.executable ?: return null
        val argv = when (component.id) {
            "shell" -> listOf(executable, "-c", "printf '$PROBE_MARKER'")
            "python" -> listOf(executable, "-c", "print('$PROBE_MARKER')")
            "node" -> listOf(executable, "-e", "process.stdout.write('$PROBE_MARKER')")
            else -> return null
        }
        return ProcessRequest(
            argv = argv,
            cwd = probeDirectory,
            timeoutMs = timeoutMs,
            maxStdoutBytes = 4_096,
            maxStderrBytes = 4_096
        )
    }

    companion object { private const val PROBE_MARKER = "AGENTDROID_RUNTIME_OK" }
}
