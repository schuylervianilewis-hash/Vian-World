// AGP 9 bundles KGP 2.2.10 by default. Override the built-in toolchain
// explicitly so Kotlin 2.4.21 can consume Firewall's Kotlin 2.4 metadata.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        // Resolve Compose compiler from its published implementation module, avoiding
        // reliance on the separate Gradle Plugin Portal marker during CI builds.
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
}

// Shared Android plugins for the Vian World host and launcher library.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
}
