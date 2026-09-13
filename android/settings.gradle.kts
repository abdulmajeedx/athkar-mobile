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
include(":designsystem")
include(":domain")
include(":data")
include(":feature-athkar")
include(":feature-prayer-times")
include(":feature-tasbih")
include(":app")
