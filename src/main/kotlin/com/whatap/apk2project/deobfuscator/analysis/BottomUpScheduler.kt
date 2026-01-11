package com.whatap.apk2project.deobfuscator.analysis

import com.whatap.apk2project.deobfuscator.graph.CallGraph
import com.whatap.apk2project.deobfuscator.graph.LeafScorer
import com.whatap.apk2project.deobfuscator.graph.MethodNode
import com.whatap.apk2project.deobfuscator.models.EdgeConfidence
import com.whatap.apk2project.deobfuscator.models.MethodId
import com.whatap.apk2project.utils.Logger
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector

/**
 * Schedules methods for bottom-up analysis
 * Analyzes leaf nodes first, then propagates context upward
 */
class BottomUpScheduler(
    private val callGraph: CallGraph,
    private val leafScorer: LeafScorer = LeafScorer()
) {
    private val analyzed = mutableSetOf<String>()
    private val scheduled = mutableSetOf<String>()

    /**
     * Get the next batch of methods ready for analysis
     * Methods are ready when all their HIGH-confidence callees have been analyzed
     */
    fun getNextBatch(batchSize: Int = 10): List<MethodNode> {
        val ready = mutableListOf<MethodNode>()

        // Get all obfuscated methods not yet analyzed
        val candidates = callGraph.getObfuscatedMethods()
            .filter { !analyzed.contains(it.id.toShortString()) }
            .filter { !scheduled.contains(it.id.toShortString()) }

        for (node in candidates) {
            if (isReadyForAnalysis(node)) {
                ready.add(node)
                if (ready.size >= batchSize * 2) break  // Get more than needed for sorting
            }
        }

        // Sort by leaf scorer priority and take batch size
        val sorted = leafScorer.sortByPriority(ready).take(batchSize)

        // Mark as scheduled
        sorted.forEach { scheduled.add(it.id.toShortString()) }

        return sorted
    }

    /**
     * Check if a method is ready for analysis
     * A method is ready if all its HIGH-confidence callees are either:
     * - Already analyzed
     * - Not obfuscated (external/framework calls)
     * - Part of an SCC (will be handled separately)
     */
    private fun isReadyForAnalysis(node: MethodNode): Boolean {
        val callees = callGraph.getCalleesWithConfidence(node.id, EdgeConfidence.HIGH)

        return callees.all { (calleeId, _) ->
            val calleeKey = calleeId.toShortString()
            val calleeNode = callGraph.getMethodNode(calleeKey)

            // Ready if: already analyzed, not in graph (external), or not obfuscated
            analyzed.contains(calleeKey) ||
            calleeNode == null ||
            !calleeNode.isObfuscated
        }
    }

    /**
     * Mark a method as analyzed
     */
    fun markAnalyzed(methodId: MethodId) {
        val key = methodId.toShortString()
        analyzed.add(key)
        scheduled.remove(key)
    }

    /**
     * Mark multiple methods as analyzed
     */
    fun markAnalyzed(methodIds: Collection<MethodId>) {
        methodIds.forEach { markAnalyzed(it) }
    }

    /**
     * Get the initial leaf nodes (methods that don't call other obfuscated methods)
     */
    fun getLeafNodes(): List<MethodNode> {
        return callGraph.findLeafNodes()
            .filter { it.isObfuscated }
            .let { leafScorer.sortByPriority(it) }
    }

    /**
     * Detect strongly connected components (circular dependencies)
     */
    fun detectSccs(): List<Set<MethodNode>> {
        val sccInspector = KosarajuStrongConnectivityInspector(callGraph.graph)
        val sccs = sccInspector.stronglyConnectedSets()

        // Filter to SCCs with more than one node (actual cycles)
        // and containing obfuscated methods
        return sccs
            .filter { it.size > 1 }
            .map { keys ->
                keys.mapNotNull { key -> callGraph.getMethodNode(key) }
                    .filter { it.isObfuscated }
                    .toSet()
            }
            .filter { it.size > 1 }
    }

    /**
     * Get analysis order with SCC handling
     */
    fun getAnalysisOrder(): AnalysisOrder {
        val leafNodes = getLeafNodes()
        val sccs = detectSccs()
        val sccNodes = sccs.flatten().map { it.id.toShortString() }.toSet()

        // Regular nodes (not in SCC)
        val regularNodes = callGraph.getObfuscatedMethods()
            .filter { !sccNodes.contains(it.id.toShortString()) }

        Logger.info("Analysis order: ${leafNodes.size} leaf nodes, ${sccs.size} SCCs, ${regularNodes.size} regular nodes")

        return AnalysisOrder(
            leafNodes = leafNodes,
            sccs = sccs,
            regularNodes = regularNodes
        )
    }

    /**
     * Get progress statistics
     */
    fun getProgress(): SchedulerProgress {
        val total = callGraph.getObfuscatedMethods().size
        val done = analyzed.size
        val pending = scheduled.size

        return SchedulerProgress(
            totalMethods = total,
            analyzedMethods = done,
            pendingMethods = pending,
            remainingMethods = total - done,
            progressPercent = if (total > 0) (done * 100.0 / total) else 0.0
        )
    }

    /**
     * Reset scheduler state
     */
    fun reset() {
        analyzed.clear()
        scheduled.clear()
    }

    /**
     * Check if all methods have been analyzed
     */
    fun isComplete(): Boolean {
        return callGraph.getObfuscatedMethods().all {
            analyzed.contains(it.id.toShortString())
        }
    }

    /**
     * Get methods that are blocked (waiting for callees to be analyzed)
     */
    fun getBlockedMethods(): List<BlockedMethod> {
        val candidates = callGraph.getObfuscatedMethods()
            .filter { !analyzed.contains(it.id.toShortString()) }
            .filter { !scheduled.contains(it.id.toShortString()) }

        return candidates.mapNotNull { node ->
            val blockedBy = getBlockingCallees(node)
            if (blockedBy.isNotEmpty()) {
                BlockedMethod(node, blockedBy)
            } else null
        }
    }

    private fun getBlockingCallees(node: MethodNode): List<MethodId> {
        val callees = callGraph.getCalleesWithConfidence(node.id, EdgeConfidence.HIGH)

        return callees.mapNotNull { (calleeId, _) ->
            val calleeKey = calleeId.toShortString()
            val calleeNode = callGraph.getMethodNode(calleeKey)

            if (calleeNode != null &&
                calleeNode.isObfuscated &&
                !analyzed.contains(calleeKey)) {
                calleeId
            } else null
        }
    }
}

/**
 * Analysis order with categorized methods
 */
data class AnalysisOrder(
    val leafNodes: List<MethodNode>,
    val sccs: List<Set<MethodNode>>,
    val regularNodes: List<MethodNode>
)

/**
 * Progress statistics
 */
data class SchedulerProgress(
    val totalMethods: Int,
    val analyzedMethods: Int,
    val pendingMethods: Int,
    val remainingMethods: Int,
    val progressPercent: Double
)

/**
 * Method blocked waiting for callees
 */
data class BlockedMethod(
    val node: MethodNode,
    val blockedBy: List<MethodId>
)
