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

rootProject.name = "wBooks"
include(":app")
include(":companion")
// Standalone Wear OS tile + drawer shortcut that opens Developer options. Lives
// here only to share this project's gradle wrapper / version catalog; it has
// its own applicationId and ships as a separate APK.
include(":devtools")
