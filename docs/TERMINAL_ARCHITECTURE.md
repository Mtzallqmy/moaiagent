# Terminal Architecture

## Boundary

`core:terminal` owns terminal sessions. Compose never creates a subprocess and the model never receives a `TerminalSession` or JNI handle. The boundary is:

`Workspace UI → TerminalManager → TermuxTerminalManager → Termux TerminalSession → PTY/JNI`

Agent command execution is a separate path through `core:runtime`; interactive terminal input is explicitly user-driven.

## PTY implementation

AgentDroid uses Termux `terminal-view` `0.118.0`, which pulls `terminal-emulator` as a dependency. The Termux terminal library creates the subprocess through its native PTY layer. It provides terminal emulation, UTF-8, ANSI escape processing, transcript/scrollback, input, process exit status, and terminal resize support. AgentDroid does not implement a fake text console.

`TermuxTerminalManager` wraps the native API and enforces workspace-relative working directories before creating a session. The environment sets workspace `HOME`, `PWD`, and `TMPDIR`; system executable locations are exposed through `PATH`.

## Session model

`TerminalManager` supports multiple live sessions. Each session exposes:

- session id and workspace id
- title and working directory
- process pid when available
- running/exit status
- UTF-8 input and code-point input
- rows/columns resize
- clear/reset
- graceful close and force kill
- bounded in-memory transcript supplied by the Termux emulator

The UI supports create, rename, switch, close, clear, copy, paste, keyboard input, and explicit Ctrl/C/Tab/Esc helpers. `TerminalScreen` forces LTR independently from the app locale.

## Persistence

Room persists only session metadata (`sessionId`, `workspaceId`, `title`, `cwd`, timestamps, running flag, exit code). Scrollback is intentionally not stored. Sessions previously recorded as running are marked non-running when a new process starts; AgentDroid never claims an OS process survived application death when it cannot prove that.

## Workspace integration

Opening Terminal from a workspace root uses `cwd = .`. Opening it while browsing a subdirectory passes that workspace-relative directory. Canonical-path checks prevent `..` and absolute-path escape.

## Native compatibility

The Termux `terminal-emulator` published AAR includes its PTY native layer for x86, x86_64, armeabi-v7a, and arm64-v8a. AgentDroid does not add a second JNI/NDK implementation in Phase 3 because the selected terminal component already supplies the required PTY native layer.
