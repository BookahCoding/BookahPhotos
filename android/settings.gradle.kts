rootProject.name = "bookahphotos"

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

include(":app")
include(":core-data")
include(":core-search")
include(":design")
include(":feature-library")
include(":feature-viewer")
include(":feature-collections")
include(":feature-search")
include(":feature-map")
