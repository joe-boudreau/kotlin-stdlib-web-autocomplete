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
    implementation("org.ow2.asm:asm:9.10.1")
    stdlibSources("org.jetbrains.kotlin:kotlin-stdlib:2.3.21:sources")
}

tasks.named<JavaExec>("run") {
    // Default single-version run: parse the stdlib on the build's own classpath.
    val stdlibBinary = configurations.runtimeClasspath.get()
        .resolvedConfiguration.resolvedArtifacts
        .first { it.moduleVersion.id.name == "kotlin-stdlib" }.file
    args(stdlibBinary.absolutePath, "methods.json", stdlibSources.singleFile.absolutePath)
}

tasks.register<Copy>("copyToFrontend") {
    dependsOn("run")
    from("methods.json")
    into("frontend")
}

// ── Multi-version generation ────────────────────────────────
// Latest patch of each minor from 1.8 through 2.3. First entry is the default.
val stdlibVersions = listOf("2.3.21", "2.2.20", "2.1.21", "2.0.21", "1.9.25", "1.8.22")

val generateAll = tasks.register("generateAll") {
    group = "build"
    description = "Generate frontend/data/methods-<version>.json for every configured Kotlin version"
    doLast {
        val manifest = stdlibVersions.joinToString(
            prefix = "{\"default\":\"${stdlibVersions.first()}\",\"versions\":[",
            postfix = "]}",
            separator = ",",
        ) { "\"$it\"" }
        file("frontend/data/versions.json").apply { parentFile.mkdirs() }.writeText(manifest)
    }
}

fun detached(notation: String) = configurations.detachedConfiguration(
    dependencies.create(notation),
).apply { isTransitive = false }

stdlibVersions.forEach { v ->
    val binaryConfig = detached("org.jetbrains.kotlin:kotlin-stdlib:$v")
    val sourcesConfig = detached("org.jetbrains.kotlin:kotlin-stdlib:$v:sources")
    // Pre-2.0 stdlibs keep generated extension sources (_Collections.kt, _Strings.kt, …)
    // in kotlin-stdlib-common; 2.x merged them into the main sources jar.
    val commonSourcesConfig = detached("org.jetbrains.kotlin:kotlin-stdlib-common:$v:sources")

    val gen = tasks.register<JavaExec>("generate_${v.replace('.', '_')}") {
        group = "build"
        description = "Generate methods-$v.json"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("MainKt")
        argumentProviders.add {
            val sources = buildList {
                add(sourcesConfig.singleFile.absolutePath)
                runCatching { commonSourcesConfig.singleFile.absolutePath }.getOrNull()?.let { add(it) }
            }
            listOf(binaryConfig.singleFile.absolutePath, "frontend/data/methods-$v.json.gz") + sources
        }
    }
    generateAll.configure { dependsOn(gen) }
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}