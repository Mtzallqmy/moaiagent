# AgentDroid

Native Kotlin/Jetpack Compose foundation for a multi-provider AI client. The project is intentionally split into `core:model`, `core:ai`, `data:database`, and `app` modules. Secrets are encrypted with Android Keystore and only aliases are persisted in provider records.

## Build

Set `ANDROID_SDK_ROOT` and run `./gradlew clean assembleDebug test lintDebug`. The release variant enables R8/resource shrinking.

## Scope

This is Phase 1: local persistence, provider abstraction and HTTP integrations, streaming state, Compose screens, RTL-ready resources, and test seams. Terminal, browser, Git, files, and autonomous agent loops are deliberately excluded.
