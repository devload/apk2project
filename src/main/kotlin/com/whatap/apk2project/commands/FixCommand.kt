package com.whatap.apk2project.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.whatap.apk2project.deobfuscator.client.AiClientType
import com.whatap.apk2project.deobfuscator.pipeline.DeobfuscationPipeline
import com.whatap.apk2project.deobfuscator.pipeline.PipelineConfig
import com.whatap.apk2project.fixer.CodeFixer
import com.whatap.apk2project.utils.Logger
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path

class FixCommand : CliktCommand(
    name = "fix",
    help = """
        Fix decompilation errors in Java source files.

        Applies automatic corrections to common JADX decompilation issues.

        With --ai flag, enables AI-based deobfuscation pipeline.

        Example:
          apk2project fix ./project
          apk2project fix ./project --ai
          apk2project fix ./project --ai --model deepseek-coder:6.7b
          apk2project fix ./project --ai --korean
    """.trimIndent()
) {
    private val projectPath by argument(
        name = "project",
        help = "Path to generated project or source directory"
    ).path(mustExist = true)

    // AI Deobfuscation options
    private val ai by option(
        "--ai",
        help = "Enable AI-based deobfuscation using Claude API"
    ).flag(default = false)

    private val aiClientType by option(
        "--ai-client",
        help = "AI client type: ollama, claude, codex"
    ).choice("ollama", "claude", "codex").default("ollama")

    private val modelName by option(
        "--model",
        help = "Model name (e.g., deepseek-coder:6.7b for Ollama)"
    ).default("deepseek-coder:6.7b")

    private val batchSize by option(
        "--batch-size",
        help = "Number of parallel workers"
    ).int().default(10)

    private val enableKorean by option(
        "--korean",
        help = "Enable Korean translation for class/method names"
    ).flag(default = false)

    private val translationModel by option(
        "--translation-model",
        help = "Translation model name"
    ).default("qwen2.5:7b")

    private val ollamaBaseUrl by option(
        "--ollama-base-url",
        help = "Ollama server URL (default: http://localhost:11434). Use this to connect to remote Ollama server."
    ).default("http://localhost:11434")

    override fun run() {
        val sourceDir = findSourceDir(projectPath)
        if (sourceDir == null) {
            Logger.error("Could not find Java source directory")
            return
        }

        // Initialize ProgressMonitor if AI is enabled (Next.js dashboard will read status.json)
        var monitor: com.whatap.apk2project.deobfuscator.monitor.ProgressMonitor? = null

        if (ai) {
            val outputDir = sourceDir.resolve(".apk2project").toFile()
            outputDir.mkdirs()

            try {
                monitor = com.whatap.apk2project.deobfuscator.monitor.ProgressMonitor(outputDir)
                monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.INITIALIZING
                monitor.phase = "Fixing Errors"
                monitor.status = "Scanning for compilation errors..."
                monitor.start()

                Logger.info("Dashboard: http://localhost:3000 (Next.js)")
            } catch (e: Exception) {
                Logger.warn("Failed to start progress monitor: ${e.message}")
            }
        }

        Logger.header("Fixing Decompilation Errors")
        Logger.info("Source directory: $sourceDir")

        // Run standard fixes first
        val fixer = CodeFixer()
        val result = fixer.fixSourceDirectory(sourceDir.toFile(), monitor)

        Logger.info("────────────────────────────────────────────────")
        Logger.success("Fix complete!")
        Logger.info("Fixed files: ${result.fixedFiles}")
        Logger.info("Total fixes: ${result.totalFixes}")
        if (result.stubsGenerated > 0) {
            Logger.info("Stubs generated: ${result.stubsGenerated}")
        }

        // Run AI deobfuscation if enabled
        if (ai) {
            runAiDeobfuscation(sourceDir, monitor)
        }
    }

    private fun runAiDeobfuscation(sourceDir: Path, monitor: com.whatap.apk2project.deobfuscator.monitor.ProgressMonitor?) {
        Logger.header("AI Deobfuscation Pipeline")

        // Create output directory
        val outputDir = sourceDir.resolve(".apk2project").toFile()
        outputDir.mkdirs()

        val config = PipelineConfig(
            aiClientType = when (aiClientType) {
                "claude" -> AiClientType.CLAUDE
                "codex" -> AiClientType.CODEX
                else -> AiClientType.OLLAMA
            },
            modelName = modelName,
            batchSize = batchSize,
            enableKorean = enableKorean,
            translationModelName = if (enableKorean) translationModel else null,
            ollamaBaseUrl = ollamaBaseUrl
        )

        Logger.info("AI Client: $aiClientType")
        Logger.info("Model: $modelName")
        Logger.info("Batch Size: $batchSize")
        Logger.info("Korean Translation: ${if (enableKorean) "Enabled ($translationModel)" else "Disabled"}")
        Logger.info("Output: ${outputDir.absolutePath}")

        val pipeline = DeobfuscationPipeline(
            sourceDir = sourceDir.toFile(),
            outputDir = outputDir,
            config = config
        )

        // Run pipeline
        val result = runBlocking {
            pipeline.run()
        }

        Logger.info("────────────────────────────────────────────────")
        if (result.success) {
            Logger.success("AI Deobfuscation complete!")
            Logger.info("Total Methods: ${result.stats.totalMethods}")
            Logger.info("Processed Methods: ${result.stats.processedMethods}")
            Logger.info("Renamed Methods: ${result.stats.renamedMethods}")
            Logger.info("Renamed Classes: ${result.stats.renamedClasses}")
            Logger.info("Duration: ${result.stats.durationMs / 1000}s")
            Logger.info("Results saved to: ${outputDir.absolutePath}")
            Logger.info("Dashboard: ${File(outputDir, "dashboard.html").absolutePath}")
        } else {
            Logger.error("AI Deobfuscation failed: ${result.error}")
        }
    }

    private fun findSourceDir(path: Path): Path? {
        // Check if this is directly a source directory
        val javaDir = path.resolve("app/src/main/java")
        if (javaDir.toFile().exists()) {
            return javaDir
        }

        // Check if path itself is the source directory
        if (path.toFile().isDirectory) {
            // Check if this directory or immediate subdirectories contain Java files
            val hasJavaFiles = path.toFile().walkTopDown()
                .maxDepth(3)  // Look a few levels deep
                .any { it.extension == "java" }

            if (hasJavaFiles) {
                // Return the input path itself as the source root
                return path
            }
        }

        return null
    }
}
