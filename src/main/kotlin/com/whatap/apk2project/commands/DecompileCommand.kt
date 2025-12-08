package com.whatap.apk2project.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.whatap.apk2project.decompiler.JadxDecompiler
import com.whatap.apk2project.decompiler.ResourceExtractor
import com.whatap.apk2project.models.DecompileResult
import com.whatap.apk2project.utils.FileUtils
import com.whatap.apk2project.utils.Logger
import kotlinx.coroutines.runBlocking
import java.io.File

class DecompileCommand : CliktCommand(
    name = "decompile",
    help = "Decompile APK to Java/Kotlin source code"
) {
    private val apkFile by argument("APK", help = "Path to the APK file")
        .file(mustExist = true, canBeDir = false)

    private val outputDir by option("-o", "--output", help = "Output directory")
        .file()

    private val skipResources by option("--skip-resources", help = "Skip resource extraction")
        .flag(default = false)

    private val deobfuscate by option("--deobf", help = "Enable deobfuscation")
        .flag(default = true)

    private val verbose by option("-v", "--verbose", help = "Verbose output")
        .flag(default = false)

    override fun run(): Unit = runBlocking {
        Logger.verbose = verbose

        val output = outputDir ?: File(apkFile.nameWithoutExtension + "_decompiled")
        output.mkdirs()

        Logger.header("APK Decompiler")
        Logger.info("Input: ${apkFile.absolutePath}")
        Logger.info("Output: ${output.absolutePath}")

        val decompiler = JadxDecompiler()

        // Check JADX availability
        val jadxPath = decompiler.findJadx()
        if (jadxPath == null) {
            Logger.error("JADX not found!")
            Logger.info("Install JADX:")
            Logger.step("macOS: brew install jadx")
            Logger.step("Linux: sudo apt install jadx")
            Logger.lastStep("Manual: https://github.com/skylot/jadx/releases")
            return@runBlocking
        }

        Logger.success("JADX found at: $jadxPath")

        // Decompile
        val options = JadxDecompiler.DecompileOptions(
            deobfuscate = deobfuscate,
            skipResources = skipResources
        )

        val result = decompiler.decompile(apkFile, output, options)

        when (result) {
            is DecompileResult.Success -> {
                Logger.header("Decompilation Complete")
                Logger.success("Sources: ${result.sourceDir.absolutePath}")
                Logger.success("Resources: ${result.resourceDir.absolutePath}")
                Logger.info("Statistics:")
                Logger.step("Total classes: ${result.stats.totalClasses}")
                Logger.step("Successful: ${result.stats.successfulClasses}")
                Logger.step("Failed: ${result.stats.failedClasses}")
                Logger.lastStep("Success rate: ${(result.stats.successRate * 100).toInt()}%")
            }

            is DecompileResult.PartialSuccess -> {
                Logger.header("Decompilation Partial Success")
                Logger.warn("Completed with ${result.errors.size} errors")
                Logger.success("Sources: ${result.sourceDir.absolutePath}")
                Logger.info("Statistics:")
                Logger.step("Total classes: ${result.stats.totalClasses}")
                Logger.step("Successful: ${result.stats.successfulClasses}")
                Logger.step("Failed: ${result.stats.failedClasses}")
                Logger.lastStep("Success rate: ${(result.stats.successRate * 100).toInt()}%")

                if (verbose) {
                    Logger.info("Errors:")
                    result.errors.take(10).forEach {
                        Logger.step("${it.className}: ${it.message}")
                    }
                }
            }

            is DecompileResult.Failure -> {
                Logger.error("Decompilation failed: ${result.error}")
                result.cause?.let { Logger.debug(it.stackTraceToString()) }
            }
        }
    }
}
