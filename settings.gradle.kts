pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "AgentDroid"
include(":app", ":core:model", ":core:ai", ":core:agent", ":core:permissions", ":core:workspace", ":data:database")
