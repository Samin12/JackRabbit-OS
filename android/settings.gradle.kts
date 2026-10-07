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

rootProject.name = "SamR1"
include(
    ":app",
    ":core:design",
    ":core:input",
    ":core:power",
    ":core:motor",
    ":feature:settings",
    ":feature:voice",
    ":feature:cards",
    ":feature:calendar",
    ":feature:tasks",
    ":feature:camera",
    ":feature:creation-import",
    ":feature:background-run",
    ":feature:t3",
    ":runtime-host",
    ":system:motor-service",
)
