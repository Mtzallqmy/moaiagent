# Open Source Components and Architecture References

## java-diff-utils

- Project: `java-diff-utils/java-diff-utils`
- Version: `4.17`
- License: Apache-2.0
- Usage: direct dependency in `core:workspace` for diff calculation, unified diff generation/parsing, and patch application.

## Termux terminal-view / terminal-emulator

- Project: `termux/termux-app`
- Version: `0.118.0`
- Dependency: `com.termux.termux-app:terminal-view:0.118.0` from JitPack; `terminal-emulator` is pulled transitively.
- License: the Termux repository is GPLv3, but its root license explicitly identifies the terminal-view and terminal-emulator libraries as Apache-2.0 exceptions.
- Usage in Phase 3: bundled terminal emulator/view and native PTY implementation. `TermuxTerminalManager` wraps the upstream `TerminalSession`; Compose embeds the upstream `TerminalView`.
- Native layer: terminal-emulator builds its PTY JNI code for x86, x86_64, armeabi-v7a, and arm64-v8a.
- Reuse boundary: AgentDroid does not copy the GPL Termux application or `termux-shared`; it consumes only the specifically excepted terminal libraries.

## Eclipse JGit

- Project: `eclipse-jgit/jgit`
- Version: `5.13.5.202508271544-r`
- Dependency: `org.eclipse.jgit:org.eclipse.jgit`
- License: Eclipse Distribution License 1.0, SPDX BSD-3-Clause.
- Usage in Phase 3: embedded local Git backend in `core:git` for repository init/status/diff/log/branches/checkout/add/commit/restore.
- Remote authentication/push/pull are intentionally not exposed by Phase 3.
- JGit core has transitive dependencies such as JavaEWAH (Apache-2.0) and SLF4J API (MIT), subject to their upstream terms.

## OpenCode

- Project: `anomalyco/opencode`
- License: MIT
- Usage: architecture reference only for centralized/scoped tool registration and permission semantics. No source copied.

## Hermes Agent

- Project: `NousResearch/hermes-agent`
- License: MIT
- Usage: architecture reference only for iterative model → tools → results → model flow. No source copied.

## libgit2

- Project: `libgit2/libgit2`
- License: GPLv2 with linking exception.
- Usage: future `GitEngine` backend architecture reference only. Phase 3 does not link libgit2.

## Source references

- Termux library import and dependency guidance: `https://github.com/termux/termux-app/wiki/Termux-Libraries`
- Termux license exceptions: `https://github.com/termux/termux-app/blob/master/LICENSE.md`
- Termux terminal native build: `terminal-emulator/build.gradle`
- JGit project/license: `https://github.com/eclipse-jgit/jgit`

## Reuse policy

Directly distributed libraries are also recorded in `THIRD_PARTY_NOTICES.md`. Architecture references with no copied or linked code remain documented here for traceability only.
