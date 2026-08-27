# Open Source Components and Architecture References

Phase 2 reviewed the following projects before implementing the corresponding subsystems. The default approach was to reimplement the applicable architecture in Kotlin rather than copy complete systems.

## java-diff-utils

- Project: `java-diff-utils/java-diff-utils`
- License: Apache-2.0
- AgentDroid version: `io.github.java-diff-utils:java-diff-utils:4.17`
- Usage: direct runtime dependency in `core/workspace` for diff calculation, unified diff generation/parsing, and patch application.
- Reuse: library API usage only; AgentDroid's `DiffEngine`, ChangeSet model, staging, hash validation, and UI are AgentDroid code.

## OpenCode

- Project: `anomalyco/opencode`
- License: MIT
- Usage: architecture reference only.
- Design ideas reviewed: centralized/scoped tool registration, separation of registry mechanics from permission policy, `allow/ask/deny` permission semantics, and bounded tool output.
- Reuse: no OpenCode source file was copied.

## Hermes Agent

- Project: `NousResearch/hermes-agent`
- License: MIT
- Usage: architecture reference only.
- Design ideas reviewed: iterative model → tool calls → tool results → model flow and centralized tool definitions.
- Reuse: no Hermes Agent source file was copied.

## Termux terminal-emulator / terminal-view

- Project family: Termux terminal components.
- License for the terminal emulator/view libraries: Apache-2.0 (the broader `termux-app` repository is GPLv3 with explicit exceptions for these libraries).
- Usage: architecture reference for a future terminal UI/runtime boundary only.
- Reuse in Phase 2: none. Phase 2 intentionally has no terminal or process-execution implementation.

## libgit2

- Project: `libgit2/libgit2`
- License: GPLv2 with a linking exception.
- Usage: architecture reference for a future Git abstraction boundary only.
- Reuse in Phase 2: none. No libgit2 binary/source is linked or copied and full Git execution is deferred.

## Reuse policy

When a third-party library is actually distributed with AgentDroid, its license and notice obligations must be represented in `THIRD_PARTY_NOTICES.md` and release packaging as appropriate. Architecture references that contributed concepts but no copied/linkable code are documented here for traceability and are not listed as bundled components.
