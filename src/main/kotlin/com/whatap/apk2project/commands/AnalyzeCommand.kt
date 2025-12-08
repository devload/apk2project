package com.whatap.apk2project.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.whatap.apk2project.analyzer.DependencyAnalyzer
import com.whatap.apk2project.analyzer.ObfuscationDetector
import com.whatap.apk2project.decompiler.JadxDecompiler
import com.whatap.apk2project.decompiler.ManifestParser
import com.whatap.apk2project.models.AnalysisResult
import com.whatap.apk2project.models.DecompileResult
import com.whatap.apk2project.utils.FileUtils
import com.whatap.apk2project.utils.Logger
import kotlinx.coroutines.runBlocking
import java.io.File

class AnalyzeCommand : CliktCommand(
    name = "analyze",
    help = "Analyze APK dependencies and structure"
) {
    private val apkFile by argument("APK", help = "Path to the APK file")
        .file(mustExist = true, canBeDir = false)

    private val verbose by option("-v", "--verbose", help = "Verbose output")
        .flag(default = false)

    private val showAll by option("--all", help = "Show all detected dependencies")
        .flag(default = false)

    override fun run(): Unit = runBlocking {
        Logger.verbose = verbose

        Logger.header("APK Analyzer")
        Logger.info("Analyzing: ${apkFile.name}")

        val workDir = FileUtils.createTempDirectory("apk2project_analyze")

        try {
            // Parse manifest
            Logger.step("Parsing AndroidManifest...")
            val manifestParser = ManifestParser()
            val apkInfo = manifestParser.parseFromApk(apkFile, workDir)

            if (apkInfo != null) {
                Logger.header("APK Information")
                Logger.info("Package: ${apkInfo.packageName}")
                Logger.info("Version: ${apkInfo.versionName} (${apkInfo.versionCode})")
                Logger.info("Min SDK: ${apkInfo.minSdk}")
                Logger.info("Target SDK: ${apkInfo.targetSdk}")
                Logger.info("DEX files: ${apkInfo.dexCount}")
                Logger.info("Native libs: ${apkInfo.nativeLibraries.size}")

                if (apkInfo.permissions.isNotEmpty()) {
                    Logger.header("Permissions (${apkInfo.permissions.size})")
                    apkInfo.permissions.take(if (showAll) Int.MAX_VALUE else 10).forEach {
                        Logger.step(it.removePrefix("android.permission."))
                    }
                    if (!showAll && apkInfo.permissions.size > 10) {
                        Logger.lastStep("... and ${apkInfo.permissions.size - 10} more")
                    }
                }
            }

            // Decompile for analysis
            Logger.header("Decompiling for Analysis")
            val decompiler = JadxDecompiler()

            if (decompiler.findJadx() == null) {
                Logger.error("JADX not found. Install with: brew install jadx")
                return@runBlocking
            }

            val decompileResult = decompiler.decompile(apkFile, workDir)
            val sourceDir = when (decompileResult) {
                is DecompileResult.Success -> decompileResult.sourceDir
                is DecompileResult.PartialSuccess -> decompileResult.sourceDir
                is DecompileResult.Failure -> {
                    Logger.error("Decompilation failed: ${decompileResult.error}")
                    return@runBlocking
                }
            }

            // Detect obfuscation
            Logger.header("Obfuscation Detection")
            val obfuscationDetector = ObfuscationDetector()
            val obfuscationReport = obfuscationDetector.detect(sourceDir)

            Logger.info("Obfuscation level: ${obfuscationReport.level}")
            Logger.info("Obfuscated classes: ${obfuscationReport.obfuscatedClassCount}/${obfuscationReport.totalClassCount} (${obfuscationReport.obfuscationPercentage.toInt()}%)")

            if (obfuscationReport.details.isNotEmpty()) {
                obfuscationReport.details.forEach { Logger.step(it) }
            }

            // Analyze dependencies
            Logger.header("Dependency Analysis")
            val analyzer = DependencyAnalyzer()
            val analysisResult = analyzer.analyze(sourceDir)

            when (analysisResult) {
                is AnalysisResult.Success -> {
                    val deps = analysisResult.dependencies
                    val highConfidence = deps.filter { it.confidence >= 0.8f }
                    val mediumConfidence = deps.filter { it.confidence >= 0.6f && it.confidence < 0.8f }
                    val lowConfidence = deps.filter { it.confidence < 0.6f }

                    Logger.header("Detected Dependencies (${deps.size} total)")

                    if (highConfidence.isNotEmpty()) {
                        Logger.info("High Confidence (${highConfidence.size}):")
                        highConfidence.forEach { dep ->
                            Logger.success("  ${dep.toGradleNotation()}")
                        }
                    }

                    if (mediumConfidence.isNotEmpty()) {
                        Logger.info("Medium Confidence (${mediumConfidence.size}):")
                        mediumConfidence.forEach { dep ->
                            Logger.warn("  ${dep.toGradleNotation()} (${(dep.confidence * 100).toInt()}%)")
                        }
                    }

                    if (showAll && lowConfidence.isNotEmpty()) {
                        Logger.info("Low Confidence (${lowConfidence.size}):")
                        lowConfidence.forEach { dep ->
                            Logger.step("  ${dep.toGradleNotation()} (${(dep.confidence * 100).toInt()}%)")
                        }
                    }

                    // Print Gradle dependencies block
                    Logger.header("Gradle Dependencies Block")
                    println()
                    println("dependencies {")
                    highConfidence.forEach { dep ->
                        println("    implementation '${dep.toGradleNotation()}'")
                    }
                    if (mediumConfidence.isNotEmpty()) {
                        println()
                        println("    // Medium confidence - verify manually")
                        mediumConfidence.forEach { dep ->
                            println("    implementation '${dep.toGradleNotation()}'")
                        }
                    }
                    println("}")
                    println()
                }

                is AnalysisResult.Failure -> {
                    Logger.error("Analysis failed: ${analysisResult.error}")
                }
            }

        } finally {
            FileUtils.deleteDirectory(workDir)
        }
    }
}
