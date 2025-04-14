import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
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
        commonMain.dependencies { }

        val jvmAndAndroid by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.sl4j.api)
            }
        }

        jvmMain {
            dependsOn(jvmAndAndroid)
        }

        androidMain {
            dependsOn(jvmAndAndroid)
            dependencies {
                implementation(libs.sl4j.android)
            }
        }
    }
}

android {
    namespace = "io.voxkit.socketio.logging"

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
