package com.whatap.apk2project.deobfuscator

import com.whatap.apk2project.deobfuscator.ai.*
import com.whatap.apk2project.deobfuscator.analysis.*
import com.whatap.apk2project.deobfuscator.ast.JavaAstParser
import com.whatap.apk2project.deobfuscator.context.*
import com.whatap.apk2project.deobfuscator.graph.*
import com.whatap.apk2project.deobfuscator.models.*
import com.whatap.apk2project.utils.Logger
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Orchestrates the entire deobfuscation workflow
 */
class DeobfuscationOrchestrator(
    private val config: DeobfuscationConfig
) {
    private val parser = JavaAstParser()
    private val callGraphBuilder = CallGraphBuilder(parser)
    private val leafScorer = LeafScorer()
    private val heuristicAnalyzer = HeuristicAnalyzer(leafScorer)
    private val contextStore = ContextStore()

    private lateinit var callGraph: CallGraph
    private lateinit var contextBuilder: ContextBuilder
    private lateinit var promptBuilder: PromptBuilder
    private lateinit var claudeClient: ClaudeClient
    private lateinit var responseParser: ResponseParser
    private lateinit var scheduler: BottomUpScheduler

    // Statistics tracking
    private var apiCallCount = 0
    private var totalCost = 0.0
    private var retryCount = 0
    private val skipReasons = mutableMapOf<String, Int>()

    /**
     * Run analysis on a source directory (dry-run mode)
     */
    fun analyze(sourceDir: File): AnalysisReport {
        Logger.warn("AI deobfuscation is intended for authorized reverse engineering only.")
        Logger.info("Starting AI-based deobfuscation analysis...")

        val startTime = System.currentTimeMillis()

        // Phase 1: Build call graph
        Logger.info("Phase 1: Building call graph...")
        callGraph = callGraphBuilder.buildFromDirectory(sourceDir)

        // Initialize components
        contextBuilder = ContextBuilder(callGraph, contextStore, leafScorer)
        promptBuilder = PromptBuilder(contextBuilder, config.redactMode)
        claudeClient = ClaudeClient(config.apiKey)
        responseParser = ResponseParser()
        scheduler = BottomUpScheduler(callGraph, leafScorer)

        // Phase 2: Get analysis order
        Logger.info("Phase 2: Planning analysis order...")
        val analysisOrder = scheduler.getAnalysisOrder()

        Logger.info("Found ${analysisOrder.leafNodes.size} leaf nodes")
        Logger.info("Found ${analysisOrder.sccs.size} strongly connected components")

        // Phase 3: Analyze leaf nodes first (bottom-up)
        Logger.info("Phase 3: Analyzing leaf nodes...")
        analyzeNodes(analysisOrder.leafNodes)

        // Phase 4: Process remaining nodes in dependency order
        Logger.info("Phase 4: Processing remaining methods...")
        processRemainingMethods()

        // Phase 5: Generate report
        val duration = System.currentTimeMillis() - startTime
        return generateReport(duration)
    }

    /**
     * Analyze a batch of nodes
     */
    private fun analyzeNodes(nodes: List<MethodNode>) {
        for (node in nodes) {
            if (shouldStop()) {
                Logger.warn("Stopping: budget or call limit reached")
                break
            }

            try {
                analyzeNode(node)
            } catch (e: Exception) {
                Logger.error("Failed to analyze ${node.id.toShortString()}: ${e.message}")
                if (config.stopOnError) throw e
            }
        }
    }

    /**
     * Analyze a single node
     */
    private fun analyzeNode(node: MethodNode) {
        val methodId = node.id

        // Skip if already analyzed
        if (contextStore.isAnalyzed(methodId)) return

        // Run heuristic analysis first
        val heuristicResult = heuristicAnalyzer.analyze(node)

        // Skip if heuristics say to skip AI
        if (heuristicResult.shouldSkipAi) {
            recordSkip("Heuristic skip", methodId)
            scheduler.markAnalyzed(methodId)
            return
        }

        // Build context with already-analyzed callees
        val context = contextBuilder.buildContext(node, config.minGraphConfidence)

        // Build prompt
        val prompt = promptBuilder.buildPrompt(context)

        // Estimate cost and check budget
        val estimatedCost = claudeClient.estimateCost(prompt.length)
        if (totalCost + estimatedCost > config.budget) {
            recordSkip("Budget exceeded", methodId)
            return
        }
        if (apiCallCount >= config.maxCalls) {
            recordSkip("Call limit exceeded", methodId)
            return
        }

        // Call Claude API
        val result = runBlocking {
            callClaudeWithRetry(prompt, methodId)
        }

        if (result != null) {
            contextStore.store(result)
            scheduler.markAnalyzed(methodId)

            if (config.dryRun) {
                Logger.info("  ${methodId.name} -> ${result.suggestedName} (${result.confidence})")
            }
        }
    }

    /**
     * Call Claude API with retry logic
     */
    private suspend fun callClaudeWithRetry(
        prompt: String,
        methodId: MethodId,
        maxRetries: Int = 2
    ): DeobfuscationResult? {
        var currentPrompt = prompt
        var lastError: String? = null

        for (attempt in 0..maxRetries) {
            try {
                apiCallCount++
                val response = claudeClient.analyze(currentPrompt)
                totalCost += response.estimatedCost

                when (val parseResult = responseParser.parse(response.content, methodId)) {
                    is ParseResult.Success -> return parseResult.result

                    is ParseResult.ValidationError -> {
                        if (attempt < maxRetries) {
                            retryCount++
                            currentPrompt = promptBuilder.buildRetryPrompt(
                                prompt,
                                response.content,
                                parseResult.errors
                            )
                            lastError = parseResult.errors.joinToString(", ")
                            Logger.debug("Validation error, retrying: $lastError")
                        } else {
                            Logger.warn("Validation failed after retries for ${methodId.name}: $lastError")
                        }
                    }

                    is ParseResult.Error -> {
                        if (attempt < maxRetries) {
                            retryCount++
                            lastError = parseResult.message
                            Logger.debug("Parse error, retrying: $lastError")
                        } else {
                            Logger.warn("Parse failed after retries for ${methodId.name}: ${parseResult.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.error("API error for ${methodId.name}: ${e.message}")
                if (config.stopOnError) throw e
                return null
            }
        }

        return null
    }

    /**
     * Process remaining methods after leaf nodes
     */
    private fun processRemainingMethods() {
        var iterations = 0
        val maxIterations = 100  // Safety limit

        while (!scheduler.isComplete() && iterations < maxIterations && !shouldStop()) {
            val batch = scheduler.getNextBatch(10)

            if (batch.isEmpty()) {
                // Check for blocked methods
                val blocked = scheduler.getBlockedMethods()
                if (blocked.isNotEmpty()) {
                    Logger.debug("${blocked.size} methods blocked on dependencies")
                    // Try to process SCCs if regular methods are blocked
                    break
                }
                break
            }

            analyzeNodes(batch)
            iterations++

            val progress = scheduler.getProgress()
            Logger.info("Progress: ${progress.progressPercent.toInt()}% (${progress.analyzedMethods}/${progress.totalMethods})")
        }
    }

    /**
     * Check if we should stop (budget or call limit)
     */
    private fun shouldStop(): Boolean {
        return totalCost >= config.budget || apiCallCount >= config.maxCalls
    }

    /**
     * Record why a method was skipped
     */
    private fun recordSkip(reason: String, methodId: MethodId) {
        skipReasons[reason] = (skipReasons[reason] ?: 0) + 1
        Logger.debug("Skipped ${methodId.name}: $reason")
    }

    /**
     * Generate the final analysis report
     */
    private fun generateReport(durationMs: Long): AnalysisReport {
        val allResults = contextStore.getAllResults()
        val stats = callGraph.getStats()

        val analysisStats = AnalysisStats(
            totalMethods = stats.totalMethods,
            obfuscatedMethods = stats.obfuscatedMethods,
            analyzedMethods = allResults.size,
            skippedMethods = stats.obfuscatedMethods - allResults.size,
            successRate = if (apiCallCount > 0) allResults.size.toFloat() / apiCallCount else 0f,
            retryCount = retryCount,
            confidenceDistribution = stats.edgesByConfidence,
            topSkipReasons = skipReasons.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { "${it.key}: ${it.value}" },
            apiCalls = apiCallCount,
            estimatedCost = totalCost,
            durationMs = durationMs
        )

        // Get sample results (top 30 by confidence)
        val sampleResults = allResults.values
            .sortedByDescending { it.confidence }
            .take(30)

        return AnalysisReport(
            mappings = allResults,
            stats = analysisStats,
            sampleResults = sampleResults.toList()
        )
    }

    /**
     * Export results to files
     */
    fun exportResults(report: AnalysisReport, outputDir: File) {
        outputDir.mkdirs()

        // Export mappings
        val mappingsFile = File(outputDir, "deobfuscation-map.json")
        contextStore.exportToJson(mappingsFile)
        Logger.info("Mappings exported to: ${mappingsFile.absolutePath}")

        // Export report
        val reportFile = File(outputDir, "deobfuscation-report.md")
        reportFile.writeText(generateMarkdownReport(report))
        Logger.info("Report exported to: ${reportFile.absolutePath}")
    }

    /**
     * Generate markdown report
     */
    private fun generateMarkdownReport(report: AnalysisReport): String {
        val sb = StringBuilder()

        sb.appendLine("# Deobfuscation Analysis Report")
        sb.appendLine()
        sb.appendLine("Generated: ${java.time.Instant.now()}")
        sb.appendLine()

        sb.appendLine("## Summary")
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|--------|-------|")
        sb.appendLine("| Total Methods | ${report.stats.totalMethods} |")
        sb.appendLine("| Obfuscated Methods | ${report.stats.obfuscatedMethods} |")
        sb.appendLine("| Analyzed Methods | ${report.stats.analyzedMethods} |")
        sb.appendLine("| Skipped Methods | ${report.stats.skippedMethods} |")
        sb.appendLine("| API Calls | ${report.stats.apiCalls} |")
        sb.appendLine("| Estimated Cost | $${String.format("%.4f", report.stats.estimatedCost)} |")
        sb.appendLine("| Duration | ${report.stats.durationMs / 1000}s |")
        sb.appendLine()

        if (report.stats.topSkipReasons.isNotEmpty()) {
            sb.appendLine("## Skip Reasons")
            sb.appendLine()
            report.stats.topSkipReasons.forEach { reason ->
                sb.appendLine("- $reason")
            }
            sb.appendLine()
        }

        sb.appendLine("## Sample Results (Top 30)")
        sb.appendLine()
        sb.appendLine("| Original | Suggested | Confidence | Summary |")
        sb.appendLine("|----------|-----------|------------|---------|")

        report.sampleResults.forEach { result ->
            sb.appendLine("| `${result.originalName}` | `${result.suggestedName}` | ${String.format("%.2f", result.confidence)} | ${result.oneLineSummary.take(50)} |")
        }

        return sb.toString()
    }
}
