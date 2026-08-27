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

rootProject.name = "athkar"

include(":core")
include(":domain")
include(":data")
include(":sync")
include(":feature-athkar")
include(":feature-prayer-times")
include(":app")
