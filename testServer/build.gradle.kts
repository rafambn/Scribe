plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(project(":scribe-slf4j"))
}

application {
    mainClass = "com.rafambn.scribe.testserver.MainKt"
}
