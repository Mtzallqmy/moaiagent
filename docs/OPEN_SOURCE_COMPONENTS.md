# Open Source Components and Architecture References

This inventory records components directly used by AgentDroid through Phase 5 and distinguishes linked/bundled software from architecture-only references and optional downloadable runtime packs.

## llama.cpp

- Project: `ggml-org/llama.cpp`.
- Pinned version: `v0.3.0`.
- License: MIT.
- Usage: native local-inference backend in `core:localai`.
- Integration: CMake `FetchContent` at build time; AgentDroid first-party `llama_jni.cpp` links the upstream `llama` library through Android NDK/JNI.
- Build reduction: examples, tests, server, tools, curl, RPC, OpenMP and dynamic backend loading are disabled for the Android library build.
- Copied upstream files: none committed into the AgentDroid repository.
- Modifications to upstream source: none; integration is through upstream public C API.

## Chaquopy 17.0.0 and CPython 3.13

- Project: `chaquo/chaquopy`.
- Version: Chaquopy Gradle plugin/runtime `17.0.0`; configured Python language line `3.13`.
- License: Chaquopy is MIT-licensed. CPython remains under the Python Software Foundation License and bundled third-party notices.
- Usage: real embedded Python runtime in the Android app, including the first-party `app/src/main/python/agentdroid_runtime.py` sandbox adapter.
- Supported Agent path: Python execution is exposed through permission-gated Agent tools; package installation is a separate external-risk operation.
- Copied upstream files: none manually copied.

## Node.js Mobile runtime pack

- Project: `nodejs-mobile/nodejs-mobile`.
- Version: `18.20.4` Android release pack.
- License: MIT for Node.js Mobile / Node.js, plus licenses recorded by the upstream distribution for bundled third-party components.
- Usage: optional trusted-download Runtime Pack source only.
- Integrity: AgentDroid pins SHA-256 `bd7321eaa1a7602fbe0bb87302df2d79d87835cf4363fbdd17c350dbb485c2af` for the official Android ZIP.
- Important boundary: the pack contains `libnode.so`, but AgentDroid does not currently provide a verified `node::Start` JNI execution bridge. Therefore Node is not reported as an Agent-executable runtime.
- Copied upstream files: none committed in the repository.

## AndroidX WorkManager

- Package: `androidx.work:work-runtime:2.11.2`.
- License: Apache License 2.0.
- Usage: constrained one-time/periodic workspace automations respecting Android scheduler, Doze and background-execution limits. The worker creates a durable task and does not run an unlimited background Agent loop.

## Model Context Protocol

- Specification: Model Context Protocol (MCP).
- Usage: AgentDroid implements its own bounded Streamable HTTP JSON-RPC client/adapters in `core:mcp` using existing OkHttp and Kotlin serialization dependencies.
- No MCP SDK source is copied or linked.
- Trust boundary: MCP servers are treated as external; tools enter through `McpToolAdapter -> ToolRegistry -> PermissionEngine`.

## java-diff-utils

- Project: `java-diff-utils/java-diff-utils`.
- Version: `4.17`.
- License: Apache-2.0.
- Usage: direct dependency in `core:workspace` for diff calculation, unified diff generation/parsing, and patch application.

## Termux terminal-view / terminal-emulator

- Project: `termux/termux-app`.
- Version: `0.118.0`.
- Dependency: `com.termux.termux-app:terminal-view:0.118.0` from JitPack; `terminal-emulator` is pulled transitively.
- License: terminal-view and terminal-emulator are Apache-2.0 exceptions identified by the Termux repository license.
- Usage: terminal view/emulator and native PTY layer. AgentDroid does not copy the broader GPL Termux app.

## Eclipse JGit

- Project: `eclipse-jgit/jgit`.
- Version: `5.13.5.202508271544-r`.
- License: Eclipse Distribution License 1.0 / BSD-3-Clause SPDX classification.
- Usage: embedded local Git backend. It is a JVM component, not a separate Git executable/runtime pack binary.

## Android System WebView

- Platform component supplied by the Android system provider.
- Usage: isolated browser surface behind `core:browser`; AgentDroid does not bundle Chromium and does not expose a JavaScript bridge to the model.

## OkHttp / MockWebServer

- Project: `square/okhttp`, version catalog `4.12.0` for OkHttp and MockWebServer.
- License: Apache-2.0.
- Usage: cloud/research transport, MCP HTTP transport, trusted runtime-pack download transport; MockWebServer is test-only.

## DuckDuckGo Instant Answer API

- Optional external API behind `WebSearchProvider`.
- No DuckDuckGo source code is copied or linked.

## Architecture-only references

- OpenCode (`anomalyco/opencode`, MIT): scoped tool/permission architecture reference only.
- Hermes Agent (`NousResearch/hermes-agent`, MIT): iterative model/tool architecture reference only.
- libgit2 (`libgit2/libgit2`, GPLv2 with linking exception): future backend reference only; not linked.
- LiteRT-LM: evaluated as a possible LocalModelEngine backend but **not integrated or distributed** in the current repository, so it is not a used component.

## Reuse policy

A component is described as bundled/linked only when the repository or produced Android artifacts prove that use. Downloadable packs are identified separately from built-in code, and architecture references are never counted as AgentDroid source languages or executable runtime support.
