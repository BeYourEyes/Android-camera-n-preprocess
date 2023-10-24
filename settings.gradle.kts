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

rootProject.name = "Camera"
include(":app")

include(":opencv")
project(":opencv").projectDir = File("D:\\opencv-4.8.1-android-sdk\\OpenCV-android-sdk\\sdk")