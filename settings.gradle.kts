pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Anu"

// Modules are added as they are built. The full map is in docs/ARCHITECTURE.md §4.1.
include(":app")
include(":core:model")
include(":core:router")
include(":core:agent")
include(":core:security")
include(":core:vault")
include(":core:data")
include(":brain:litertlm")
include(":brain:gemini")
include(":feature:home")
include(":feature:settings")
