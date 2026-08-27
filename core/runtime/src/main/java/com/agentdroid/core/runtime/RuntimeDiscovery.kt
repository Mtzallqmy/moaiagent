package com.agentdroid.core.runtime

import java.io.File

class RuntimeDiscovery(
    private val runner: ProcessRunner,
    private val probeDirectory: File,
    private val shellPath: String = defaultShellPath(),
    private val gitFallback: (suspend () -> RuntimeComponent)? = null
) : RuntimePackManager {
    private val packs: List<RuntimePack> = listOf(
        ShellRuntimePack(runner, probeDirectory, shellPath),
        ExecutableRuntimePack("git", "Git", "git", listOf("--version"), runner, probeDirectory, gitFallback),
        ExecutableRuntimePack("python", "Python", "python3", listOf("--version"), runner, probeDirectory),
        ExecutableRuntimePack("node", "Node", "node", listOf("--version"), runner, probeDirectory),
        ExecutableRuntimePack("rust", "Rust", "rustc", listOf("--version"), runner, probeDirectory),
        ExecutableRuntimePack("go", "Go", "go", listOf("version"), runner, probeDirectory)
    )

    override suspend fun list(): List<RuntimeComponent> = packs.map { it.detect() }
    override suspend fun get(id: String): RuntimeComponent? = packs.firstOrNull { it.id == id }?.detect()

    suspend fun snapshot(): RuntimeSnapshot {
        val values = list().associateBy { it.id }
        return RuntimeSnapshot(
            shell = values.getValue("shell"),
            git = values.getValue("git"),
            python = values.getValue("python"),
            node = values.getValue("node"),
            rust = values.getValue("rust"),
            go = values.getValue("go")
        )
    }
}

private class ShellRuntimePack(
    private val runner: ProcessRunner,
    private val cwd: File,
    private val shellPath: String
) : RuntimePack {
    override val id = "shell"
    override val displayName = "Shell"
    override suspend fun detect(): RuntimeComponent {
        val executable = File(shellPath)
        val available = shellPath == "sh" || (executable.exists() && executable.canExecute())
        val version = if (available) probe(runner, ProcessRequest(command = "toybox --version 2>/dev/null || echo Android sh", cwd = cwd, timeoutMs = 2_000)) else null
        return RuntimeComponent(id, displayName, available, version, shellPath)
    }
}

private class ExecutableRuntimePack(
    override val id: String,
    override val displayName: String,
    private val executable: String,
    private val versionArgs: List<String>,
    private val runner: ProcessRunner,
    private val cwd: File,
    private val fallback: (suspend () -> RuntimeComponent)? = null
) : RuntimePack {
    override suspend fun detect(): RuntimeComponent {
        val which = runCatching { runner.run(ProcessRequest(command = "command -v ${shellQuote(executable)}", cwd = cwd, timeoutMs = 2_000)) }.getOrNull()
        val path = which?.stdout?.trim()?.lineSequence()?.firstOrNull()?.takeIf { which.exitCode == 0 && it.isNotBlank() }
        if (path == null) return fallback?.invoke() ?: RuntimeComponent(id, displayName, false)
        val version = runCatching { runner.run(ProcessRequest(argv = listOf(path) + versionArgs, cwd = cwd, timeoutMs = 3_000)) }
            .getOrNull()?.let { (it.stdout.ifBlank { it.stderr }).trim().lineSequence().firstOrNull() }
        return RuntimeComponent(id, displayName, true, version, path)
    }
}

private suspend fun probe(runner: ProcessRunner, request: ProcessRequest): String? = runCatching { runner.run(request) }
    .getOrNull()?.let { (it.stdout.ifBlank { it.stderr }).trim().lineSequence().firstOrNull() }

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
