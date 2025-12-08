package com.whatap.apk2project.analyzer

import com.whatap.apk2project.utils.Logger
import java.io.File

class PackageScanner {

    data class ScanResult(
        val packages: Set<String>,
        val imports: Set<String>,
        val classNames: Set<String>,
        val hasKotlin: Boolean,
        val hasJava: Boolean
    )

    fun scan(sourceDir: File): ScanResult {
        val packages = mutableSetOf<String>()
        val imports = mutableSetOf<String>()
        val classNames = mutableSetOf<String>()
        var hasKotlin = false
        var hasJava = false

        sourceDir.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .forEach { file ->
                when (file.extension) {
                    "java" -> {
                        hasJava = true
                        scanJavaFile(file, packages, imports, classNames)
                    }
                    "kt" -> {
                        hasKotlin = true
                        scanKotlinFile(file, packages, imports, classNames)
                    }
                }
            }

        Logger.debug("Scanned packages: ${packages.size}, imports: ${imports.size}")

        return ScanResult(
            packages = packages,
            imports = imports,
            classNames = classNames,
            hasKotlin = hasKotlin,
            hasJava = hasJava
        )
    }

    private fun scanJavaFile(
        file: File,
        packages: MutableSet<String>,
        imports: MutableSet<String>,
        classNames: MutableSet<String>
    ) {
        try {
            file.useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()

                    // Package declaration
                    if (trimmed.startsWith("package ")) {
                        val pkg = trimmed
                            .removePrefix("package ")
                            .removeSuffix(";")
                            .trim()
                        packages.add(pkg)
                    }

                    // Import statement
                    if (trimmed.startsWith("import ")) {
                        val import = trimmed
                            .removePrefix("import ")
                            .removePrefix("static ")
                            .removeSuffix(";")
                            .trim()

                        // Remove wildcard imports but keep the package
                        val cleanImport = if (import.endsWith(".*")) {
                            import.removeSuffix(".*")
                        } else {
                            import
                        }
                        imports.add(cleanImport)

                        // Extract package from full class name
                        val pkg = cleanImport.substringBeforeLast(".", "")
                        if (pkg.isNotEmpty()) {
                            packages.add(pkg)
                        }
                    }

                    // Class declarations
                    val classMatch = CLASS_PATTERN.find(trimmed)
                    if (classMatch != null) {
                        classNames.add(classMatch.groupValues[1])
                    }
                }
            }
        } catch (e: Exception) {
            Logger.debug("Error scanning ${file.name}: ${e.message}")
        }
    }

    private fun scanKotlinFile(
        file: File,
        packages: MutableSet<String>,
        imports: MutableSet<String>,
        classNames: MutableSet<String>
    ) {
        try {
            file.useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()

                    // Package declaration
                    if (trimmed.startsWith("package ")) {
                        val pkg = trimmed.removePrefix("package ").trim()
                        packages.add(pkg)
                    }

                    // Import statement
                    if (trimmed.startsWith("import ")) {
                        val import = trimmed
                            .removePrefix("import ")
                            .substringBefore(" as ") // Handle aliases
                            .trim()

                        val cleanImport = if (import.endsWith(".*")) {
                            import.removeSuffix(".*")
                        } else {
                            import
                        }
                        imports.add(cleanImport)

                        val pkg = cleanImport.substringBeforeLast(".", "")
                        if (pkg.isNotEmpty()) {
                            packages.add(pkg)
                        }
                    }

                    // Class/object/interface declarations
                    val classMatch = KOTLIN_CLASS_PATTERN.find(trimmed)
                    if (classMatch != null) {
                        classNames.add(classMatch.groupValues[2])
                    }
                }
            }
        } catch (e: Exception) {
            Logger.debug("Error scanning ${file.name}: ${e.message}")
        }
    }

    fun extractPackagePrefix(fullPackage: String): String {
        val parts = fullPackage.split(".")

        // Common patterns for library packages
        return when {
            // androidx.xxx.yyy -> androidx.xxx
            parts.getOrNull(0) == "androidx" -> parts.take(2).joinToString(".")

            // com.google.xxx -> com.google.xxx
            parts.getOrNull(0) == "com" && parts.getOrNull(1) == "google" ->
                parts.take(3).joinToString(".")

            // com.xxx.yyy -> com.xxx.yyy (first 3 parts)
            parts.getOrNull(0) == "com" -> parts.take(3).joinToString(".")

            // org.xxx -> org.xxx
            parts.getOrNull(0) == "org" -> parts.take(2).joinToString(".")

            // io.xxx -> io.xxx
            parts.getOrNull(0) == "io" -> parts.take(2).joinToString(".")

            // Default: first 2-3 parts
            else -> parts.take(minOf(3, parts.size)).joinToString(".")
        }
    }

    fun categorizePackages(packages: Set<String>): PackageCategories {
        val androidPlatform = mutableSetOf<String>()
        val androidx = mutableSetOf<String>()
        val googleServices = mutableSetOf<String>()
        val thirdParty = mutableSetOf<String>()
        val appPackages = mutableSetOf<String>()

        packages.forEach { pkg ->
            when {
                pkg.startsWith("android.") || pkg.startsWith("java.") || pkg.startsWith("javax.") ->
                    androidPlatform.add(pkg)
                pkg.startsWith("androidx.") ->
                    androidx.add(pkg)
                pkg.startsWith("com.google.android.gms") || pkg.startsWith("com.google.firebase") ->
                    googleServices.add(pkg)
                isThirdPartyPackage(pkg) ->
                    thirdParty.add(pkg)
                else ->
                    appPackages.add(pkg)
            }
        }

        return PackageCategories(
            androidPlatform = androidPlatform,
            androidx = androidx,
            googleServices = googleServices,
            thirdParty = thirdParty,
            appPackages = appPackages
        )
    }

    private fun isThirdPartyPackage(pkg: String): Boolean {
        val thirdPartyPrefixes = listOf(
            "com.squareup", "com.jakewharton", "com.github", "com.airbnb",
            "io.reactivex", "io.coil", "io.insert-koin",
            "org.greenrobot", "org.apache",
            "retrofit2", "okhttp3", "okio",
            "dagger", "timber", "kotlinx", "kotlin"
        )

        return thirdPartyPrefixes.any { pkg.startsWith(it) }
    }

    companion object {
        private val CLASS_PATTERN = Regex("""(?:public\s+)?(?:final\s+)?(?:abstract\s+)?(?:class|interface|enum)\s+(\w+)""")
        private val KOTLIN_CLASS_PATTERN = Regex("""(?:data\s+|sealed\s+|abstract\s+|open\s+)?(class|object|interface)\s+(\w+)""")
    }
}

data class PackageCategories(
    val androidPlatform: Set<String>,
    val androidx: Set<String>,
    val googleServices: Set<String>,
    val thirdParty: Set<String>,
    val appPackages: Set<String>
)
