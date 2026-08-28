# Third-Party Notices

This file records notable third-party components directly distributed, linked, or optionally downloaded by AgentDroid through Phase 5. Copyright and full license texts remain with their upstream owners.

## llama.cpp v0.3.0

- Upstream: `ggml-org/llama.cpp`.
- License: MIT.
- Purpose: local GGUF model loading and inference through AgentDroid's Android NDK/JNI backend.
- Integration: fetched at build time from the pinned Git tag; upstream source is not copied into this repository and is not modified.

## Chaquopy 17.0.0

- Upstream: `chaquo/chaquopy`.
- License: MIT.
- Purpose: embed and package CPython for Android and bridge Kotlin/Java to Python.

## CPython 3.13

- Upstream: Python Software Foundation / `python/cpython`.
- License: Python Software Foundation License Version 2 and applicable bundled third-party notices.
- Purpose: executable embedded Python runtime used by AgentDroid's workspace Python tooling.

## Node.js Mobile 18.20.4 Android pack

- Upstream: `nodejs-mobile/nodejs-mobile` and Node.js.
- License: MIT for Node.js Mobile / Node.js, with additional upstream third-party notices in the distribution.
- Purpose: optional trusted-download runtime pack candidate. The official ZIP is checksum-pinned by AgentDroid.
- Status: not bundled into the APK and not advertised as an Agent-executable Node runtime until a verified execution bridge exists.

## AndroidX WorkManager 2.11.2

- Package: `androidx.work:work-runtime:2.11.2`.
- License: Apache License 2.0.
- Purpose: Android-constrained one-time and periodic workspace automations.

## java-diff-utils 4.17

- Package: `io.github.java-diff-utils:java-diff-utils:4.17`.
- Upstream: `java-diff-utils/java-diff-utils`.
- License: Apache License 2.0.
- Purpose: line diff, unified diff generation/parsing, and patch application.

## Termux terminal-view / terminal-emulator 0.118.0

- Package: `com.termux.termux-app:terminal-view:0.118.0`, with terminal-emulator transitively.
- Upstream: `termux/termux-app`.
- License for these two terminal libraries: Apache License 2.0 exception documented by the upstream Termux repository.
- Purpose: terminal UI/emulation and native PTY/subprocess layer.

AgentDroid does not bundle the broader GPL Termux application or `termux-shared`.

## Eclipse JGit 5.13.5.202508271544-r

- Package: `org.eclipse.jgit:org.eclipse.jgit:5.13.5.202508271544-r`.
- Upstream: `eclipse-jgit/jgit`.
- License: Eclipse Distribution License 1.0 / BSD-3-Clause SPDX classification.
- Purpose: embedded local Git repository operations.

JGit core may include transitive dependencies such as JavaEWAH and SLF4J API, each subject to its upstream license.

## OkHttp 4.12.0 and MockWebServer 4.12.0

- Upstream: `square/okhttp`.
- License: Apache License 2.0.
- Purpose: HTTP transports for provider/research/MCP/runtime-pack operations; MockWebServer is test-only.

## Android System WebView

- Provider: Android system component based on Chromium.
- License: Chromium BSD-style license plus component-specific third-party licenses as distributed by the device/system WebView provider.
- Purpose: browser rendering. AgentDroid uses the platform API and does not redistribute Chromium.

## DuckDuckGo Instant Answer API

AgentDroid contains an optional client for the public DuckDuckGo Instant Answer API. No DuckDuckGo software is bundled or copied; service access remains subject to its terms and availability.

## MCP specification

AgentDroid implements the Model Context Protocol Streamable HTTP/JSON-RPC interaction itself. No MCP SDK source is bundled solely for this implementation.

## General notice

For architecture-only references and the exact reuse boundary, see `docs/OPEN_SOURCE_COMPONENTS.md`. Components considered but not integrated, including LiteRT-LM, are not represented here as distributed dependencies.
