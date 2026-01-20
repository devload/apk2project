package com.whatap.apk2project.analyzer

import com.whatap.apk2project.models.ObfuscationLevel
import com.whatap.apk2project.utils.Logger
import java.io.File

class ObfuscationDetector {

    data class ObfuscationReport(
        val isObfuscated: Boolean,
        val level: ObfuscationLevel,
        val obfuscatedClassCount: Int,
        val totalClassCount: Int,
        val obfuscatedMethodCount: Int,
        val details: List<String>
    ) {
        val obfuscationPercentage: Float
            get() = if (totalClassCount > 0) {
                (obfuscatedClassCount.toFloat() / totalClassCount) * 100
            } else 0f
    }

    fun detect(sourceDir: File): ObfuscationReport {
        val details = mutableListOf<String>()
        var obfuscatedClassCount = 0
        var totalClassCount = 0
        var obfuscatedMethodCount = 0

        val sourceFiles = sourceDir.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .toList()

        for (file in sourceFiles) {
            totalClassCount++

            val analysis = analyzeFile(file)

            if (analysis.isClassNameObfuscated) {
                obfuscatedClassCount++
            }

            obfuscatedMethodCount += analysis.obfuscatedMethodCount
        }

        val obfuscationRatio = if (totalClassCount > 0) {
            obfuscatedClassCount.toFloat() / totalClassCount
        } else 0f

        val level = when {
            obfuscationRatio < 0.1 -> ObfuscationLevel.NONE
            obfuscationRatio < 0.3 -> ObfuscationLevel.LIGHT
            obfuscationRatio < 0.6 -> ObfuscationLevel.MODERATE
            else -> ObfuscationLevel.HEAVY
        }

        if (obfuscationRatio > 0.1) {
            details.add("${(obfuscationRatio * 100).toInt()}% of classes have obfuscated names")
        }

        if (obfuscatedMethodCount > 0) {
            details.add("$obfuscatedMethodCount methods with obfuscated names detected")
        }

        // Check for specific obfuscation patterns
        val patterns = detectObfuscationPatterns(sourceDir)
        details.addAll(patterns)

        Logger.debug("Obfuscation detection: level=$level, obfuscated=$obfuscatedClassCount/$totalClassCount")

        return ObfuscationReport(
            isObfuscated = level != ObfuscationLevel.NONE,
            level = level,
            obfuscatedClassCount = obfuscatedClassCount,
            totalClassCount = totalClassCount,
            obfuscatedMethodCount = obfuscatedMethodCount,
            details = details
        )
    }

    private fun analyzeFile(file: File): FileAnalysis {
        val className = file.nameWithoutExtension
        val isClassNameObfuscated = isObfuscatedName(className)

        var obfuscatedMethodCount = 0
        var hasStringEncryption = false

        try {
            file.useLines { lines ->
                lines.forEach { line ->
                    // Check for obfuscated method names
                    METHOD_PATTERN.findAll(line).forEach { match ->
                        val methodName = match.groupValues[1]
                        if (isObfuscatedName(methodName)) {
                            obfuscatedMethodCount++
                        }
                    }

                    // Check for string encryption patterns
                    if (STRING_ENCRYPTION_PATTERNS.any { it.containsMatchIn(line) }) {
                        hasStringEncryption = true
                    }
                }
            }
        } catch (e: Exception) {
            Logger.debug("Error analyzing file ${file.name}: ${e.message}")
        }

        return FileAnalysis(
            isClassNameObfuscated = isClassNameObfuscated,
            obfuscatedMethodCount = obfuscatedMethodCount,
            hasStringEncryption = hasStringEncryption
        )
    }

    private fun isObfuscatedName(name: String): Boolean {
        // Common obfuscation patterns (uses pre-compiled patterns from companion object)
        return when {
            // Single letter (a, b, c, ...)
            name.length == 1 && name[0].isLetter() -> true

            // Two letters (aa, ab, ...)
            name.length == 2 && name.all { it.isLetter() && it.isLowerCase() } -> true

            // Letter followed by numbers (a0, b1, ...)
            LETTER_NUMBER_PATTERN.matches(name) -> true

            // Common ProGuard patterns
            PROGUARD_PATTERN.matches(name) -> true

            // R8 patterns with underscores
            R8_UNDERSCORE_PATTERN.matches(name) -> true

            // Names like "C0001" or "C1234abc"
            CLASS_NUMBER_PATTERN.matches(name) -> true

            else -> false
        }
    }

    private fun detectObfuscationPatterns(sourceDir: File): List<String> {
        val patterns = mutableListOf<String>()

        // Check for ProGuard-style package flattening
        val packageDepths = mutableMapOf<Int, Int>()
        sourceDir.walkTopDown()
            .filter { it.isDirectory }
            .forEach { dir ->
                val depth = dir.relativeTo(sourceDir).path.split(File.separator).size
                packageDepths[depth] = (packageDepths[depth] ?: 0) + 1
            }

        // If most packages are at depth 1-2, likely flattened
        val flattenedCount = (packageDepths[1] ?: 0) + (packageDepths[2] ?: 0)
        val deepCount = packageDepths.filter { it.key > 2 }.values.sum()
        if (flattenedCount > deepCount * 3) {
            patterns.add("Package flattening detected (ProGuard -repackageclasses)")
        }

        // Check for removed line numbers
        var filesWithoutLineNumbers = 0
        var totalFiles = 0
        sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .take(20) // Sample
            .forEach { file ->
                totalFiles++
                val content = file.readText()
                if (!content.contains("// ") && !content.contains("/* ")) {
                    filesWithoutLineNumbers++
                }
            }

        if (totalFiles > 0 && filesWithoutLineNumbers > totalFiles * 0.8) {
            patterns.add("Source debugging info likely removed")
        }

        return patterns
    }

    private data class FileAnalysis(
        val isClassNameObfuscated: Boolean,
        val obfuscatedMethodCount: Int,
        val hasStringEncryption: Boolean
    )

    companion object {
        private val METHOD_PATTERN = Regex("""(?:public|private|protected|static|\s)+[\w<>\[\]]+\s+(\w+)\s*\(""")

        private val STRING_ENCRYPTION_PATTERNS = listOf(
            Regex("""new\s+String\s*\(\s*new\s+byte\s*\["""),  // byte array to string
            Regex("""Base64\.decode"""),                        // Base64 decoding
            Regex("""\.getBytes\s*\(\s*\)\s*\^\s*"""),          // XOR with bytes
            Regex("""\^\s*0x[0-9a-fA-F]+"""),                   // XOR with hex
        )

        // Pre-compiled obfuscation detection patterns (performance optimization)
        // Avoids creating new Regex objects on every check
        private val LETTER_NUMBER_PATTERN = Regex("[a-z][0-9]+")
        private val PROGUARD_PATTERN = Regex("[a-z]{1,3}[A-Z]?[0-9]*")
        private val R8_UNDERSCORE_PATTERN = Regex("[a-z]_[a-z]+")
        private val CLASS_NUMBER_PATTERN = Regex("[A-Z][0-9]{2,}[a-z]*")

        // Obfuscated name/package patterns for external use
        private val OBFUSCATED_NAME_PATTERN = Regex("^[a-z]{1,2}$|^[A-Z][a-z]?$")
        private val OBFUSCATED_PACKAGE_PATTERN = Regex("^[a-z]{1,2}(\\.[a-z]{1,2})*$")

        /**
         * Check if a name appears to be obfuscated (static method for external use)
         */
        fun isObfuscatedName(name: String): Boolean {
            return when {
                name.length == 1 && name[0].isLetter() -> true
                name.length == 2 && name.all { it.isLetter() && it.isLowerCase() } -> true
                LETTER_NUMBER_PATTERN.matches(name) -> true
                PROGUARD_PATTERN.matches(name) -> true
                R8_UNDERSCORE_PATTERN.matches(name) -> true
                CLASS_NUMBER_PATTERN.matches(name) -> true
                else -> false
            }
        }

        /**
         * Check if a package name appears to be obfuscated (static method for external use)
         */
        fun isObfuscatedPackage(packageName: String): Boolean {
            if (packageName.isEmpty()) return false
            return OBFUSCATED_PACKAGE_PATTERN.matches(packageName) ||
                    packageName.split(".").all { it.length <= 2 }
        }

        // Known non-obfuscated packages
        private val KNOWN_PACKAGES = setOf(
            "android", "androidx", "com.google", "com.android",
            "java", "javax", "kotlin", "kotlinx",
            "org.apache", "org.json", "io.reactivex", "okhttp3",
            "retrofit2", "com.squareup", "com.facebook", "com.twitter"
        )

        /**
         * Check if this is a known library package (not obfuscated)
         */
        fun isKnownPackage(className: String): Boolean {
            return KNOWN_PACKAGES.any { className.startsWith(it) }
        }
    }
}
