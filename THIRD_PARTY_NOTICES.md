# Third-Party Notices

This file records components directly introduced in AgentDroid Phases 2–3. Existing Android/Kotlin dependencies retain their upstream license terms.

## java-diff-utils 4.17

- Package: `io.github.java-diff-utils:java-diff-utils:4.17`
- Upstream: `java-diff-utils/java-diff-utils`
- License: Apache License 2.0
- Purpose: line diff, unified diff generation/parsing, and patch application.

## Termux terminal-view / terminal-emulator 0.118.0

- Package: `com.termux.termux-app:terminal-view:0.118.0` (with terminal-emulator transitively)
- Upstream: `termux/termux-app`
- License for these terminal libraries: Apache License 2.0. The Termux root license identifies terminal-view and terminal-emulator as explicit exceptions to the GPLv3 license covering the broader application repository.
- Purpose: interactive terminal view/emulator, ANSI/UTF-8 terminal state, scrollback, keyboard input, and the native PTY/subprocess layer.

AgentDroid does not bundle the GPL Termux application or `termux-shared` as part of this Phase 3 terminal integration.

## Eclipse JGit 5.13.5.202508271544-r

- Package: `org.eclipse.jgit:org.eclipse.jgit:5.13.5.202508271544-r`
- Upstream: `eclipse-jgit/jgit`
- License: Eclipse Distribution License 1.0 (SPDX BSD-3-Clause)
- Purpose: embedded local Git repository operations on Android.

JGit core may bring transitive dependencies including JavaEWAH (Apache-2.0) and SLF4J API (MIT); those remain subject to their own upstream notices and licenses.

## General notice

Copyright and license notices remain the property of their upstream authors. Apache-2.0 and BSD-3-Clause/EDL components are distributed subject to their respective preservation and disclaimer requirements. Architecture-only references such as OpenCode, Hermes Agent, and libgit2 are documented in `docs/OPEN_SOURCE_COMPONENTS.md` and are not linked Phase 3 components.
