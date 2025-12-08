package com.whatap.apk2project.decompiler

import com.whatap.apk2project.models.DecompileError
import com.whatap.apk2project.models.DecompileErrorType
import com.whatap.apk2project.models.DecompileResult
import com.whatap.apk2project.models.DecompileStats
import com.whatap.apk2project.utils.FileUtils
import com.whatap.apk2project.utils.Logger
import com.whatap.apk2project.utils.ProcessUtils
import java.io.File
import kotlin.system.measureTimeMillis

class JadxDecompiler {

    private var jadxPath: String? = null

    suspend fun findJadx(): String? {
        if (jadxPath != null) return jadxPath

        // Check common locations
        val possiblePaths = listOf(
            ProcessUtils.findExecutable("jadx"),
            "/usr/local/bin/jadx",
            "/opt/homebrew/bin/jadx",
            System.getProperty("user.home") + "/jadx/bin/jadx",
            System.getProperty("user.home") + "/.local/bin/jadx"
        )

        for (path in possiblePaths) {
            if (path != null && File(path).exists()) {
                jadxPath = path
                return path
            }
        }

        return null
    }

    suspend fun decompile(
        apkFile: File,
        outputDir: File,
        options: DecompileOptions = DecompileOptions()
    ): DecompileResult {
        val jadx = findJadx()
        if (jadx == null) {
            return DecompileResult.Failure(
                "JADX not found. Please install JADX: brew install jadx",
                null
            )
        }

        Logger.debug("Using JADX at: $jadx")

        val sourceDir = File(outputDir, "sources")
        val resourceDir = File(outputDir, "resources")
        sourceDir.mkdirs()
        resourceDir.mkdirs()

        val errors = mutableListOf<DecompileError>()
        var totalClasses = 0
        var successfulClasses = 0
        var failedClasses = 0
        var obfuscatedClasses = 0

        val durationMs = measureTimeMillis {
            val command = buildCommand(jadx, apkFile, sourceDir, resourceDir, options)
            Logger.debug("Running: ${command.joinToString(" ")}")

            val result = ProcessUtils.execute(
                command = command,
                workDir = outputDir,
                timeoutSeconds = options.timeoutSeconds
            ) { line ->
                // Parse JADX output for progress
                when {
                    line.contains("processing") -> {
                        Logger.debug(line)
                    }
                    line.contains("INFO") && line.contains("classes") -> {
                        val match = Regex("""(\d+)\s+classes""").find(line)
                        match?.groupValues?.get(1)?.toIntOrNull()?.let {
                            totalClasses = it
                        }
                    }
                    line.contains("ERROR") -> {
                        val className = extractClassName(line)
                        errors.add(
                            DecompileError(
                                className = className,
                                errorType = DecompileErrorType.DECOMPILATION_ERROR,
                                message = line
                            )
                        )
                        failedClasses++
                    }
                    line.contains("WARN") && line.contains("Can't process") -> {
                        failedClasses++
                    }
                }
            }

            if (result.timedOut) {
                return DecompileResult.Failure("JADX timed out after ${options.timeoutSeconds} seconds")
            }

            // Count actual decompiled files
            val javaFiles = FileUtils.findFiles(sourceDir, "java")
            val kotlinFiles = FileUtils.findFiles(sourceDir, "kt")
            successfulClasses = javaFiles.size + kotlinFiles.size

            if (totalClasses == 0) {
                totalClasses = successfulClasses + failedClasses
            }

            // Detect obfuscated classes (single letter names, etc.)
            obfuscatedClasses = javaFiles.count { file ->
                val name = file.nameWithoutExtension
                name.length <= 2 || name.matches(Regex("[a-z]{1,2}"))
            }

            Logger.debug("JADX exit code: ${result.exitCode}")
            Logger.debug("Decompiled ${successfulClasses} classes, ${failedClasses} failed")
        }

        val stats = DecompileStats(
            totalClasses = totalClasses.coerceAtLeast(successfulClasses),
            successfulClasses = successfulClasses,
            failedClasses = failedClasses,
            totalMethods = estimateMethods(successfulClasses),
            obfuscatedClasses = obfuscatedClasses,
            durationMs = durationMs
        )

        return when {
            successfulClasses == 0 -> DecompileResult.Failure(
                "No classes were decompiled. Check if APK is valid.",
                null
            )
            errors.isEmpty() -> DecompileResult.Success(sourceDir, resourceDir, stats)
            else -> DecompileResult.PartialSuccess(sourceDir, resourceDir, stats, errors)
        }
    }

    private fun buildCommand(
        jadx: String,
        apkFile: File,
        sourceDir: File,
        resourceDir: File,
        options: DecompileOptions
    ): List<String> {
        val cmd = mutableListOf(
            jadx,
            apkFile.absolutePath,
            "--output-dir", sourceDir.absolutePath,
            "--output-dir-res", resourceDir.absolutePath
        )

        if (options.showBadCode) {
            cmd.add("--show-bad-code")
        }

        if (options.deobfuscate) {
            cmd.add("--deobf")
        }

        if (options.escapeUnicode) {
            cmd.add("--escape-unicode")
        }

        cmd.addAll(listOf("--threads-count", options.threadCount.toString()))

        if (!options.decompileResources) {
            cmd.add("--no-res")
        }

        if (options.skipResources) {
            cmd.add("--skip-resources")
        }

        return cmd
    }

    private fun extractClassName(logLine: String): String {
        // Try to extract class name from JADX log line
        val patterns = listOf(
            Regex("""class:\s*(\S+)"""),
            Regex("""Method\s+(\S+)"""),
            Regex("""(\S+\.class)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(logLine)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return "Unknown"
    }

    private fun estimateMethods(classCount: Int): Int {
        // Average of ~10 methods per class
        return classCount * 10
    }

    data class DecompileOptions(
        val showBadCode: Boolean = true,
        val deobfuscate: Boolean = true,
        val escapeUnicode: Boolean = true,
        val threadCount: Int = Runtime.getRuntime().availableProcessors(),
        val decompileResources: Boolean = true,
        val skipResources: Boolean = false,
        val timeoutSeconds: Long = 600 // 10 minutes
    )
}
