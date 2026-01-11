package com.whatap.apk2project.deobfuscator.models

import java.io.File

/**
 * Unique identifier for a method, structured to handle overloading, inner classes, and synthetic methods
 */
data class MethodId(
    val ownerFqcn: String,
    val name: String,
    val desc: String? = null,
    val sourcePath: String? = null,
    val range: IntRange? = null,
    val isStatic: Boolean = false,
    val isSynthetic: Boolean = false
) {
    val qualifiedName: String get() = "$ownerFqcn.$name"

    fun toShortString(): String = "$ownerFqcn.$name${desc?.let { "($it)" } ?: ""}"

    companion object {
        fun fromSignature(ownerFqcn: String, signature: String): MethodId {
            val name = signature.substringBefore("(")
            val desc = if ("(" in signature) signature.substringAfter("(").substringBefore(")") else null
            return MethodId(ownerFqcn, name, desc)
        }
    }
}

/**
 * Edge confidence level for call graph edges
 */
enum class EdgeConfidence {
    HIGH,    // Static call, this.foo() with signature matched
    MEDIUM,  // Interface call, virtual call
    LOW      // Chaining, overloading guess, Kotlin synthetic
}

/**
 * Represents a method call edge in the call graph
 */
data class MethodCall(
    val caller: MethodId,
    val callee: MethodId,
    val confidence: EdgeConfidence,
    val lineNumber: Int
)

/**
 * Redaction mode for sensitive strings
 */
enum class RedactMode {
    OFF,    // Keep strings as-is
    SOFT,   // Preserve patterns (https://api.foo.com/user → /user)
    HARD    // Replace all with [REDACTED]
}

/**
 * Result of deobfuscating a single method
 */
data class DeobfuscationResult(
    val methodId: MethodId,
    val originalName: String,
    val suggestedName: String,
    val oneLineSummary: String,
    val keySignals: List<String>,
    val confidence: Float,
    val reasons: List<String>,
    val warnings: List<String> = emptyList(),
    val source: DeobfuscationSource = DeobfuscationSource.AI
)

/**
 * Source of deobfuscation suggestion
 */
enum class DeobfuscationSource {
    HEURISTIC,  // Rule-based
    AI,         // Claude API
    CACHED      // From cache
}

/**
 * Analysis report for dry-run output
 */
data class AnalysisReport(
    val mappings: Map<MethodId, DeobfuscationResult>,
    val stats: AnalysisStats,
    val sampleResults: List<DeobfuscationResult>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Statistics for the analysis
 */
data class AnalysisStats(
    val totalMethods: Int,
    val obfuscatedMethods: Int,
    val analyzedMethods: Int,
    val skippedMethods: Int,
    val successRate: Float,
    val retryCount: Int,
    val confidenceDistribution: Map<EdgeConfidence, Int>,
    val topSkipReasons: List<String>,
    val apiCalls: Int,
    val estimatedCost: Double,
    val durationMs: Long
)

/**
 * Cache key for API response caching
 */
data class CacheKey(
    val methodId: MethodId,
    val calleeDeobfuscatedSetHash: String,
    val redactMode: RedactMode,
    val promptVersion: String = "v1"
) {
    fun toKeyString(): String = "${methodId.toShortString()}|$calleeDeobfuscatedSetHash|$redactMode|$promptVersion"
}

/**
 * Configuration for AI deobfuscation
 */
data class DeobfuscationConfig(
    val apiKey: String,
    val budget: Double = 1.0,
    val maxCalls: Int = 200,
    val maxCostPerMethod: Double = 0.01,
    val redactMode: RedactMode = RedactMode.OFF,
    val minGraphConfidence: EdgeConfidence = EdgeConfidence.HIGH,
    val dryRun: Boolean = true,
    val applyStrict: Boolean = true,
    val stopOnError: Boolean = false
)

/**
 * Parsed class information from AST
 */
data class ParsedClass(
    val packageName: String,
    val className: String,
    val fullyQualifiedName: String,
    val superClass: String?,
    val interfaces: List<String>,
    val methods: List<ParsedMethod>,
    val fields: List<ParsedField>,
    val sourceFile: File,
    val isObfuscated: Boolean
)

/**
 * Parsed method information from AST
 */
data class ParsedMethod(
    val name: String,
    val signature: String,
    val returnType: String,
    val parameters: List<ParsedParameter>,
    val methodCalls: List<MethodCallInfo>,
    val fieldAccesses: List<String>,
    val stringLiterals: List<String>,
    val annotations: List<String>,
    val lineStart: Int,
    val lineEnd: Int,
    val bodySource: String,
    val isStatic: Boolean = false,
    val isSynthetic: Boolean = false,
    val isObfuscated: Boolean = false
) {
    val bodyTokenCount: Int get() = bodySource.split(Regex("\\s+")).size
}

/**
 * Parsed field information
 */
data class ParsedField(
    val name: String,
    val type: String,
    val modifiers: List<String>,
    val isObfuscated: Boolean
)

/**
 * Parsed parameter information
 */
data class ParsedParameter(
    val name: String,
    val type: String
)

/**
 * Method call information extracted from AST
 */
data class MethodCallInfo(
    val targetClass: String?,
    val methodName: String,
    val argumentTypes: List<String>,
    val lineNumber: Int,
    val isStatic: Boolean = false
)

/**
 * Signals extracted from method for heuristic analysis
 */
data class MethodSignals(
    val externalApiCalls: List<String>,
    val stringLiterals: List<String>,
    val androidFrameworkTypes: List<String>,
    val sqlPatterns: List<String>,
    val urlPatterns: List<String>,
    val sharedPrefsKeys: List<String>,
    val annotations: List<String>,
    val isLifecycleMethod: Boolean = false
)

/**
 * Context for analyzing a method (includes already-analyzed callees)
 */
data class AnalysisContext(
    val method: ParsedMethod,
    val methodId: MethodId,
    val calledMethods: Map<MethodId, DeobfuscationResult>,
    val callerMethods: Map<MethodId, DeobfuscationResult>,
    val fieldContext: Map<String, String>,
    val classContext: String,
    val signals: MethodSignals
)
