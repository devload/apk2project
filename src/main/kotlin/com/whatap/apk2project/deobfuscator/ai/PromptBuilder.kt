package com.whatap.apk2project.deobfuscator.ai

import com.whatap.apk2project.deobfuscator.context.ContextBuilder
import com.whatap.apk2project.deobfuscator.models.*

/**
 * Builds prompts for Claude API deobfuscation requests
 */
class PromptBuilder(
    private val contextBuilder: ContextBuilder,
    private val redactMode: RedactMode = RedactMode.OFF
) {

    companion object {
        const val PROMPT_VERSION = "v1"

        private val SYSTEM_PROMPT = """
You are an expert Java/Android reverse engineer. Your task is to analyze obfuscated Java code and suggest meaningful, descriptive names.

IMPORTANT RULES:
1. Respond ONLY with valid JSON - no markdown, no explanations outside JSON
2. Use camelCase for method names (e.g., fetchUserData, processPayment)
3. Names should be specific and descriptive, not generic (avoid: doSomething, process, handle)
4. Base your naming on:
   - What the method actually does (its behavior)
   - What APIs it calls
   - What strings it uses
   - Its parameters and return type
   - Context from already-analyzed methods it calls
5. Confidence should be 0.0-1.0 based on how certain you are

JSON RESPONSE FORMAT:
{
  "suggestedName": "descriptiveMethodName",
  "oneLineSummary": "Brief description of what this method does",
  "keySignals": ["signal1", "signal2"],
  "confidence": 0.85,
  "reasons": ["reason1", "reason2"],
  "warnings": ["any uncertainty or assumptions"]
}
""".trimIndent()
    }

    /**
     * Build a complete prompt for method deobfuscation
     */
    fun buildPrompt(context: AnalysisContext): String {
        val sb = StringBuilder()

        // System instruction
        sb.appendLine(SYSTEM_PROMPT)
        sb.appendLine()

        // Method information
        sb.appendLine("=== METHOD TO ANALYZE ===")
        sb.appendLine("Original name: ${context.method.name}")
        sb.appendLine("Signature: ${context.method.signature}")
        sb.appendLine("Return type: ${context.method.returnType}")

        if (context.method.parameters.isNotEmpty()) {
            sb.appendLine("Parameters:")
            context.method.parameters.forEach { param ->
                sb.appendLine("  - ${param.name}: ${param.type}")
            }
        }

        if (context.method.annotations.isNotEmpty()) {
            sb.appendLine("Annotations: ${context.method.annotations.joinToString(", ")}")
        }

        // Context from already-analyzed callees
        val contextStr = contextBuilder.formatContextForPrompt(context, redactMode)
        if (contextStr.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("=== CONTEXT ===")
            sb.append(contextStr)
        }

        // Method body
        sb.appendLine()
        sb.appendLine("=== METHOD BODY ===")
        sb.appendLine("```java")
        sb.appendLine(formatMethodBody(context.method.bodySource))
        sb.appendLine("```")

        // Reminder for JSON output
        sb.appendLine()
        sb.appendLine("Analyze this method and respond with ONLY the JSON object described above.")

        return sb.toString()
    }

    /**
     * Build a retry prompt with validation errors
     */
    fun buildRetryPrompt(
        originalPrompt: String,
        previousResponse: String,
        validationErrors: List<String>
    ): String {
        val sb = StringBuilder()

        sb.appendLine(originalPrompt)
        sb.appendLine()
        sb.appendLine("=== PREVIOUS RESPONSE (INVALID) ===")
        sb.appendLine(previousResponse)
        sb.appendLine()
        sb.appendLine("=== VALIDATION ERRORS ===")
        validationErrors.forEach { error ->
            sb.appendLine("- $error")
        }
        sb.appendLine()
        sb.appendLine("Please fix the errors and respond with ONLY valid JSON.")

        return sb.toString()
    }

    /**
     * Build a prompt for SCC (Strongly Connected Component) first pass
     * This generates summaries without final names
     */
    fun buildSccFirstPassPrompt(methods: List<AnalysisContext>): String {
        val sb = StringBuilder()

        sb.appendLine("""
These methods form a circular dependency (they call each other).
First, analyze each method and provide a summary of what it does.
Do NOT suggest final names yet - just describe the behavior.

Respond with JSON array:
[
  {"originalName": "a", "summary": "what method a does", "keySignals": ["signal1"]},
  {"originalName": "b", "summary": "what method b does", "keySignals": ["signal1"]}
]
""".trimIndent())

        methods.forEach { context ->
            sb.appendLine()
            sb.appendLine("=== METHOD: ${context.method.name} ===")
            sb.appendLine("```java")
            sb.appendLine(formatMethodBody(context.method.bodySource))
            sb.appendLine("```")
        }

        return sb.toString()
    }

    /**
     * Build a prompt for SCC second pass
     * This generates final names based on summaries
     */
    fun buildSccSecondPassPrompt(
        methods: List<AnalysisContext>,
        summaries: Map<String, String>
    ): String {
        val sb = StringBuilder()

        sb.appendLine("""
Based on the summaries below, suggest final names for these methods.
The methods call each other, so names should be consistent.

Summaries:
""".trimIndent())

        summaries.forEach { (name, summary) ->
            sb.appendLine("- $name: $summary")
        }

        sb.appendLine()
        sb.appendLine("""
Respond with JSON array:
[
  {"originalName": "a", "suggestedName": "fetchData", "confidence": 0.8},
  {"originalName": "b", "suggestedName": "processResponse", "confidence": 0.7}
]
""".trimIndent())

        return sb.toString()
    }

    /**
     * Format method body for inclusion in prompt
     */
    private fun formatMethodBody(body: String): String {
        // Limit body size to avoid token limits
        val maxLength = 2000
        val trimmed = if (body.length > maxLength) {
            body.take(maxLength) + "\n// ... (truncated)"
        } else {
            body
        }

        // Apply redaction if needed
        return when (redactMode) {
            RedactMode.OFF -> trimmed
            RedactMode.SOFT -> softRedactBody(trimmed)
            RedactMode.HARD -> hardRedactBody(trimmed)
        }
    }

    private fun softRedactBody(body: String): String {
        var result = body

        // Redact URLs but keep path patterns
        result = result.replace(Regex("https?://[^\"\\s]+")) { match ->
            val url = match.value
            val path = url.substringAfter("://").substringAfter("/", "")
            if (path.isNotEmpty()) "\"/$path\"" else "\"[URL]\""
        }

        // Redact API keys/tokens
        result = result.replace(
            Regex("(api[_-]?key|token|secret|password)\\s*=\\s*\"[^\"]+\"", RegexOption.IGNORE_CASE)
        ) { match ->
            val key = match.value.substringBefore("=")
            "$key=\"[REDACTED]\""
        }

        return result
    }

    private fun hardRedactBody(body: String): String {
        // Replace all string literals with [REDACTED]
        return body.replace(Regex("\"[^\"]*\"")) { "[REDACTED]" }
    }

    /**
     * Estimate token count for a prompt
     */
    fun estimateTokens(prompt: String): Int {
        // Rough estimate: 4 characters per token
        return prompt.length / 4
    }
}
