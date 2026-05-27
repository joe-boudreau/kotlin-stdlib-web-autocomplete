plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

application {
    mainClass.set("MainKt")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val stdlibSources by configurations.creating {
    isTransitive = false
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    stdlibSources("org.jetbrains.kotlin:kotlin-stdlib:2.3.21:sources")
}

tasks.named<JavaExec>("run") {
    environment("STDLIB_SOURCES_JAR", stdlibSources.singleFile.absolutePath)
}

tasks.register<Copy>("copyToFrontend") {
    dependsOn("run")
    from("methods.json")
    into("frontend")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}