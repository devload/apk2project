plugins {
    kotlin("jvm") version "1.9.21"
    application
}

group = "com.whatap"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // CLI Framework
    implementation("com.github.ajalt.clikt:clikt:4.2.1")

    // Template Engine
    implementation("org.freemarker:freemarker:2.3.32")

    // JSON Processing
    implementation("com.google.code.gson:gson:2.10.1")

    // Bytecode Analysis (ASM)
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
    implementation("org.ow2.asm:asm-util:9.6")

    // HTTP Client for Maven Central API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // XML Parsing
    implementation("org.jdom:jdom2:2.0.6.1")

    // Archive handling
    implementation("org.apache.commons:commons-compress:1.25.0")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // AST Parsing for Deobfuscation
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.25.8")

    // Graph Processing for Call Graph
    implementation("org.jgrapht:jgrapht-core:1.5.2")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.mockk:mockk:1.13.8")
}

application {
    mainClass.set("com.whatap.apk2project.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.whatap.apk2project.MainKt"
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "com.whatap.apk2project.MainKt"
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}
