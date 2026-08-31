plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(project(":scribe"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass = "com.rafambn.scribe.benchmarks.MainKt"
}
