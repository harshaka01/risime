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
        // livekit-android 2.29.0 (decision 057) depends on one JitPack artifact (its audioswitch
        // fork, pinned to a commit). Only that group may come from JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.davidliu") }
        }
    }
}
rootProject.name = "RisiMe"
include(":app")
