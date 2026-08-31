plugins {
    kotlin("jvm")
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "com.rafambn"
version = "0.8.0"

kotlin {
    jvmToolchain(11)
}

dependencies {
    api(project(":scribe"))
    api(libs.slf4j.api)
    implementation(libs.classgraph)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
}

mavenPublishing {
    coordinates(
        groupId = group.toString(),
        artifactId = "scribe-slf4j",
        version = version.toString()
    )

    pom {
        name.set("Scribe SLF4J")
        description.set("SLF4J binding for Scribe structured logging.")
        url.set("https://scribe.rafambn.com")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("rafambn")
                name.set("Rafael Mendonca")
                email.set("rafambn@gmail.com")
                url.set("https://rafambn.com")
            }
        }
        scm {
            url.set("https://github.com/rafambn/Scribe")
        }
    }

    publishToMavenCentral(automaticRelease = false)
    signAllPublications()
}
