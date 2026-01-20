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
    ).default("deepseek-coder:6.7b")  // 로컬 Ollama 6.7B 모델 (빠름)

    private val batchSize by option(
        "--batch-size",
        help = "Number of parallel workers"
    ).int().default(30)  // 로컬 Ollama: 30개 스레드 (6.7B 모델 빠름)

    private val enableKorean by option(
        "--korean",
        help = "Enable Korean translation for class/method names"
    ).flag(default = false)  // 한글 번역 기본 비활성화 (--korean 명시적 사용 필요)

    private val translationModel by option(
        "--translation-model",
        help = "Translation model name"
    ).default("qwen2.5:latest")  // 쿠버네티스 Qwen2.5 모델

    private val ollamaBaseUrl by option(
        "--ollama-base-url",
        help = "Ollama server URL (default: http://localhost:11434). Use this to connect to remote Ollama server."
    ).default("http://localhost:11434")  // 로컬 Ollama 서버

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

            // Update output.properties for dashboard
            updateOutputProperties(sourceDir.toFile())

            try {
                monitor = com.whatap.apk2project.deobfuscator.monitor.ProgressMonitor(outputDir)
                monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.INITIALIZING
                monitor.phase = "Initializing"
                monitor.status = "Starting pipeline..."
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
            config = config,
            externalMonitor = monitor
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

    /**
     * Update output.properties for Dashboard API
     */
    private fun updateOutputProperties(sourceDir: File) {
        try {
            val projectRoot = File(System.getProperty("user.dir")).absoluteFile
            val propertiesFile = projectRoot.resolve("output.properties")

            // Read existing properties
            val existingContent = if (propertiesFile.exists()) {
                propertiesFile.readText()
            } else {
                "# APK2Project Configuration\n# This file contains project-specific settings (do not commit to Git)\n\n"
            }

            // Calculate relative path safely
            val relativePath = try {
                sourceDir.absoluteFile.relativeTo(projectRoot).path
            } catch (e: Exception) {
                // If relative path fails, use absolute path
                sourceDir.absolutePath
            }

            val newContent = existingContent.replace(
                Regex("^output\\.dir=.*$", RegexOption.MULTILINE),
                "output.dir=$relativePath"
            )

            // Add line if not exists
            val finalContent = if (!existingContent.contains("output.dir=")) {
                newContent + "\noutput.dir=$relativePath\n"
            } else {
                newContent
            }

            propertiesFile.writeText(finalContent)
            Logger.info("Updated output.properties: output.dir=$relativePath")
        } catch (e: Exception) {
            Logger.warn("Failed to update output.properties: ${e.message}")
        }
    }
}
