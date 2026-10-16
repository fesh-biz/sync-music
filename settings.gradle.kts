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

rootProject.name = "sync-music"

include(":shared")
include(":desktopApp")
// The Windows build job does not need the Android module (and may lack the SDK).
if (System.getenv("SYNCMUSIC_SKIP_ANDROID") != "1") {
    include(":androidApp")
}
