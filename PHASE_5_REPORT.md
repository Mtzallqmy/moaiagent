# Phase 5 Report — Local AI, Runtime Packs, MCP, and Model-Driven Planning

## Status

Phase 5 implementation and runtime/build verification are complete on `phase5-local-ai-runtimes-mcp-release`.

The final publication PR and merge to `main` are still pending at the time of this branch report update.

**Ready for Phase 6: NO — publication/merge still pending.**

## Implemented

- Model-driven planning with structured, bounded, capability-validated plans.
- Repair-once handling for malformed or invalid planner output plus deterministic goal-aware fallback.
- Production Plan/Agent integration through `ModelPlanningCoordinator` and `TaskEngine.createFromPlan`.
- Verified runtime capability discovery that distinguishes detection from actual Agent-executable evidence.
- Runtime Pack manager with trusted-source installation, checksums, enable/disable state, execution verification, and UI.
- Embedded Python 3.13 integration with Agent tools for version inspection, code execution, and package installation.
- Local-model management and a native llama.cpp JNI bridge with Android ABI packaging.
- MCP HTTP client/contracts/adapters plus application management UI and secure credential storage integration.
- Storage/runtime/model/MCP system screens and Phase 5 Android acceptance coverage.

## Planner and capability policy

`ModelDrivenPlanner` receives the user goal plus bounded context, memory, skills, workspace summary, and only verified capabilities. Retrieved/model-generated data is treated as untrusted and cannot create capabilities that AgentDroid has not verified.

Structured plans contain stable step IDs, user-visible goals, dependencies, expected capabilities, and observable acceptance criteria. Plans are schema-validated, capability-checked, cycle-checked, bounded, and topologically ordered before task creation.

Runtime discovery alone is inventory only. A runtime capability is advertised to the planner only when the product's verification policy permits it.

## Final first-party source language inventory

Phase 5 Verification run #38 generated the final first-party inventory on commit `c25361c31cc5869edb8b48a858f77f2cb38e84bd`:

- Kotlin: **135 files**
- C++: **1 file** (`core/localai/src/main/cpp/llama_jni.cpp`)
- Python: **1 file** (`app/src/main/python/agentdroid_runtime.py`)

No first-party Rust, Go, JavaScript/TypeScript, or Lua source was present in the final inventory.

This classification applies to AgentDroid's first-party source tree only; third-party dependency implementation languages are not counted as AgentDroid source languages.

## Runtime execution classification

### Android shell

**VERIFIED Agent-executable.**

API 35 x86_64 emulator instrumentation verifies shell discovery and a bounded command execution through `DefaultProcessRunner`, and requires `RuntimeEvidenceKind.EXECUTION_PROBE_PASSED` before `runtime.shell` is advertised.

### Embedded Python 3.13

**VERIFIED Agent-executable.**

Android instrumentation executes `print('AGENTDROID_PYTHON_OK')` through AgentDroid's embedded Python runtime, verifies Python 3.13, verifies exit code `0`, verifies the expected output, verifies the registered `python_version`, `python_run`, and `python_install_package` Agent tools, and verifies the Python Runtime Pack as `agentExecutable`.

### Node.js

**NOT Agent-executable in Phase 5.**

The official Node.js Mobile Android pack is independently inspected in CI and contains Android libraries for `arm64-v8a` and `x86_64`. However, installing `libnode.so` is not equivalent to a `node::Start` execution bridge. `AppRuntimePackController` therefore intentionally returns `false` for Node execution verification and does not advertise Node as an Agent-executable runtime.

### Git

Git is an **embedded application component** through JGit and the existing Agent Git tooling. It is not classified as a language runtime discovered from the Android host.

### Rust and Go

**NOT Agent runtimes.** Host/toolchain detection does not grant `runtime.rust` or `runtime.go`, and Android acceptance explicitly requires them not to be advertised without a real AgentDroid subsystem.

### Lua

**NOT implemented as a runtime/plugin sandbox in Phase 5.**

## Native/local AI evidence

The final first-party inventory contains `core/localai/src/main/cpp/llama_jni.cpp`, and the debug APK native-library inventory contains:

- `lib/arm64-v8a/libagentdroid_llama.so`
- `lib/x86_64/libagentdroid_llama.so`

This verifies that AgentDroid's native llama.cpp JNI bridge is first-party integrated and packaged for both configured Android ABIs.

The APK also contains Python/Chaquopy, C++ runtime, Termux, crypto/SSL/SQLite, AndroidX graphics, and related third-party native libraries. Their presence does not make those dependencies first-party AgentDroid implementation languages or planner capabilities.

Phase 5 CI verifies the native bridge packaging/build path; it does not claim a full performance/quality benchmark of inference with every GGUF model/device combination.

## MCP status

The `core:mcp` module contains the MCP contracts, HTTP client, and Agent adapters, with application-level server configuration/connection UI. MCP unit coverage is included in the successful full unit-test suite.

Phase 5 does not claim that every external MCP server or authentication scheme has been live-tested in CI; interoperability remains bounded by the implemented protocol/transport behavior and the configured server.

## Build artifacts

Phase 5 Verification run #38 records:

- commit: `c25361c31cc5869edb8b48a858f77f2cb38e84bd`
- branch: `phase5-local-ai-runtimes-mcp-release`
- debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`
- release AAB: `app/build/outputs/bundle/release/app-release.aab`
- CI signing: **not configured in CI**

The unsigned release APK/AAB build successfully; production signing is intentionally not claimed by this CI evidence.

## CI and Android verification

Phase 5 Verification run #38 (`33184399469`) completed successfully on commit `c25361c31cc5869edb8b48a858f77f2cb38e84bd`.

Successful gates:

- official Node.js Mobile Android asset inspection and evidence upload;
- clean;
- planner/runtime targeted unit tests;
- full unit tests;
- lint;
- Android UI test compilation;
- debug APK assembly;
- release APK assembly;
- release AAB assembly;
- first-party source-language inventory;
- APK native-library inventory;
- final build-evidence artifact upload;
- API 35 x86_64 Android emulator instrumentation.

The emulator acceptance includes the Phase 5 runtime-evidence tests and passes successfully.

## Persistence

Room remains on schema version 4. The full validated planner DAG exists at the planning boundary and is topologically converted into the current ordered execution `TaskPlan`. Phase 5 does not add a speculative migration solely to duplicate graph metadata before a future scheduler needs durable branch-level execution state.

## Remaining publication gate

1. Run the Phase 5 workflow on this final documentation SHA.
2. Open the Phase 5 pull request to `main`.
3. Require all PR-triggered checks to be green.
4. Merge normally without force-pushing.
5. Record the final PR and merge commit on `main`, then set `Ready for Phase 6: YES` only after the merge is actually complete.
