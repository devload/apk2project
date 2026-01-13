plugins {
    java
}

group = "com.test"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // JUnit for testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")

    // SQLite JDBC (for DatabaseHelper simulation)
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")

    // Android dependencies stub (for compilation only)
    compileOnly("com.google.android:android:4.1.1.4")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}
