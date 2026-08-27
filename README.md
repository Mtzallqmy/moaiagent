# AgentDroid

Native Kotlin/Jetpack Compose application for a multi-provider, tool-using AI agent. The project keeps provider, agent, permissions, workspace, runtime, terminal, Git, persistence, and UI concerns in separate modules. Secrets are encrypted with Android Keystore and only aliases are persisted in provider records.

## Build

Set `ANDROID_SDK_ROOT` and run `./gradlew clean assembleDebug test lintDebug`. The release variant enables R8/resource shrinking.

## Scope

The current Phase 3 branch preserves the Phase 1 provider/chat foundation and Phase 2 agent/workspace tools, and adds a real Termux-backed PTY terminal, bounded foreground/background processes, command permissions and redaction, runtime discovery, and a JGit-backed workspace Git workflow. Browser automation, web research, subagents, and local LLM runtimes remain intentionally outside Phase 3.
