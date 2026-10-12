// Top-level build file. Plugin versions are managed in gradle/libs.versions.toml.

// Mappls services Gradle plugin (id: com.mappls.services.android). It is distributed through
// maven.mappls.com WITHOUT a plugin-marker jar, so the `plugins {}` DSL cannot resolve it —
// Mappls' own documentation therefore installs it via the buildscript classpath. The app
// module applies it conditionally (only when the local *.a.olf/*.a.conf auth files exist).
buildscript {
    repositories {
        maven(url = "https://maven.mappls.com/repository/mappls/")
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.mappls.services:mappls-services:1.0.1")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
