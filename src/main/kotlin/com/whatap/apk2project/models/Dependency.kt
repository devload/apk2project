package com.whatap.apk2project.models

data class Dependency(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val confidence: Float = 1.0f,
    val source: DetectionSource = DetectionSource.MANUAL,
    val scope: DependencyScope = DependencyScope.IMPLEMENTATION
) {
    fun toGradleNotation(): String = "$groupId:$artifactId:$version"

    fun toShortNotation(): String = "$groupId:$artifactId"

    companion object {
        fun parse(notation: String): Dependency? {
            val parts = notation.split(":")
            return when (parts.size) {
                3 -> Dependency(parts[0], parts[1], parts[2])
                2 -> Dependency(parts[0], parts[1], "latest.release")
                else -> null
            }
        }
    }
}

enum class DetectionSource {
    PACKAGE_PREFIX,     // Detected by package name prefix
    BYTECODE_SIGNATURE, // Detected by class/method signatures
    MAVEN_CENTRAL,      // Resolved via Maven Central API
    MANIFEST,           // Found in AndroidManifest
    RESOURCES,          // Found in resources (e.g., google-services)
    MANUAL              // Manually specified
}

enum class DependencyScope {
    IMPLEMENTATION,
    API,
    COMPILE_ONLY,
    RUNTIME_ONLY,
    TEST_IMPLEMENTATION,
    ANDROID_TEST_IMPLEMENTATION
}

data class DetectedLibrary(
    val name: String,
    val dependency: Dependency,
    val confidence: Float,
    val matchedSignatures: Int,
    val totalSignatures: Int
)

data class LibrarySignature(
    val name: String,
    val groupId: String,
    val artifactId: String,
    val packagePrefix: String,
    val classSignatures: List<String>,
    val versionDetection: VersionDetection?
)

data class VersionDetection(
    val className: String,
    val fieldName: String,
    val type: String
)

data class MavenArtifact(
    val groupId: String,
    val artifactId: String,
    val latestVersion: String,
    val versions: List<String> = emptyList()
) {
    fun toDependency(confidence: Float = 0.95f): Dependency =
        Dependency(groupId, artifactId, latestVersion, confidence, DetectionSource.MAVEN_CENTRAL)
}
