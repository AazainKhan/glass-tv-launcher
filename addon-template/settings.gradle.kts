// A standalone project: NOT part of Glass's own build. It reaches the add-on API by path (see README.md).
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
    // Plugin and library versions are shared with the add-on API's own build. If you copy the template out of the Glass repo,
    // copy gradle/libs.versions.toml next to it and change this path.
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "my-glass-addon"
include(":app")

// The add-on API (no Maven artifact yet). Path dependency; when you copy the template out, copy the addon-api folder too and fix this path.
include(":addon-api")
project(":addon-api").projectDir = file("../addon-api")
