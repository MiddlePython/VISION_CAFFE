pluginManagement {
    repositories {
        maven { url = uri("https://mvn-mirror.gitverse.ru") }
        google()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://mvn-mirror.gitverse.ru") }
        google()
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
}

rootProject.name = "UNIVER"
include(":app")
