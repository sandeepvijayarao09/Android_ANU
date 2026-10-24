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
include(":agent:adk")
include(":core:security")
include(":core:vault")
include(":core:data")
include(":core:memory")
include(":core:voice")
include(":core:ui")
include(":brain:litertlm")
include(":brain:gemini")
include(":brain:ollama")
include(":brain:embedding")
include(":brain:nano")
include(":connector:calendar")
include(":connector:contacts")
include(":connector:gmail")
include(":connector:messages")
include(":connector:sms")
include(":connector:mcp")
include(":connector:shopping")
include(":connector:phone")
include(":connector:notes")
include(":connector:apps")
include(":connector:a2a")
include(":feature:home")
include(":feature:settings")
include(":feature:memory")
include(":feature:writing")
include(":feature:keyboard")
include(":eval")
include(":demo:shop")
