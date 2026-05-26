plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(project(":testApp:shared"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}
