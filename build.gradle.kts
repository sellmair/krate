@file:Suppress("PropertyName")

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.dokka.gradle.DokkaTask
import java.net.URL

val kolor_version:      String by project
val result_version:     String by project
val pg_driver_version:  String by project
val exposed_version:    String by project
val hikari_version:     String by project
val epgx_version:       String by project
val coroutines_version: String by project
val junit_version:      String by project
val logback_version:    String by project
val jackson_version:    String by project

plugins {
    `maven-publish`
    `java-library`

    kotlin("jvm") version "1.3.72"

    id("org.jetbrains.dokka") version "0.10.1"
}

group = "dev.31416"
version = "0.2.1"

repositories {
    mavenCentral()
    mavenLocal()
    jcenter()
    maven("https://jitpack.io")
    maven { url = uri("https://dl.bintray.com/kittinunf/maven") }
    maven { url = uri("https://kotlin.bintray.com/ktor") }
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))

    implementation("org.jetbrains.kotlinx", "kotlinx-coroutines-core", coroutines_version)

    // Reflectr

    api("com.github.blogify-dev", "reflectr", "ada67bb")

    // Jackson

    implementation("com.fasterxml.jackson.core", "jackson-core", jackson_version)
    implementation("com.fasterxml.jackson.core", "jackson-databind", jackson_version)
    implementation("com.fasterxml.jackson.core", "jackson-annotations", jackson_version)
    implementation("com.fasterxml.jackson.module", "jackson-module-kotlin", jackson_version)

    // Database stuff

    implementation("org.postgresql", "postgresql", pg_driver_version)
    implementation("com.zaxxer", "HikariCP", hikari_version)
    api("org.jetbrains.exposed", "exposed-core", exposed_version)
    api("org.jetbrains.exposed", "exposed-jdbc", exposed_version)
    api("com.github.Benjozork", "exposed-postgres-extensions", epgx_version)

    // Kolor

    implementation("com.andreapivetta.kolor", "kolor", kolor_version)

    // Result

    api("com.github.kittinunf.result", "result", result_version)
    api("com.github.kittinunf.result", "result-coroutines", result_version)

    // Logback

    implementation("ch.qos.logback", "logback-classic", logback_version)

    // Testing

    testImplementation("org.junit.jupiter", "junit-jupiter-api", junit_version)
    testRuntimeOnly("org.junit.jupiter", "junit-jupiter-engine", junit_version)
    testImplementation("org.jetbrains.kotlinx", "kotlinx-coroutines-test", coroutines_version)
}

val sourcesJar by tasks.creating(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.getByName("main").allSource)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
            artifact(sourcesJar)
        }
    }
}

kotlin.sourceSets["main"].kotlin.srcDirs("src")
kotlin.sourceSets["test"].kotlin.srcDirs("test")

tasks {
    withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "1.8"
    }

    withType<Test>().configureEach {
        useJUnitPlatform()

        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val dokka by getting(DokkaTask::class) {
        outputFormat = "jekyll"
        outputDirectory = "docs/"

        configuration {
            skipDeprecated = true

            reportUndocumented = false

            skipEmptyPackages = true

            includes = listOf("docs/krate.md")

            externalDocumentationLink {
                url = URL("https://docs.31416.dev/reflectr/")
            }

            perPackageOption {
                prefix   = "krate.annotations"
                suppress = true
            }

            perPackageOption {
                prefix   = "krate.util"
                suppress = true
            }

            sourceLink {
                path = "./"

                url = "https://github.com/blogify-dev/krate/blob/master/"

                lineSuffix = "#L"
            }
        }
    }
}
