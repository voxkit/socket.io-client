plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    explicitApi()

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.coroutines.core)
            implementation(libs.ktor.client.core)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        commonTest.dependencies {
        }
    }
}
