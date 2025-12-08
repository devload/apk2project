package com.whatap.apk2project.generator

import com.whatap.apk2project.models.SourceFile
import com.whatap.apk2project.models.SourceLanguage
import com.whatap.apk2project.utils.FileUtils
import com.whatap.apk2project.utils.Logger
import com.whatap.apk2project.utils.ProgressBar
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SourceOrganizer {

    data class OrganizeResult(
        val sourceFiles: List<SourceFile>,
        val javaCount: Int,
        val kotlinCount: Int,
        val errorCount: Int,
        val skippedLibraries: Int,
        val errors: List<String>
    )

    // Known library package prefixes that should be excluded when we add them as dependencies
    private val libraryPackagePrefixes = setOf(
        // AndroidX - exclude ALL packages (single prefix covers everything)
        "androidx",

        // Google libraries
        "com.google.gson",           // Gson
        "com.google.firebase",       // Firebase
        "com.google.android.gms",    // Google Play Services
        "com.google.android.material", // Material
        "com.google.android.exoplayer", // ExoPlayer
        "com.google.android.datatransport",
        "com.google.android.play",
        "com.google.common",         // Guava
        "com.google.protobuf",       // Protocol Buffers
        "com.google.crypto.tink",    // Tink crypto
        "com.google.errorprone",
        "com.google.j2objc",
        "com.google.mlkit",          // ML Kit
        "com.google.zxing",          // ZXing barcode

        // Square libraries
        "okhttp3", "okhttp",
        "okio",
        "retrofit2", "retrofit",
        "com.squareup.picasso",
        "com.squareup.moshi",
        "com.squareup.okhttp",
        "com.squareup.okio",
        "com.squareup.leakcanary",
        "com.squareup.wire",

        // Image loading
        "com.bumptech.glide",
        "coil",
        "com.github.bumptech.glide",

        // Reactive libraries
        "io.reactivex",
        "io.reactivex.rxjava2",
        "io.reactivex.rxjava3",
        "rx",
        "rxjava",

        // Kotlin
        "kotlin",
        "kotlinx",

        // Dependency Injection
        "dagger",
        "hilt",
        "javax.inject",
        "com.google.dagger",

        // JSON/Serialization
        "com.fasterxml.jackson",
        "org.json",
        "flexjson",

        // Logging
        "timber.log",
        "org.slf4j",
        "ch.qos.logback",

        // Animation
        "com.airbnb.lottie",

        // EventBus
        "org.greenrobot.eventbus",
        "org.greenrobot",

        // ButterKnife
        "butterknife",

        // Apache libraries
        "org.apache.commons",
        "org.apache.http",

        // Security/Crypto
        "org.bouncycastle",
        "org.spongycastle",

        // Excel
        "jxl",
        "org.apache.poi",

        // Networking
        "com.android.volley",

        // ORM
        "io.realm",
        "org.greenrobot.greendao",

        // Testing libraries (shouldn't be in APK but just in case)
        "junit",
        "org.junit",
        "org.mockito",
        "io.mockk",

        // Other common libraries
        "javolution",
        "net.bytebuddy",
        "org.jetbrains.annotations",
        "org.jetbrains",
        "org.intellij",              // JetBrains annotations
        "android.support",           // Legacy support library
        "com.facebook",
        "com.crashlytics",
        "io.fabric",
        "com.amplitude",
        "com.adjust",
        "com.appsflyer",
        "com.kakao",
        "com.nhn",
        "com.navercorp",

        // Additional libraries found in APKs
        "org.krysalis",              // Barcode4j
        "org.reactivestreams",       // Reactive Streams
        "org.mospi",
        "org.apache",                // All Apache (broader catch)
        "cz.msebera",                // Apache HTTP client fork
        "io.netty",                  // Netty
        "io.grpc",                   // gRPC
        "com.squareup",              // All Square libraries
        "com.android.installreferrer",
        "com.android.billingclient",
        "com.ahnlab",                // AhnLab security SDK
        "ai.entropyxx",
        "ai.fairytech",              // Fairytech Moment SDK
        "ai",                        // All AI SDKs
        "firebase",                  // Firebase (without com.google prefix)
        "igj",                       // Obfuscated library
        "net.sourceforge",
        "com.github",                // GitHub libraries

        // Korean financial/telecom SDKs
        "io.fincube",
        "io.lfin",
        "io.whatap",
        "whatap",                    // WhaTap monitoring SDK

        // Obfuscated packages (JADX renamed)
        "sda",                       // Obfuscated SDK
        "ocs",                       // Obfuscated SDK
        "sources",                   // JADX artifact

        // Machine Learning libraries
        "org.tensorflow",            // TensorFlow / TensorFlow Lite
        "com.google.ai",             // Google AI
        "org.pytorch",               // PyTorch Mobile

        // Java desugaring library (j$ packages)
        "j\$",                       // Desugaring library (backported Java 8+ APIs)

        // Android internals that shouldn't be in app code
        "android",                   // Android SDK (provided by system)
        "android.twb",

        // Korean SDK vendors
        "com.wizvera",               // Crypto library
        "com.initech",               // Security/Cert library
        "com.nshc",                  // Security library
        "com.nice",                  // Authentication
        "com.lguplus",               // LG U+ telecom SDK
        "com.igaworks",              // Ads SDK
        "com.enliple",               // Ads SDK
        "com.appinsightor",          // Analytics
        "com.atoncorp",              // Payment SDK
        "com.callgate",              // Telecom SDK
        "com.greencross",            // Healthcare SDK
        "com.interezen",             // Payment SDK
        "com.journeyapps",           // ZXing Barcode
        "com.ksmartech",             // Marketing SDK
        "com.mastercard",            // Payment SDK
        "com.netfunnel",             // Traffic control
        "com.nethru",                // Analytics
        "com.nimbusds",              // JWT/OAuth
        "com.oezsoft",               // SDK
        "com.loopj",                 // HTTP library
        "com.gun0912",               // Korean libraries
        "com.korks",
        "com.metsakuur",
        "com.mww",

        // Jake Wharton libraries
        "com.jakewharton",

        // Payment/Financial SDKs
        "com.tmoney",                // T-money SDK
        "com.visa",                  // Visa SDK
        "com.tmx",                   // Payment SDK

        // Korean Telecom SDKs
        "com.skt",
        "com.skp",
        "com.sktelecom",

        // Security/Cert SDKs
        "com.penta",                 // Security SDK
        "com.ssenstone",             // Security SDK

        // Other vendor SDKs
        "com.samsung",               // Samsung SDK
        "com.posicube",              // SDK
        "com.seerooinfo",            // SDK
        "com.atsoltutions"           // SDK (typo in original)
    )

    fun organize(
        decompiledSourceDir: File,
        targetJavaDir: File,
        targetKotlinDir: File?,
        excludeLibraries: Boolean = true
    ): OrganizeResult {
        Logger.info("Organizing source files...")

        val sourceFiles = mutableListOf<SourceFile>()
        val errors = mutableListOf<String>()
        var javaCount = 0
        var kotlinCount = 0
        var errorCount = 0
        var skippedLibraries = 0

        // Find all source files
        val allSourceFiles = decompiledSourceDir.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .toList()

        val progress = ProgressBar(allSourceFiles.size, "Copying source files")

        for ((index, file) in allSourceFiles.withIndex()) {
            progress.update(index + 1)

            try {
                val packageName = extractPackageName(file)
                val className = file.nameWithoutExtension
                val language = if (file.extension == "kt") SourceLanguage.KOTLIN else SourceLanguage.JAVA

                // Skip known library packages if excludeLibraries is enabled
                if (excludeLibraries && isLibraryPackage(packageName)) {
                    skippedLibraries++
                    continue
                }

                // Determine target directory
                val targetBaseDir = when (language) {
                    SourceLanguage.KOTLIN -> targetKotlinDir ?: targetJavaDir
                    SourceLanguage.JAVA -> targetJavaDir
                }

                // Create package directory structure
                val packageDir = if (packageName.isNotEmpty()) {
                    File(targetBaseDir, packageName.replace(".", File.separator))
                } else {
                    targetBaseDir
                }
                packageDir.mkdirs()

                // Copy file
                val targetFile = File(packageDir, file.name)
                Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

                // Check for decompilation errors in the file
                val hasErrors = checkForErrors(targetFile)

                sourceFiles.add(SourceFile(
                    file = targetFile,
                    packageName = packageName,
                    className = className,
                    language = language,
                    isDecompiled = true,
                    hasErrors = hasErrors
                ))

                when (language) {
                    SourceLanguage.JAVA -> javaCount++
                    SourceLanguage.KOTLIN -> kotlinCount++
                }

                if (hasErrors) {
                    errorCount++
                }

            } catch (e: Exception) {
                errors.add("Error processing ${file.name}: ${e.message}")
                Logger.debug("Error processing ${file.name}: ${e.message}")
            }
        }

        Logger.success("Organized $javaCount Java files, $kotlinCount Kotlin files")
        if (skippedLibraries > 0) {
            Logger.info("Skipped $skippedLibraries library files (replaced by Maven dependencies)")
        }
        if (errorCount > 0) {
            Logger.warn("$errorCount files may have decompilation errors")
        }

        return OrganizeResult(
            sourceFiles = sourceFiles,
            javaCount = javaCount,
            kotlinCount = kotlinCount,
            errorCount = errorCount,
            skippedLibraries = skippedLibraries,
            errors = errors
        )
    }

    /**
     * Check if a package belongs to a known library that should be excluded
     */
    private fun isLibraryPackage(packageName: String): Boolean {
        // Check against known library prefixes
        if (libraryPackagePrefixes.any { prefix ->
            packageName == prefix || packageName.startsWith("$prefix.")
        }) {
            return true
        }

        // For JADX-renamed packages (p123abc pattern), check if they contain library identifiers
        if (packageName.matches(Regex("""^p\d+.*"""))) {
            val suffix = packageName.removePrefix("p").replace(Regex("""^\d+"""), "")

            // j$ is Java desugaring library - always exclude
            // JADX renames j$ to p024j$ (or similar p###j$ pattern)
            if (suffix.startsWith("j\$") || suffix == "j") {
                return true
            }

            // Check for known library identifiers in the suffix
            val libraryIdentifiers = setOf(
                "rx",       // RxJava
                "gson",     // Gson
                "okhttp",   // OkHttp
                "okio",     // Okio
                "retrofit", // Retrofit
                "glide",    // Glide
                "dagger",   // Dagger
                "hilt",     // Hilt
                "kotlin",   // Kotlin
                "guava",    // Guava
                "proto",    // Protocol Buffers
                "grpc",     // gRPC
                "firebase", // Firebase
                "gms",      // Google Play Services
                "play",     // Google Play
                "volley",   // Volley
                "picasso",  // Picasso
                "moshi",    // Moshi
                "wire",     // Wire
                "room",     // Room
                "work",     // WorkManager
                "paging",   // Paging
                "lifecycle",// Lifecycle
                "navigation",// Navigation
                "compose",  // Compose
                "coroutine",// Coroutines
                "flow",     // Flow
            )

            if (libraryIdentifiers.any { id ->
                suffix.contains(id, ignoreCase = true)
            }) {
                return true
            }
        }

        // Only exclude very short single-letter packages that are clearly obfuscated library artifacts
        // Pattern: Single letter only (a, b, c) - but NOT if followed by letters (could be app code)
        if (packageName.matches(Regex("""^[a-z]$"""))) {
            return true
        }

        return false
    }

    private fun extractPackageName(file: File): String {
        try {
            file.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package ")) {
                        return trimmed
                            .removePrefix("package ")
                            .removeSuffix(";")
                            .trim()
                    }
                    // Stop searching after first non-comment, non-empty line that's not package
                    if (trimmed.isNotEmpty() &&
                        !trimmed.startsWith("//") &&
                        !trimmed.startsWith("/*") &&
                        !trimmed.startsWith("*")) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Logger.debug("Could not extract package from ${file.name}")
        }

        // Fallback: try to infer from directory structure
        val parentPath = file.parentFile?.absolutePath ?: ""
        val srcIndex = parentPath.indexOf("sources")
        if (srcIndex >= 0) {
            val relativePath = parentPath.substring(srcIndex + "sources".length + 1)
            return relativePath.replace(File.separator, ".")
        }

        return ""
    }

    private fun checkForErrors(file: File): Boolean {
        return try {
            val content = file.readText()

            // Common JADX error markers
            val errorPatterns = listOf(
                "/* JADX WARN:",
                "/* JADX ERROR:",
                "// JADX WARN:",
                "// JADX ERROR:",
                "throw new UnsupportedOperationException",
                "/* Code decompiled incorrectly"
            )

            errorPatterns.any { content.contains(it) }
        } catch (e: Exception) {
            true
        }
    }

    fun cleanupSource(sourceDir: File) {
        // Remove common unnecessary files
        val filesToDelete = listOf(
            "BuildConfig.java",
            "R.java",
            "R\$*.java"
        )

        sourceDir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                filesToDelete.any { pattern ->
                    if (pattern.contains("*")) {
                        val regex = pattern.replace("$", "\\$").replace("*", ".*").toRegex()
                        regex.matches(file.name)
                    } else {
                        file.name == pattern
                    }
                }
            }
            .forEach { file ->
                file.delete()
                Logger.debug("Removed generated file: ${file.name}")
            }
    }
}
