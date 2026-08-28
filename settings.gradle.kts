pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "AgentDroid"
include(
    ":app",
    ":core:model",
    ":core:ai",
    ":core:agent",
    ":core:permissions",
    ":core:workspace",
    ":core:runtime",
    ":core:terminal",
    ":core:git",
    ":core:browser",
    ":core:tasks",
    ":core:research",
    ":core:artifacts",
    ":core:subagents",
    ":core:localai",
    ":data:database"
)
