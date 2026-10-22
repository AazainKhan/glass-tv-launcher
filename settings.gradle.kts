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

rootProject.name = "glass-tv-launcher"
include(":app")
include(":baselineprofile")
include(":browser")
include(":addon-api")
include(":addon-sample")
include(":p239spike") // P239 spike, not for merge
