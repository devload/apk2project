package com.whatap.apk2project.deobfuscator.context

import com.whatap.apk2project.deobfuscator.graph.CallGraph
import com.whatap.apk2project.deobfuscator.graph.LeafScorer
import com.whatap.apk2project.deobfuscator.graph.MethodNode
import com.whatap.apk2project.deobfuscator.models.*

/**
 * Builds analysis context for a method, including already-analyzed callee information
 */
class ContextBuilder(
    private val callGraph: CallGraph,
    private val contextStore: ContextStore,
    private val leafScorer: LeafScorer = LeafScorer()
) {

    /**
     * Build analysis context for a method
     */
    fun buildContext(
        node: MethodNode,
        minConfidence: EdgeConfidence = EdgeConfidence.HIGH
    ): AnalysisContext {
        val method = node.parsedMethod
        val methodId = node.id

        // Get already-analyzed callees (only HIGH confidence edges)
        val calledMethods = getAnalyzedCallees(methodId, minConfidence)

        // Get already-analyzed callers
        val callerMethods = getAnalyzedCallers(methodId)

        // Build field context from the class
        val fieldContext = buildFieldContext(methodId)

        // Build class-level context
        val classContext = buildClassContext(methodId)

        // Extract signals
        val signals = leafScorer.extractSignals(node)

        return AnalysisContext(
            method = method,
            methodId = methodId,
            calledMethods = calledMethods,
            callerMethods = callerMethods,
            fieldContext = fieldContext,
            classContext = classContext,
            signals = signals
        )
    }

    /**
     * Get already-analyzed callees with their deobfuscation results
     */
    private fun getAnalyzedCallees(
        methodId: MethodId,
        minConfidence: EdgeConfidence
    ): Map<MethodId, DeobfuscationResult> {
        val callees = callGraph.getCalleesWithConfidence(methodId, minConfidence)

        return callees.mapNotNull { (calleeId, _) ->
            contextStore.getResult(calleeId)?.let { result ->
                calleeId to result
            }
        }.toMap()
    }

    /**
     * Get already-analyzed callers with their deobfuscation results
     */
    private fun getAnalyzedCallers(methodId: MethodId): Map<MethodId, DeobfuscationResult> {
        val callerKeys = callGraph.getCallers(methodId)

        return callerKeys.mapNotNull { callerKey ->
            val callerNode = callGraph.getMethodNode(callerKey)
            callerNode?.let { node ->
                contextStore.getResult(node.id)?.let { result ->
                    node.id to result
                }
            }
        }.toMap()
    }

    /**
     * Build field context from the owning class
     */
    private fun buildFieldContext(methodId: MethodId): Map<String, String> {
        val node = callGraph.getMethodNode(methodId) ?: return emptyMap()

        // Get field accesses from the method
        val fieldAccesses = node.parsedMethod.fieldAccesses

        // Map field names to deobfuscated names if available
        return fieldAccesses.associateWith { fieldName ->
            // For now, just return the type information
            // In a more sophisticated implementation, this would include
            // deobfuscated field names from class analysis
            fieldName
        }
    }

    /**
     * Build class-level context description
     */
    private fun buildClassContext(methodId: MethodId): String {
        val parts = mutableListOf<String>()
        val className = methodId.ownerFqcn.substringAfterLast(".")

        // Check for Android component patterns
        when {
            className.endsWith("Activity") -> parts.add("Android Activity component")
            className.endsWith("Fragment") -> parts.add("Android Fragment component")
            className.endsWith("Service") -> parts.add("Android Service component")
            className.endsWith("Receiver") -> parts.add("Android BroadcastReceiver component")
            className.endsWith("Provider") -> parts.add("Android ContentProvider component")
            className.endsWith("Adapter") -> parts.add("Android Adapter for list/recycler views")
            className.endsWith("ViewModel") -> parts.add("Android ViewModel for MVVM pattern")
            className.endsWith("Repository") -> parts.add("Repository pattern for data access")
            className.endsWith("Manager") -> parts.add("Manager class for coordination")
            className.endsWith("Helper") || className.endsWith("Utils") ->
                parts.add("Utility/Helper class")
            className.endsWith("Client") -> parts.add("Client class for external communication")
            className.endsWith("Handler") -> parts.add("Handler class for message/event processing")
        }

        // Check package name for hints
        val packageName = methodId.ownerFqcn.substringBeforeLast(".")
        when {
            packageName.contains(".network") || packageName.contains(".api") ->
                parts.add("Located in network/API package")
            packageName.contains(".data") || packageName.contains(".model") ->
                parts.add("Located in data/model package")
            packageName.contains(".ui") || packageName.contains(".view") ->
                parts.add("Located in UI/view package")
            packageName.contains(".util") -> parts.add("Located in utility package")
            packageName.contains(".service") -> parts.add("Located in service package")
        }

        return parts.joinToString(". ")
    }

    /**
     * Format context for prompt inclusion
     */
    fun formatContextForPrompt(context: AnalysisContext, redactMode: RedactMode): String {
        val sb = StringBuilder()

        // Class context
        if (context.classContext.isNotEmpty()) {
            sb.appendLine("Class context: ${context.classContext}")
        }

        // Already analyzed callees
        if (context.calledMethods.isNotEmpty()) {
            sb.appendLine("\nAlready analyzed methods this calls:")
            context.calledMethods.entries.take(10).forEach { (id, result) ->
                sb.appendLine("  - ${result.suggestedName}(): ${result.oneLineSummary}")
            }
        }

        // Signals
        val signals = context.signals
        if (signals.externalApiCalls.isNotEmpty()) {
            sb.appendLine("\nExternal API calls: ${signals.externalApiCalls.joinToString(", ")}")
        }
        if (signals.androidFrameworkTypes.isNotEmpty()) {
            sb.appendLine("Android types used: ${signals.androidFrameworkTypes.joinToString(", ")}")
        }
        if (signals.sqlPatterns.isNotEmpty()) {
            val patterns = redactStrings(signals.sqlPatterns, redactMode)
            sb.appendLine("SQL patterns: ${patterns.joinToString(", ")}")
        }
        if (signals.urlPatterns.isNotEmpty()) {
            val patterns = redactStrings(signals.urlPatterns, redactMode)
            sb.appendLine("URL patterns: ${patterns.joinToString(", ")}")
        }
        if (signals.isLifecycleMethod) {
            sb.appendLine("This is an Android lifecycle method")
        }

        // String literals (redacted if needed)
        if (signals.stringLiterals.isNotEmpty()) {
            val strings = redactStrings(signals.stringLiterals.take(10), redactMode)
            sb.appendLine("\nString literals in method: ${strings.joinToString(", ") { "\"$it\"" }}")
        }

        return sb.toString()
    }

    /**
     * Redact sensitive strings based on mode
     */
    private fun redactStrings(strings: List<String>, mode: RedactMode): List<String> {
        return when (mode) {
            RedactMode.OFF -> strings
            RedactMode.SOFT -> strings.map { softRedact(it) }
            RedactMode.HARD -> strings.map { "[REDACTED]" }
        }
    }

    /**
     * Soft redaction: preserve patterns, remove sensitive data
     */
    private fun softRedact(str: String): String {
        return when {
            // URL: keep path, remove domain
            str.matches(Regex("https?://[^/]+(/.*)?")) -> {
                str.replace(Regex("https?://[^/]+"), "")
                    .ifEmpty { "/..." }
            }
            // Email: pattern only
            str.contains("@") && str.contains(".") -> "[email]"
            // API key patterns
            str.matches(Regex("(sk-|api[-_]?key|token).*", RegexOption.IGNORE_CASE)) ->
                "[api_key]"
            // Preserve SQL keywords
            str.contains(Regex("SELECT|INSERT|UPDATE|DELETE", RegexOption.IGNORE_CASE)) ->
                str.replace(Regex("'[^']*'"), "'...'")
            // Default: truncate long strings
            str.length > 20 -> str.take(10) + "..."
            else -> str
        }
    }

    /**
     * Calculate hash of analyzed callee names for cache key
     */
    fun calculateCalleeHash(methodId: MethodId, minConfidence: EdgeConfidence): String {
        val callees = callGraph.getCalleesWithConfidence(methodId, minConfidence)
        val names = callees.mapNotNull { (id, _) ->
            contextStore.getDeobfuscatedName(id)
        }.sorted()

        return if (names.isEmpty()) {
            "none"
        } else {
            names.joinToString("|").hashCode().toString(16)
        }
    }
}
