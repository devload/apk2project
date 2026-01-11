package com.whatap.apk2project.deobfuscator.context

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.whatap.apk2project.deobfuscator.models.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores deobfuscation results and provides context for bottom-up analysis
 */
class ContextStore {
    private val results = ConcurrentHashMap<String, DeobfuscationResult>()
    private val summaries = ConcurrentHashMap<String, String>()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Store a deobfuscation result
     */
    fun store(result: DeobfuscationResult) {
        val key = result.methodId.toShortString()
        results[key] = result
        summaries[key] = result.oneLineSummary
    }

    /**
     * Get a stored result by method ID
     */
    fun getResult(methodId: MethodId): DeobfuscationResult? {
        return results[methodId.toShortString()]
    }

    /**
     * Get deobfuscated name for a method
     */
    fun getDeobfuscatedName(methodId: MethodId): String? {
        return results[methodId.toShortString()]?.suggestedName
    }

    /**
     * Get summary for a method
     */
    fun getSummary(methodId: MethodId): String? {
        return summaries[methodId.toShortString()]
    }

    /**
     * Check if a method has been analyzed
     */
    fun isAnalyzed(methodId: MethodId): Boolean {
        return results.containsKey(methodId.toShortString())
    }

    /**
     * Get all results
     */
    fun getAllResults(): Map<MethodId, DeobfuscationResult> {
        return results.values.associateBy { it.methodId }
    }

    /**
     * Get results for specific method IDs
     */
    fun getResults(methodIds: Collection<MethodId>): Map<MethodId, DeobfuscationResult> {
        return methodIds.mapNotNull { id ->
            results[id.toShortString()]?.let { id to it }
        }.toMap()
    }

    /**
     * Get analysis statistics
     */
    fun getStats(): StoreStats {
        val highConfidence = results.values.count { it.confidence >= 0.8f }
        val mediumConfidence = results.values.count { it.confidence in 0.5f..0.8f }
        val lowConfidence = results.values.count { it.confidence < 0.5f }

        return StoreStats(
            totalAnalyzed = results.size,
            highConfidence = highConfidence,
            mediumConfidence = mediumConfidence,
            lowConfidence = lowConfidence,
            bySource = results.values.groupingBy { it.source }.eachCount()
        )
    }

    /**
     * Export results to JSON file
     */
    fun exportToJson(outputFile: File) {
        val export = ExportData(
            version = "1.0",
            timestamp = System.currentTimeMillis(),
            mappings = results.values.map { result ->
                MappingEntry(
                    originalFqcn = result.methodId.ownerFqcn,
                    originalName = result.originalName,
                    suggestedName = result.suggestedName,
                    summary = result.oneLineSummary,
                    confidence = result.confidence,
                    signals = result.keySignals
                )
            }
        )
        outputFile.writeText(gson.toJson(export))
    }

    /**
     * Import results from JSON file
     */
    fun importFromJson(inputFile: File) {
        val export = gson.fromJson(inputFile.readText(), ExportData::class.java)
        export.mappings.forEach { entry ->
            val methodId = MethodId(
                ownerFqcn = entry.originalFqcn,
                name = entry.originalName
            )
            val result = DeobfuscationResult(
                methodId = methodId,
                originalName = entry.originalName,
                suggestedName = entry.suggestedName,
                oneLineSummary = entry.summary,
                keySignals = entry.signals,
                confidence = entry.confidence,
                reasons = emptyList(),
                source = DeobfuscationSource.CACHED
            )
            store(result)
        }
    }

    /**
     * Clear all stored results
     */
    fun clear() {
        results.clear()
        summaries.clear()
    }

    /**
     * Get count of analyzed methods
     */
    val size: Int get() = results.size
}

/**
 * Statistics about the store
 */
data class StoreStats(
    val totalAnalyzed: Int,
    val highConfidence: Int,
    val mediumConfidence: Int,
    val lowConfidence: Int,
    val bySource: Map<DeobfuscationSource, Int>
)

/**
 * Export data structure
 */
private data class ExportData(
    val version: String,
    val timestamp: Long,
    val mappings: List<MappingEntry>
)

/**
 * Single mapping entry for export
 */
private data class MappingEntry(
    val originalFqcn: String,
    val originalName: String,
    val suggestedName: String,
    val summary: String,
    val confidence: Float,
    val signals: List<String>
)
