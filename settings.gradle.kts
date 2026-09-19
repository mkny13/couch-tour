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
        // NewPipeExtractor (#232) is distributed on JitPack, not Maven Central. Scoped
        // to com.github so nothing else can silently resolve from it.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "CouchTour"
include(":app")
