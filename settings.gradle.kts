pluginManagement {
    repositories {
        google()
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

rootProject.name = "RookieOnQuest"
include(":app")

// Gradle Build Cache Configuration
//
// SCOPE CLARIFICATION:
// 1. Local Build Cache: Persists task outputs in ~/.gradle/caches/build-cache-1.
// 2. Cross-Run Caching: GitHub Actions (via actions/cache) persists the ~/.gradle/caches 
//    directory between CI runs. This makes the local build cache effective across 
//    different PR commits and builds.
// 3. Effectiveness: Speeds up tasks like lint and compilation by reusing outputs 
//    when inputs haven't changed.
buildCache {
    local {
        isEnabled = true
    }
}
