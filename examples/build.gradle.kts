
plugins {
    kotlin("jvm")
    alias(libs.plugins.atomicfu)
    alias(libs.plugins.ktlint)
}

group = "io.voxkit.socketio.examples"
version = "0.1.0"

dependencies {
    implementation(project(":socketio-client"))
    implementation(libs.logback.classic)
}
