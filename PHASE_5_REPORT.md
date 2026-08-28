# Phase 5 Report — Model-Driven Planner

## Status

Phase 5 implementation is present on `phase5-model-driven-planner`, but final CI, Android runtime evidence, artifact inventory, PR review, and merge are still required.

**Ready for Phase 6: NO**

## Implemented

- `ModelDrivenPlanner` accepts goal, project memory, skills, verified capabilities, and optional workspace summary.
- Structured steps contain title, goal, dependencies, expected capabilities, and acceptance criteria.
- Candidate plans are bounded, schema-decoded, capability-validated, cycle-checked, and topologically ordered.
- One bounded model repair attempt handles malformed or invalid structured output.
- A deterministic, goal-aware fallback remains available when model planning fails.
- `ModelPlanningCoordinator` creates/reuses a validated task before the production Plan/Agent loop.
- Tool capabilities come from the actual mode-filtered `ToolRegistry`.
- Runtime language capabilities come only from `RuntimeVerifier` execution evidence, never from detection alone.
- `TaskEngine.createFromPlan` persists an already validated execution plan without moving provider/runtime concerns into the task state machine.

## Runtime evidence policy

`RuntimeDiscovery` is inventory only. `RuntimeVerifier` distinguishes `NOT_DETECTED`, `DETECTED_ONLY`, `EXECUTION_PROBE_PASSED`, and `EMBEDDED_COMPONENT`.

A language is not reported as an Agent-executable runtime unless bounded code execution succeeds through AgentDroid's `ProcessRunner` in the Android application environment. CI-host tools do not count as Android runtime support.

## Language classification

Final values are intentionally pending until the Phase 5 CI artifact inventory and Android instrumentation run complete.

### 1. AgentDroid first-party source languages

**Pending final source-tree inventory artifact.**

Kotlin/Java are known first-party Android sources. C/C++, Rust, Go, Python, Node/JavaScript/TypeScript, and Lua will not be claimed as first-party implementation languages unless the final source inventory proves first-party source for an actually integrated subsystem.

### 2. Languages/runtimes AgentDroid can execute

**Pending Android instrumentation evidence.**

- Android shell: must pass a real execution probe before it is reported supported.
- Python: reported only if the Android `python3 -c` probe passes.
- Node.js: reported only if the Android `node -e` probe passes.
- Rust/Go: detection of `rustc`/`go` is toolchain inventory only; neither becomes an Agent runtime capability without a real AgentDroid subsystem.
- Lua: not claimed without a real plugin/skill sandbox.

### 3. Languages present only in external dependencies/build artifacts

**Pending APK native-library inventory and dependency evidence.**

Native `.so` files in the APK may originate from Android/third-party dependencies. Their implementation language is not automatically an AgentDroid first-party language or an Agent-executable runtime.

## Native/local AI status

- llama.cpp / native inference backend: **not claimed** unless C/C++ source/NDK-JNI integration and corresponding APK native artifacts are verified.
- Rust native/security subsystem: **not claimed** unless first-party Rust source and product linkage are verified.
- Go service/CLI/runtime component: **not claimed** unless first-party Go source and actual product use are verified.
- Lua plugin/skill sandbox: **not claimed** unless executable sandbox behavior is verified.
- MCP: no Phase 5 claim is made merely from plans or dependency names; an implementation must be present and exercised before it is reported supported.

## Tests added

- calculator goal and Android foreground-service research goal produce materially different model plans;
- cyclic plans are rejected and repaired;
- malformed structured JSON is repaired;
- unadvertised capabilities are rejected and repaired;
- plan size is bounded;
- model/provider failure uses deterministic goal-aware fallback;
- detected Python does not become a capability when its execution probe fails;
- Python/Node capabilities require successful execution probes;
- Rust/Go detection does not become runtime support;
- embedded Git is classified as an application component;
- Android instrumentation verifies runtime evidence through `DefaultProcessRunner` in the emulator/application environment.

## Persistence

Room schema remains version 4. The full validated planner DAG exists at the planning boundary and is topologically converted into the current ordered execution `TaskPlan`. Phase 5 does not add a speculative database migration solely to duplicate graph metadata before a graph scheduler needs durable branch-level scheduling.

## CI/build evidence

A dedicated `Phase 5 Verification` workflow runs:

- clean;
- planner/runtime narrow tests;
- full unit tests;
- lint;
- Android UI test compilation;
- debug/release assembly;
- first-party source language inventory;
- APK native-library inventory;
- evidence artifact upload;
- API 35 x86_64 emulator instrumentation.

Final run ID, commit SHA, inventory values, APK evidence, and instrumentation result are pending.

## Remaining before completion

1. Obtain one complete green Phase 5 workflow on the final code/documentation SHA.
2. Inspect the generated source-language and APK-native-library evidence artifacts.
3. Inspect Android instrumentation evidence for shell/Python/Node/Rust/Go classification.
4. Replace all pending language classification text above with observed facts only.
5. Review the complete diff, open the Phase 5 PR, and merge only with green checks.
6. After the merge is actually complete, update this report to record the merged commit and set `Ready for Phase 6: YES` only if no completion gate remains.
