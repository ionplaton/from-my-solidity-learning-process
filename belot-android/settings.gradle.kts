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
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "belot"

include(":core")

// `-PcoreOnly` builds and tests only the platform-independent game core (no Android SDK needed).
val coreOnly = providers.gradleProperty("coreOnly").isPresent
if (coreOnly) {
    rootProject.buildFileName = "build-core.gradle.kts"
} else {
    include(":app")
}
