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
        // Mappls SDK artifacts and the Mappls services Gradle plugin (auth since Aug 2025).
        maven(url = "https://maven.mappls.com/repository/mappls/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mappls Maps SDK (com.mappls.sdk:*). Public read access; runtime auth comes from the
        // .a.olf/.a.conf files processed by the Mappls services plugin (see app/build.gradle.kts).
        maven(url = "https://maven.mappls.com/repository/mappls/")
    }
}

rootProject.name = "HunterNav"
include(":app")
