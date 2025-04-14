plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.atomicfu) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.mavenPublish) apply false
}

val GROUP: String by project

subprojects {
    group = GROUP
    version = System.getenv("VERSION") ?: "0.0.0"
}
