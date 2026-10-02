// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        // The Android Gradle Plugin compiles Kotlin with the Kotlin Gradle plugin it depends on,
        // which can be older than the newest Kotlin; Gradle uses the newer of the two.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
