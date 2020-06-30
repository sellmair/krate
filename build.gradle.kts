@file:Suppress("PropertyName")

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.dokka.gradle.DokkaTask

val kolor_version: String by project
val result_version: String by project
val pg_driver_version: String by project
val exposed_version:   String by project
val hikari_version:    String by project
val epgx_version:      String by project

plugins {
    maven
    `java-library`
    kotlin("jvm") version "1.3.72"
    id("org.jetbrains.dokka") version "0.10.1"
}

group = "dev.31416"
version = "0.1.0"

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

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.5")

    // Reflectr

    implementation("com.github.blogify-dev", "reflectr", "master-SNAPSHOT")

    // Jackson

    implementation("com.fasterxml.jackson.core", "jackson-core", "2.10.2")
    implementation("com.fasterxml.jackson.core", "jackson-databind", "2.10.2")
    implementation("com.fasterxml.jackson.core", "jackson-annotations", "2.10.2")
    implementation("com.fasterxml.jackson.module", "jackson-module-kotlin", "2.10.2")

    // Database stuff

    implementation("org.postgresql", "postgresql", pg_driver_version)
    implementation("org.jetbrains.exposed", "exposed-core", exposed_version)
    implementation("org.jetbrains.exposed", "exposed-jdbc", exposed_version)
    implementation("com.zaxxer", "HikariCP", hikari_version)
    api("com.github.Benjozork", "exposed-postgres-extensions", epgx_version)

    // Kolor

    implementation("com.andreapivetta.kolor", "kolor", kolor_version)

    // Result

    implementation("com.github.kittinunf.result", "result", result_version)
    implementation("com.github.kittinunf.result", "result-coroutines", result_version)

    // Logback

    implementation("ch.qos.logback:logback-classic:1.2.3")

    // Testing

    testImplementation("org.junit.jupiter", "junit-jupiter-api", "5.5.2")
    testRuntimeOnly("org.junit.jupiter", "junit-jupiter-engine", "5.5.2")
}

artifacts {
    archives(tasks.kotlinSourcesJar)
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

            sourceLink {
                path = "./"

                url = "https://github.com/blogify-dev/krate/blob/master/"

                lineSuffix = "#L"
            }
        }
    }
}
