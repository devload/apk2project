package com.whatap.apk2project.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.whatap.apk2project.generator.ProjectGenerator
import com.whatap.apk2project.models.GenerateResult
import com.whatap.apk2project.utils.Logger
import com.whatap.apk2project.verifier.ProjectVerifier
import kotlinx.coroutines.runBlocking
import java.io.File

class GenerateCommand : CliktCommand(
    name = "generate",
    help = "Generate a buildable Gradle project from APK"
) {
    private val apkFile by argument("APK", help = "Path to the APK file")
        .file(mustExist = true, canBeDir = false)

    private val outputDir by option("-o", "--output", help = "Output directory")
        .file()

    private val skipVerify by option("--skip-verify", help = "Skip project verification")
        .flag(default = false)

    private val keepTemp by option("--keep-temp", help = "Keep temporary files")
        .flag(default = false)

    private val verbose by option("-v", "--verbose", help = "Verbose output")
        .flag(default = false)

    // AI Deobfuscation options
    private val ai by option("--ai", help = "Enable AI-powered deobfuscation")
        .flag(default = false)

    private val aiClientType by option("--ai-client", help = "AI client type")
        .choice("ollama", "claude", "codex")
        .default("ollama")

    private val modelName by option("--model", help = "AI model name")
        .default("deepseek-coder:33b")

    private val enableKorean by option("--korean", help = "Enable Korean translation")
        .flag(default = false)

    private val translationModelName by option("--translation-model", help = "Translation model name")
        .default("qwen2.5:7b")

    private val batchSize by option("--batch-size", help = "Number of parallel workers")
        .int().default(10)

    private val requestDelay by option("--request-delay", help = "Delay between AI requests (ms)")
        .int().default(1000)

    override fun run(): Unit = runBlocking {
        Logger.verbose = verbose

        val output = outputDir ?: File(apkFile.nameWithoutExtension + "_project")

        if (output.exists() && output.listFiles()?.isNotEmpty() == true) {
            Logger.warn("Output directory already exists: ${output.absolutePath}")
            Logger.warn("Contents will be overwritten")
        }

        output.mkdirs()

        val generator = ProjectGenerator()
        val options = ProjectGenerator.GenerateOptions(
            keepTempFiles = keepTemp,
            skipVerification = skipVerify,
            verbose = verbose,
            // AI Deobfuscation options
            enableAi = ai,
            aiClientType = com.whatap.apk2project.deobfuscator.client.AiClientType.valueOf(aiClientType.uppercase()),
            modelName = modelName,
            enableKorean = enableKorean,
            translationModelName = translationModelName,
            batchSize = batchSize,
            requestDelay = requestDelay.toLong()
        )

        val result = generator.generate(apkFile, output, options)

        when (result) {
            is GenerateResult.Success -> {
                // Optionally verify
                if (!skipVerify) {
                    Logger.header("Verification")
                    val verifier = ProjectVerifier()
                    val verifyResult = verifier.verify(output)

                    when (verifyResult) {
                        is com.whatap.apk2project.models.VerificationResult.Success -> {
                            if (verifyResult.buildable) {
                                Logger.success("Project is ready to build!")
                            } else {
                                Logger.warn("Project may need manual fixes")
                            }
                        }
                        is com.whatap.apk2project.models.VerificationResult.Failure -> {
                            Logger.warn("Verification found issues (project may still build)")
                        }
                    }
                }

                Logger.header("Success!")
                Logger.info("Project generated at: ${output.absolutePath}")
                Logger.info("")
                Logger.info("Next steps:")
                Logger.info("  1. cd ${output.absolutePath}")
                Logger.info("  2. Open in Android Studio")
                Logger.info("  3. Sync Gradle and build")
            }

            is GenerateResult.PartialSuccess -> {
                Logger.header("Partial Success")
                Logger.warn("Project generated with ${result.warnings.size} warnings")
                result.warnings.take(5).forEach { Logger.step(it) }
                Logger.info("Project at: ${output.absolutePath}")
            }

            is GenerateResult.Failure -> {
                Logger.error("Generation failed: ${result.error}")
                result.cause?.let {
                    if (verbose) {
                        Logger.debug(it.stackTraceToString())
                    }
                }
            }
        }
    }
}
