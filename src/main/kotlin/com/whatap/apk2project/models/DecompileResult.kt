package com.whatap.apk2project.models

import java.io.File

sealed class DecompileResult {
    data class Success(
        val sourceDir: File,
        val resourceDir: File,
        val stats: DecompileStats
    ) : DecompileResult()

    data class PartialSuccess(
        val sourceDir: File,
        val resourceDir: File,
        val stats: DecompileStats,
        val errors: List<DecompileError>
    ) : DecompileResult()

    data class Failure(
        val error: String,
        val cause: Throwable? = null
    ) : DecompileResult()
}

data class DecompileStats(
    val totalClasses: Int,
    val successfulClasses: Int,
    val failedClasses: Int,
    val totalMethods: Int,
    val obfuscatedClasses: Int,
    val durationMs: Long
) {
    val successRate: Float
        get() = if (totalClasses > 0) successfulClasses.toFloat() / totalClasses else 0f
}

data class DecompileError(
    val className: String,
    val errorType: DecompileErrorType,
    val message: String
)

enum class DecompileErrorType {
    PARSING_ERROR,
    DECOMPILATION_ERROR,
    IO_ERROR,
    TIMEOUT,
    UNKNOWN
}

sealed class AnalysisResult {
    data class Success(
        val dependencies: List<Dependency>,
        val stats: AnalysisStats
    ) : AnalysisResult()

    data class Failure(
        val error: String,
        val cause: Throwable? = null
    ) : AnalysisResult()
}

data class AnalysisStats(
    val scannedPackages: Int,
    val detectedLibraries: Int,
    val highConfidenceLibraries: Int,
    val unknownPackages: Int,
    val isObfuscated: Boolean,
    val obfuscationLevel: ObfuscationLevel,
    val durationMs: Long
)

enum class ObfuscationLevel {
    NONE,
    LIGHT,      // Only class names obfuscated
    MODERATE,   // Class + method names
    HEAVY       // Class + method + field names, string encryption
}

sealed class VerificationResult {
    data class Success(
        val steps: List<VerificationStep>,
        val buildable: Boolean
    ) : VerificationResult()

    data class Failure(
        val error: String,
        val steps: List<VerificationStep>
    ) : VerificationResult()
}

data class VerificationStep(
    val name: String,
    val passed: Boolean,
    val message: String,
    val details: String? = null,
    val suggestions: List<String> = emptyList()
)
