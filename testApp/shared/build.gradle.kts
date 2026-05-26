plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm()
    android {
        namespace = "scribe.demo.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(compose.foundation)
            implementation(compose.material3)
            implementation(project(":scribe"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
