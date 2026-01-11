package com.whatap.apk2project.deobfuscator.analysis

import com.whatap.apk2project.deobfuscator.graph.LeafScorer
import com.whatap.apk2project.deobfuscator.graph.MethodNode
import com.whatap.apk2project.deobfuscator.models.*

/**
 * Rule-based heuristic analyzer for initial signal extraction and simple cases
 * Focuses on generating summaries and keySignals, not final names
 */
class HeuristicAnalyzer(
    private val leafScorer: LeafScorer = LeafScorer()
) {

    companion object {
        // Pattern matchers for common functionality
        private val HTTP_PATTERNS = listOf(
            Regex(".*[Hh]ttp.*"),
            Regex(".*[Rr]equest.*"),
            Regex(".*[Rr]esponse.*"),
            Regex(".*[Aa]pi.*"),
            Regex(".*[Nn]etwork.*")
        )

        private val DATABASE_PATTERNS = listOf(
            Regex(".*[Dd]atabase.*"),
            Regex(".*[Ss]ql.*"),
            Regex(".*[Cc]ursor.*"),
            Regex(".*[Qq]uery.*")
        )

        private val PREFERENCE_PATTERNS = listOf(
            Regex(".*[Pp]reference.*"),
            Regex(".*[Ss]hared.*"),
            Regex(".*[Ss]ettings.*")
        )

        private val FILE_PATTERNS = listOf(
            Regex(".*[Ff]ile.*"),
            Regex(".*[Ss]tream.*"),
            Regex(".*[Rr]ead.*"),
            Regex(".*[Ww]rite.*")
        )

        private val UI_PATTERNS = listOf(
            Regex(".*[Vv]iew.*"),
            Regex(".*[Aa]dapter.*"),
            Regex(".*[Ll]ayout.*"),
            Regex(".*[Dd]ialog.*")
        )

        // Method name patterns that indicate specific behavior
        private val GETTER_PATTERN = Regex("^get[A-Z].*")
        private val SETTER_PATTERN = Regex("^set[A-Z].*")
        private val BOOLEAN_GETTER_PATTERN = Regex("^(is|has|can|should)[A-Z].*")
        private val CALLBACK_PATTERN = Regex("^on[A-Z].*")
        private val FACTORY_PATTERN = Regex("^(create|new|make|build)[A-Z].*")
        private val INIT_PATTERN = Regex("^(init|setup|configure)[A-Z]?.*")
    }

    /**
     * Analyze a method and generate heuristic signals
     * Does NOT generate final names - that's for AI
     */
    fun analyze(node: MethodNode): HeuristicResult {
        val method = node.parsedMethod
        val signals = leafScorer.extractSignals(node)

        val summary = generateSummary(method, signals)
        val keySignals = extractKeySignals(method, signals)
        val category = categorize(method, signals)
        val confidence = calculateConfidence(method, signals)

        return HeuristicResult(
            methodId = node.id,
            summary = summary,
            keySignals = keySignals,
            category = category,
            confidence = confidence,
            shouldSkipAi = shouldSkipAiAnalysis(node, signals)
        )
    }

    /**
     * Generate a summary based on heuristics
     */
    private fun generateSummary(method: ParsedMethod, signals: MethodSignals): String {
        val parts = mutableListOf<String>()

        // Check return type for hints
        when (method.returnType) {
            "void" -> {}  // No return hint
            "boolean" -> parts.add("Returns boolean result")
            "String" -> parts.add("Returns string")
            "int", "long" -> parts.add("Returns numeric value")
            else -> if (method.returnType.endsWith("List") || method.returnType.endsWith("[]")) {
                parts.add("Returns collection")
            }
        }

        // Check signals for functionality
        if (signals.sqlPatterns.isNotEmpty()) {
            parts.add("Performs database operation")
        }
        if (signals.urlPatterns.isNotEmpty()) {
            parts.add("Makes network request")
        }
        if (signals.sharedPrefsKeys.isNotEmpty()) {
            parts.add("Accesses preferences")
        }
        if (signals.isLifecycleMethod) {
            parts.add("Android lifecycle callback")
        }

        // Check method calls for patterns
        val callsStr = method.methodCalls.map { it.methodName }
        if (callsStr.any { it.contains("execute") || it.contains("enqueue") }) {
            parts.add("Executes async operation")
        }
        if (callsStr.any { it.contains("parse") }) {
            parts.add("Parses data")
        }
        if (callsStr.any { it.contains("Log") || it.contains("log") }) {
            parts.add("Contains logging")
        }

        return if (parts.isNotEmpty()) {
            parts.joinToString(". ")
        } else {
            "Method functionality unclear from static analysis"
        }
    }

    /**
     * Extract key signals as strings for context
     */
    private fun extractKeySignals(method: ParsedMethod, signals: MethodSignals): List<String> {
        val keySignals = mutableListOf<String>()

        // Android framework types
        signals.androidFrameworkTypes.forEach { type ->
            keySignals.add("Uses $type")
        }

        // External API patterns
        signals.externalApiCalls.take(3).forEach { call ->
            keySignals.add("Calls $call")
        }

        // Important string patterns (anonymized)
        signals.sqlPatterns.take(2).forEach { _ ->
            keySignals.add("Contains SQL query")
        }
        signals.urlPatterns.take(2).forEach { _ ->
            keySignals.add("Contains URL/API endpoint")
        }

        // Annotations
        method.annotations.forEach { annotation ->
            keySignals.add("@$annotation")
        }

        return keySignals.distinct().take(10)
    }

    /**
     * Categorize the method based on patterns
     */
    private fun categorize(method: ParsedMethod, signals: MethodSignals): MethodCategory {
        val name = method.name
        val bodyStr = method.bodySource + method.methodCalls.joinToString { it.methodName }

        return when {
            // Check method name patterns first
            GETTER_PATTERN.matches(name) -> MethodCategory.GETTER
            SETTER_PATTERN.matches(name) -> MethodCategory.SETTER
            BOOLEAN_GETTER_PATTERN.matches(name) -> MethodCategory.BOOLEAN_GETTER
            CALLBACK_PATTERN.matches(name) -> MethodCategory.CALLBACK
            FACTORY_PATTERN.matches(name) -> MethodCategory.FACTORY
            INIT_PATTERN.matches(name) -> MethodCategory.INITIALIZER

            // Check functionality patterns
            HTTP_PATTERNS.any { it.containsMatchIn(bodyStr) } -> MethodCategory.NETWORK
            DATABASE_PATTERNS.any { it.containsMatchIn(bodyStr) } -> MethodCategory.DATABASE
            PREFERENCE_PATTERNS.any { it.containsMatchIn(bodyStr) } -> MethodCategory.PREFERENCES
            FILE_PATTERNS.any { it.containsMatchIn(bodyStr) } -> MethodCategory.FILE_IO
            UI_PATTERNS.any { it.containsMatchIn(bodyStr) } -> MethodCategory.UI

            // Check signals
            signals.isLifecycleMethod -> MethodCategory.LIFECYCLE
            signals.sqlPatterns.isNotEmpty() -> MethodCategory.DATABASE
            signals.urlPatterns.isNotEmpty() -> MethodCategory.NETWORK

            else -> MethodCategory.UNKNOWN
        }
    }

    /**
     * Calculate confidence based on available signals
     */
    private fun calculateConfidence(method: ParsedMethod, signals: MethodSignals): Float {
        var score = 0f

        // More signals = more confidence
        if (signals.externalApiCalls.isNotEmpty()) score += 0.2f
        if (signals.stringLiterals.isNotEmpty()) score += 0.1f
        if (signals.sqlPatterns.isNotEmpty()) score += 0.15f
        if (signals.urlPatterns.isNotEmpty()) score += 0.15f
        if (signals.androidFrameworkTypes.isNotEmpty()) score += 0.1f
        if (signals.annotations.isNotEmpty()) score += 0.1f
        if (signals.isLifecycleMethod) score += 0.2f

        // Method characteristics
        if (method.bodySource.length > 100) score += 0.1f  // Substantial body

        return score.coerceIn(0f, 1f)
    }

    /**
     * Determine if AI analysis can be skipped for this method
     */
    private fun shouldSkipAiAnalysis(node: MethodNode, signals: MethodSignals): Boolean {
        // Skip if leaf scorer says skip
        if (leafScorer.shouldSkip(node)) return true

        // Skip trivial getters/setters
        if (node.parsedMethod.bodySource.length < 30 &&
            (GETTER_PATTERN.matches(node.parsedMethod.name) ||
             SETTER_PATTERN.matches(node.parsedMethod.name))) {
            return true
        }

        return false
    }

    /**
     * Batch analyze multiple methods
     */
    fun analyzeBatch(nodes: List<MethodNode>): List<HeuristicResult> {
        return nodes.map { analyze(it) }
    }
}

/**
 * Result of heuristic analysis
 */
data class HeuristicResult(
    val methodId: MethodId,
    val summary: String,
    val keySignals: List<String>,
    val category: MethodCategory,
    val confidence: Float,
    val shouldSkipAi: Boolean
)

/**
 * Method category based on heuristic analysis
 */
enum class MethodCategory {
    NETWORK,
    DATABASE,
    PREFERENCES,
    FILE_IO,
    UI,
    LIFECYCLE,
    GETTER,
    SETTER,
    BOOLEAN_GETTER,
    CALLBACK,
    FACTORY,
    INITIALIZER,
    UNKNOWN
}
