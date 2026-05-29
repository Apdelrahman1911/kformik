pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kformik"

include(":kformik")
include(":kformik-compose")
include(":kformik-ksp")
include(":examples")
project(":examples").projectDir = file("examples")
include(":sample-android-app")
