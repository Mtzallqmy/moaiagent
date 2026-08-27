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

Phase 3 does not bundle Python, Node, Rust, or Go. `RuntimeDiscovery` detects them and reports availability/version only. The `RuntimePack`/`RuntimePackManager` interfaces reserve a stable boundary for future runtime packs without adding a downloader now.

## Agent tools

Phase 3 adds:

- `run_command`
- `start_process`
- `process_status`
- `process_output`
- `send_process_input`
- `stop_process`
- `list_processes`

`run_command` returns stdout, stderr, exit code, duration, timeout state, truncation state, process id, and working directory. Plan mode can use it only when `CommandClassifier` resolves the actual request to `SAFE`.
