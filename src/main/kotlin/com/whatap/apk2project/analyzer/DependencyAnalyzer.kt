package com.whatap.apk2project.analyzer

import com.whatap.apk2project.models.*
import com.whatap.apk2project.utils.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import kotlin.system.measureTimeMillis

class DependencyAnalyzer(
    private val libraryDatabase: LibraryDatabase = LibraryDatabase(),
    private val packageScanner: PackageScanner = PackageScanner(),
    private val mavenResolver: MavenResolver = MavenResolver(),
    private val obfuscationDetector: ObfuscationDetector = ObfuscationDetector()
) {

    suspend fun analyze(sourceDir: File): AnalysisResult {
        Logger.info("Analyzing dependencies in ${sourceDir.absolutePath}")

        val dependencies = mutableMapOf<String, Dependency>()
        var durationMs: Long

        try {
            durationMs = measureTimeMillis {
                // Step 1: Scan all source files for packages and imports
                Logger.step("Scanning source files...")
                val scanResult = packageScanner.scan(sourceDir)
                Logger.debug("Found ${scanResult.packages.size} packages, ${scanResult.imports.size} imports")

                // Step 2: Categorize packages
                val categories = packageScanner.categorizePackages(scanResult.packages)

                // Step 3: Detect obfuscation
                Logger.step("Detecting obfuscation...")
                val obfuscationReport = obfuscationDetector.detect(sourceDir)
                if (obfuscationReport.isObfuscated) {
                    Logger.warn("Obfuscation detected: ${obfuscationReport.level}")
                    obfuscationReport.details.forEach { Logger.debug("  - $it") }
                }

                // Step 4: Match AndroidX libraries
                Logger.step("Matching AndroidX libraries...")
                matchAndroidXLibraries(categories.androidx, dependencies)

                // Step 5: Match Google Services
                Logger.step("Matching Google Services...")
                matchGoogleServices(categories.googleServices, dependencies)

                // Step 6: Match third-party libraries
                Logger.step("Matching third-party libraries...")
                matchThirdPartyLibraries(categories.thirdParty, dependencies)
                matchThirdPartyLibraries(scanResult.imports, dependencies)

                // Step 7: Query Maven Central for unknown packages (limited)
                Logger.step("Querying Maven Central for unknown packages...")
                val unknownPackages = findUnknownPackages(scanResult.imports, dependencies)
                queryMavenCentral(unknownPackages.take(15), dependencies)

                // Step 8: Add core dependencies if AndroidX is used
                if (categories.androidx.isNotEmpty()) {
                    addCoreDependencies(dependencies)
                }

                Logger.success("Detected ${dependencies.size} dependencies")
            }

            val stats = AnalysisStats(
                scannedPackages = packageScanner.scan(sourceDir).packages.size,
                detectedLibraries = dependencies.size,
                highConfidenceLibraries = dependencies.values.count { it.confidence >= 0.8f },
                unknownPackages = 0,
                isObfuscated = obfuscationDetector.detect(sourceDir).isObfuscated,
                obfuscationLevel = obfuscationDetector.detect(sourceDir).level,
                durationMs = durationMs
            )

            return AnalysisResult.Success(
                dependencies = dependencies.values.toList().sortedByDescending { it.confidence },
                stats = stats
            )

        } catch (e: Exception) {
            Logger.error("Analysis failed: ${e.message}")
            return AnalysisResult.Failure(
                error = e.message ?: "Unknown error",
                cause = e
            )
        }
    }

    private fun matchAndroidXLibraries(
        packages: Set<String>,
        dependencies: MutableMap<String, Dependency>
    ) {
        val androidxMappings = mapOf(
            "androidx.core" to Dependency("androidx.core", "core-ktx", "1.12.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.appcompat" to Dependency("androidx.appcompat", "appcompat", "1.6.1", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.activity" to Dependency("androidx.activity", "activity-ktx", "1.8.2", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.fragment" to Dependency("androidx.fragment", "fragment-ktx", "1.6.2", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.lifecycle" to Dependency("androidx.lifecycle", "lifecycle-runtime-ktx", "2.7.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.constraintlayout" to Dependency("androidx.constraintlayout", "constraintlayout", "2.1.4", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.recyclerview" to Dependency("androidx.recyclerview", "recyclerview", "1.3.2", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.viewpager2" to Dependency("androidx.viewpager2", "viewpager2", "1.0.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.navigation" to Dependency("androidx.navigation", "navigation-fragment-ktx", "2.7.6", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.room" to Dependency("androidx.room", "room-runtime", "2.6.1", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.work" to Dependency("androidx.work", "work-runtime-ktx", "2.9.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.camera" to Dependency("androidx.camera", "camera-core", "1.3.1", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.biometric" to Dependency("androidx.biometric", "biometric", "1.1.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.security" to Dependency("androidx.security", "security-crypto", "1.1.0-alpha06", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.datastore" to Dependency("androidx.datastore", "datastore-preferences", "1.0.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.paging" to Dependency("androidx.paging", "paging-runtime", "3.2.1", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.swiperefreshlayout" to Dependency("androidx.swiperefreshlayout", "swiperefreshlayout", "1.1.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.cardview" to Dependency("androidx.cardview", "cardview", "1.0.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.compose" to Dependency("androidx.compose.ui", "ui", "1.5.4", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "androidx.media3" to Dependency("androidx.media3", "media3-exoplayer", "1.2.1", 0.95f, DetectionSource.PACKAGE_PREFIX)
        )

        for (pkg in packages) {
            for ((prefix, dep) in androidxMappings) {
                if (pkg.startsWith(prefix)) {
                    dependencies[dep.toShortNotation()] = dep
                    break
                }
            }
        }
    }

    private fun matchGoogleServices(
        packages: Set<String>,
        dependencies: MutableMap<String, Dependency>
    ) {
        val googleMappings = mapOf(
            "com.google.android.gms.auth" to Dependency("com.google.android.gms", "play-services-auth", "20.7.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "com.google.android.gms.location" to Dependency("com.google.android.gms", "play-services-location", "21.1.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "com.google.android.gms.maps" to Dependency("com.google.android.gms", "play-services-maps", "18.2.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "com.google.android.gms" to Dependency("com.google.android.gms", "play-services-base", "18.3.0", 0.90f, DetectionSource.PACKAGE_PREFIX),
            "com.google.firebase.analytics" to Dependency("com.google.firebase", "firebase-analytics", "21.5.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "com.google.firebase.crashlytics" to Dependency("com.google.firebase", "firebase-crashlytics", "18.6.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "com.google.firebase.messaging" to Dependency("com.google.firebase", "firebase-messaging", "23.4.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "com.google.firebase.auth" to Dependency("com.google.firebase", "firebase-auth", "22.3.0", 0.95f, DetectionSource.PACKAGE_PREFIX),
            "com.google.firebase" to Dependency("com.google.firebase", "firebase-common", "20.4.2", 0.90f, DetectionSource.PACKAGE_PREFIX)
        )

        for (pkg in packages) {
            for ((prefix, dep) in googleMappings) {
                if (pkg.startsWith(prefix)) {
                    dependencies[dep.toShortNotation()] = dep
                    break
                }
            }
        }
    }

    private fun matchThirdPartyLibraries(
        packages: Set<String>,
        dependencies: MutableMap<String, Dependency>
    ) {
        for (pkg in packages) {
            val entry = libraryDatabase.findByPackagePrefix(pkg)
            if (entry != null) {
                val dep = libraryDatabase.toDependency(entry)
                dependencies[dep.toShortNotation()] = dep
            }
        }
    }

    private fun findUnknownPackages(
        imports: Set<String>,
        knownDeps: Map<String, Dependency>
    ): List<String> {
        val knownPrefixes = knownDeps.values.map { it.groupId }.toSet() +
            setOf("android.", "java.", "javax.", "kotlin.", "kotlinx.")

        return imports.filter { import ->
            knownPrefixes.none { prefix -> import.startsWith(prefix) }
        }.map { import ->
            // Extract likely class name for Maven search
            import.substringAfterLast(".")
        }.distinct()
    }

    private suspend fun queryMavenCentral(
        classNames: List<String>,
        dependencies: MutableMap<String, Dependency>
    ) = coroutineScope {
        val results = classNames.map { className ->
            async {
                mavenResolver.searchByClassName(className)
            }
        }.awaitAll()

        results.filterNotNull().forEach { artifact ->
            val dep = artifact.toDependency(0.75f)
            dependencies[dep.toShortNotation()] = dep
        }
    }

    private fun addCoreDependencies(dependencies: MutableMap<String, Dependency>) {
        // Ensure Material Components if AppCompat is used
        if (dependencies.any { it.key.contains("appcompat") }) {
            val material = Dependency(
                "com.google.android.material", "material", "1.11.0",
                0.9f, DetectionSource.PACKAGE_PREFIX
            )
            dependencies.putIfAbsent(material.toShortNotation(), material)
        }
    }
}
