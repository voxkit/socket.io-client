import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    explicitApi()

    androidTarget {
        publishAllLibraryVariants()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":logging"))
            api(libs.kotlin.serialization)
            api(libs.kotlin.coroutines.core)
            api(libs.kotlin.datetime)
            api(libs.ktor.client.core)
            api(libs.ktor.client.websockets)
        }

        val jvmAndAndroid by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }

        jvmMain {
            dependsOn(jvmAndAndroid)
        }

        androidMain {
            dependsOn(jvmAndAndroid)
        }

        appleMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlin.coroutines.test)
            implementation(libs.ktor.client.logging)
        }

        jvmTest.dependencies {
            implementation(libs.logback.classic)
        }
    }
}

android {
    namespace = "io.voxkit.engineio"

    compileSdk = libs.versions.compileSdk.get().toInt()
    sourceSets["main"].res.srcDir("src/androidMain/res")

    sourceSets["testDebug"].resources.srcDir("src/commonTest/resources")
    sourceSets["testRelease"].resources.srcDir("src/commonTest/resources")

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
