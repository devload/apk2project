package com.whatap.apk2project.deobfuscator.ai

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.whatap.apk2project.deobfuscator.models.*

/**
 * Parses and validates Claude API responses for deobfuscation
 */
class ResponseParser {
    private val gson = Gson()

    companion object {
        // Valid Java identifier pattern
        private val IDENTIFIER_PATTERN = Regex("^[a-z][a-zA-Z0-9]*$")

        // Reserved Java keywords
        private val JAVA_KEYWORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null"
        )

        // Generic/lazy names to penalize
        private val GENERIC_NAMES = setOf(
            "do", "doIt", "process", "processIt", "handle", "handleIt",
            "run", "execute", "perform", "action", "method", "function",
            "data", "value", "result", "temp", "tmp", "foo", "bar"
        )

        private const val MAX_NAME_LENGTH = 40
        private const val MIN_NAME_LENGTH = 3
    }

    /**
     * Parse and validate a Claude response
     */
    fun parse(
        response: String,
        methodId: MethodId
    ): ParseResult {
        // Try to extract JSON from response (in case there's extra text)
        val jsonStr = extractJson(response)
            ?: return ParseResult.Error("No valid JSON found in response")

        return try {
            val rawResult = gson.fromJson(jsonStr, RawDeobfuscationResponse::class.java)
            val errors = validate(rawResult)

            if (errors.isEmpty()) {
                ParseResult.Success(toDeobfuscationResult(rawResult, methodId))
            } else {
                ParseResult.ValidationError(errors, rawResult)
            }
        } catch (e: JsonSyntaxException) {
            ParseResult.Error("JSON parsing failed: ${e.message}")
        }
    }

    /**
     * Parse SCC first pass response (summaries only)
     */
    fun parseSccFirstPass(response: String): Map<String, SccSummary>? {
        val jsonStr = extractJsonArray(response) ?: return null

        return try {
            val results = gson.fromJson(jsonStr, Array<SccFirstPassResponse>::class.java)
            results.associate { it.originalName to SccSummary(it.summary, it.keySignals) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse SCC second pass response (final names)
     */
    fun parseSccSecondPass(response: String): Map<String, SccNaming>? {
        val jsonStr = extractJsonArray(response) ?: return null

        return try {
            val results = gson.fromJson(jsonStr, Array<SccSecondPassResponse>::class.java)
            results.associate {
                it.originalName to SccNaming(it.suggestedName, it.confidence)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Validate a parsed response
     */
    fun validate(result: RawDeobfuscationResponse): List<String> {
        val errors = mutableListOf<String>()

        // Check name is valid Java identifier
        if (!IDENTIFIER_PATTERN.matches(result.suggestedName)) {
            errors.add("Invalid Java identifier: '${result.suggestedName}'. Must start with lowercase letter and contain only alphanumerics.")
        }

        // Check name is not a keyword
        if (result.suggestedName.lowercase() in JAVA_KEYWORDS) {
            errors.add("'${result.suggestedName}' is a Java reserved keyword")
        }

        // Check name length
        if (result.suggestedName.length > MAX_NAME_LENGTH) {
            errors.add("Name too long (${result.suggestedName.length} chars). Maximum is $MAX_NAME_LENGTH")
        }
        if (result.suggestedName.length < MIN_NAME_LENGTH) {
            errors.add("Name too short (${result.suggestedName.length} chars). Minimum is $MIN_NAME_LENGTH")
        }

        // Penalize generic names
        if (result.suggestedName.lowercase() in GENERIC_NAMES) {
            errors.add("Name '${result.suggestedName}' is too generic. Please be more specific.")
        }

        // Check confidence range
        if (result.confidence < 0f || result.confidence > 1f) {
            errors.add("Confidence must be between 0.0 and 1.0, got ${result.confidence}")
        }

        // Check summary is not empty
        if (result.oneLineSummary.isBlank()) {
            errors.add("oneLineSummary cannot be empty")
        }

        // Check summary length
        if (result.oneLineSummary.length > 200) {
            errors.add("oneLineSummary too long (${result.oneLineSummary.length} chars). Maximum is 200")
        }

        return errors
    }

    /**
     * Extract JSON object from response text
     */
    private fun extractJson(text: String): String? {
        // Try to find JSON object
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')

        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
        }
        return null
    }

    /**
     * Extract JSON array from response text
     */
    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')

        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
        }
        return null
    }

    /**
     * Convert raw response to DeobfuscationResult
     */
    private fun toDeobfuscationResult(
        raw: RawDeobfuscationResponse,
        methodId: MethodId
    ): DeobfuscationResult {
        return DeobfuscationResult(
            methodId = methodId,
            originalName = methodId.name,
            suggestedName = raw.suggestedName,
            oneLineSummary = raw.oneLineSummary,
            keySignals = raw.keySignals,
            confidence = raw.confidence,
            reasons = raw.reasons,
            warnings = raw.warnings,
            source = DeobfuscationSource.AI
        )
    }
}

/**
 * Result of parsing a response
 */
sealed class ParseResult {
    data class Success(val result: DeobfuscationResult) : ParseResult()
    data class ValidationError(
        val errors: List<String>,
        val rawResult: RawDeobfuscationResponse
    ) : ParseResult()
    data class Error(val message: String) : ParseResult()
}

/**
 * Raw response from Claude (before conversion)
 */
data class RawDeobfuscationResponse(
    val suggestedName: String = "",
    val oneLineSummary: String = "",
    val keySignals: List<String> = emptyList(),
    val confidence: Float = 0f,
    val reasons: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/**
 * SCC first pass response item
 */
private data class SccFirstPassResponse(
    val originalName: String,
    val summary: String,
    val keySignals: List<String> = emptyList()
)

/**
 * SCC second pass response item
 */
private data class SccSecondPassResponse(
    val originalName: String,
    val suggestedName: String,
    val confidence: Float
)

/**
 * SCC summary for first pass
 */
data class SccSummary(
    val summary: String,
    val keySignals: List<String>
)

/**
 * SCC naming for second pass
 */
data class SccNaming(
    val suggestedName: String,
    val confidence: Float
)
