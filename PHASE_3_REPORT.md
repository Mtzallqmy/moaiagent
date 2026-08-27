# AgentDroid Phase 3 Report

## Implemented

Phase 3 extends the Phase 1 provider/chat foundation and Phase 2 agent/workspace system without replacing their APIs. The branch adds real terminal, process, runtime, Git, permission, audit, persistence, and workspace UI capabilities.

## Architecture

- Independent `core:runtime`, `core:terminal`, and `core:git` modules.
- UI and ViewModels depend on the module abstractions rather than invoking `Runtime.exec`, `ProcessBuilder`, or native process APIs directly.
- Files, Agent tools, Terminal sessions, processes, and Git resolve the same canonical workspace root.

## PTY

- Real Termux terminal-emulator/terminal-view PTY integration with UTF-8, ANSI emulation, stdin, transcript/scrollback, resize, close, kill, exit status, workspace cwd, and bounded metadata persistence.
- The PTY naturally presents a combined terminal stream; non-interactive `ProcessRunner` preserves separate stdout and stderr.

## Terminal

- LTR terminal screen using the real Termux `TerminalView`.
- Multiple sessions, create/switch/rename/close/clear, copy/paste, scrollback, keyboard input, and explicit Ctrl+C, Tab, and Esc controls.
- Session metadata is stored without persisting large scrollback data. Previously running sessions are marked non-running after process death rather than presented as live.

## Process

- Bounded foreground/background process execution, status, output, stdin, terminate/kill, exit code, duration, timeouts, cancellation, concurrency limits, and redacted output.
- Background processes are independent from foreground Agent-turn cancellation; foreground processes are killed when their owning turn is cancelled.

## Runtime

- Android shell/Toybox discovery and future `RuntimePack` / `RuntimePackManager` abstractions.
- Detection-only status for Git, Python, Node, Rust, and Go; no runtime pack downloader or full language runtime was added.

## Agent Shell Tools

- `run_command`, `start_process`, `process_status`, `process_output`, `send_process_input`, `stop_process`, and `list_processes` are registered in the central ToolRegistry.
- Plan mode receives only read-only operations; modifying process operations remain Agent-mode only.

## Permissions

- Commands are tokenized and classified as SAFE, MODIFY, DESTRUCTIVE, EXTERNAL, or SENSITIVE before permission evaluation.
- Allow once, allow session, persistent constrained command-pattern rules, and deny are supported.
- Persistent command permissions are bound to effective risk so a MODIFY approval cannot authorize a later DESTRUCTIVE variant.
- Absolute/traversal paths, execution wrappers, command substitution, unsafe variable expansion, explicit recursive symlink following, and canonical symlink escapes are blocked for Agent commands.

## Git Engine

- Provider-neutral `GitEngine` with a real JGit backend.
- `git_init`, `git_status`, `git_diff`, `git_log`, `git_branches`, `git_checkout`, `git_add`, `git_commit`, and `git_restore` tools.
- Repository/path/branch/commit-message validation, bounded diffs, unborn-repository log/unstage handling, and error propagation.

## Git UI

- Workspace Git screen showing branch, changed/staged files, diff preview, stage/unstage, restore confirmation, and commit confirmation.
- Agent Git operations use ToolRegistry and PermissionEngine; direct user UI actions require explicit UI intent/confirmation for destructive changes.

## Security

- The Model has no direct access to `Runtime.exec`, `ProcessBuilder`, JNI, Android intents, or paths outside the workspace.
- Command and log redaction cover common tokens, passwords, API keys, authorization values, bearer credentials, and URL credentials.
- Dangerous Git reset/clean/restore behavior is never classified as automatically safe.

## Tests

- Unit coverage for command parsing/classification, workspace cwd/traversal/symlink protection, permission-pattern scoping, redaction, timeout/truncation, ProcessManager behavior, JGit workflows/validation/unborn repositories, and Agent runtime/Git integration.
- Android instrumentation smoke coverage for real PTY sessions and workspace Git UI.

## CI Verification

GitHub Actions `Phase 3 Verification` run #53 completed successfully for PR #2. The successful workflow ran:

```text
./gradlew clean
./gradlew test
./gradlew lintDebug
./gradlew assembleDebugAndroidTest
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew connectedDebugAndroidTest
```

The `verify` job passed clean, unit tests, lint, AndroidTest compilation, debug build, and minified release build. The dependent `instrumentation` job also passed on an Android API 35 x86_64 emulator.

Local Gradle execution could not begin in the original workspace because Gradle 8.10.2 was not cached and access to `services.gradle.org` was restricted. CI is therefore the authoritative build verification for this report.

## Runtime Verification

The Android API 35 emulator successfully ran the Phase 3 PTY and Git UI instrumentation suite through `connectedDebugAndroidTest`. This validates real session creation, terminal output, multiple-session switching/closure, repository initialization, status/diff/staging, and commit confirmation at the covered smoke-test level. No physical-device run is claimed.

## Open Source Components

- Termux terminal-emulator / terminal-view (GPL-3.0) provides the Android PTY terminal implementation.
- Eclipse JGit (EDL-1.0) provides the Git backend.
- Reuse, coordinates, licenses, and notices are documented in `docs/OPEN_SOURCE_COMPONENTS.md` and `THIRD_PARTY_NOTICES.md`.

## Remaining

- Physical-device validation across the project's supported Android hardware remains recommended as follow-up hardening; it is not claimed as a completed CI gate.
- Full runtime packs, Browser, research, tasks, artifacts, subagents, and local LLM runtimes remain intentionally deferred to later phases.

## Ready for Phase 4

Ready for Phase 4: YES
