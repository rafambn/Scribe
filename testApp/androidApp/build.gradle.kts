plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "scribe.demo.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "scribe.demo.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}
