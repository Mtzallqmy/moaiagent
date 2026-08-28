# Runtime Model

## Architecture

`core:runtime` is the only application layer allowed to construct OS processes. ViewModels, Compose, provider adapters, and model output cannot call `Runtime.exec`, `ProcessBuilder`, JNI, or Android intents directly.

Execution path for the agent:

`Model → ToolRegistry → dynamic risk classification → PermissionEngine → Runtime tool → ProcessManager → ProcessRunner → OS process`

The interactive terminal is separate and user-driven through `TerminalManager`.

## ProcessRunner

`ProcessRunner` defines bounded foreground/background process creation. `DefaultProcessRunner` uses `ProcessBuilder` internally and handles:

- workspace working directory
- explicit environment
- independent stdout/stderr collection
- UTF-8 decoding
- timeout and forced termination
- cancellation propagation
- stdin writes and broken-pipe errors
- bounded buffers and truncation flags
- exit code/duration/status
- output redaction

## ProcessManager

`ProcessManager` gives processes stable application IDs and tracks foreground/background lifecycle independently from screens. It supports status, list, logs, stdin, terminate, force-kill, and foreground cancellation by agent session.

Default limits are centralized in `RuntimeLimits`: 1 MiB stdout, 512 KiB stderr, 30 s foreground timeout, 30 min maximum runtime, 6 background processes, and 8 concurrent processes. Tools may request a smaller timeout but cannot exceed the configured maximum.

Background processes survive navigation and an Agent turn completing. Cancelling an Agent coroutine kills its currently awaited foreground process through cancellation propagation; separately started background processes are not implicitly killed.

Room stores process metadata only. Processes left in `STARTING`/`RUNNING` after application death are changed to `STALE` on the next application lifecycle rather than presented as alive.

## Shell environment

Android shell discovery starts with `/system/bin/sh` and falls back to other available `sh` locations. Workspace commands receive `HOME`, `PWD`, `TMPDIR`, `TERM`, `LANG`, and a `PATH` containing the inherited app path plus Android system binary directories.

## Discovery is not runtime support

Phase 3 did not bundle Python, Node.js, Rust, or Go. `RuntimeDiscovery` can detect `python3`, `node`, `rustc`, or `go` and report a version/path, but detection alone is not an Agent capability.

Phase 5 adds `RuntimeVerifier`, which classifies evidence as:

- `NOT_DETECTED`
- `DETECTED_ONLY`
- `EXECUTION_PROBE_PASSED`
- `EMBEDDED_COMPONENT`

A language runtime is advertised to the planner only after bounded code execution succeeds through AgentDroid's own `ProcessRunner` in the application environment. Currently the only language-runtime capability IDs the verifier can emit are:

- `runtime.shell`
- `runtime.python`
- `runtime.node`

Python is probed with a short `-c` program; Node.js is probed with a short `-e` program. Shell is probed through the Android shell. Rust and Go detection remains toolchain evidence only and never becomes `runtime.rust` or `runtime.go` without a real AgentDroid subsystem using them.

A Git implementation such as embedded JGit is classified as an application component, not a language runtime.

GitHub Actions host tools are not runtime evidence for the Android application. Final runtime claims require the Android instrumentation test to execute against the built application environment.

## Native and optional language policy

Phase 5 does not add a language merely to increase implementation count.

- C/C++ is required only when a native inference backend such as llama.cpp is actually integrated through NDK/JNI and present in build artifacts.
- Python and Node.js count as Agent runtimes only when executable from AgentDroid and verified on Android.
- Rust counts as AgentDroid implementation language only if a real native/security subsystem is first-party Rust and linked into the product.
- Go counts only when a real service, CLI, or runtime component is first-party Go and is actually used.
- Lua counts only when a real plugin/skill sandbox executes Lua.

Absent those integrations, these languages must be reported as absent, detected-only, or dependency-only as appropriate.

## Agent tools

Runtime tools include:

- `run_command`
- `start_process`
- `process_status`
- `process_output`
- `send_process_input`
- `stop_process`
- `list_processes`

`run_command` returns stdout, stderr, exit code, duration, timeout state, truncation state, process id, and working directory. Plan mode can use it only when `CommandClassifier` resolves the actual request to `SAFE`.
