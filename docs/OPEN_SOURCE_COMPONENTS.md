# Open Source Components and Architecture References

This inventory covers components used through Phase 4. Existing AndroidX, Kotlin,
Compose, Room, coroutines, and serialization dependencies remain under their
respective upstream licenses.

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

## Android System WebView

- Project: Chromium-based Android System WebView supplied by the device/runtime.
- License: Chromium/WebView is distributed under BSD-style and component-specific open-source licenses by the Android system provider.
- Usage in Phase 4: isolated browser rendering behind `core:browser`. AgentDroid does not bundle a browser engine binary and does not expose WebView or a JavaScript bridge to the model.

## OkHttp

- Project: `square/okhttp`.
- Version: managed by the repository version catalog.
- License: Apache-2.0.
- Usage in Phase 4: bounded research search/source HTTP transport. OkHttp was already used by the AI transport before Phase 4.

## MockWebServer

- Project: `square/okhttp`, module `mockwebserver`.
- License: Apache-2.0.
- Usage in Phase 4: test-only local HTTP pages and deterministic research/browser integration tests. CI does not rely on public websites.

## DuckDuckGo Instant Answer API

- Service/API: `https://api.duckduckgo.com/`.
- Usage in Phase 4: optional concrete backend behind `WebSearchProvider`.
- No DuckDuckGo source code is copied or linked. Runtime API usage remains subject to the provider's terms and availability.

## Source references

- Termux library import and dependency guidance: `https://github.com/termux/termux-app/wiki/Termux-Libraries`
- Termux license exceptions: `https://github.com/termux/termux-app/blob/master/LICENSE.md`
- Termux terminal native build: `terminal-emulator/build.gradle`
- JGit project/license: `https://github.com/eclipse-jgit/jgit`
- Android WebView source: `https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/`
- OkHttp project/license: `https://github.com/square/okhttp`

## Reuse policy

Directly distributed libraries are also recorded in `THIRD_PARTY_NOTICES.md`. Architecture references with no copied or linked code remain documented here for traceability only.
