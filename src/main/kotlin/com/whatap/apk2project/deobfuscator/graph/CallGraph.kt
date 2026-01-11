package com.whatap.apk2project.deobfuscator.graph

import com.whatap.apk2project.deobfuscator.models.*
import org.jgrapht.graph.DefaultDirectedGraph
import org.jgrapht.graph.DefaultEdge
import java.util.concurrent.ConcurrentHashMap

/**
 * Labeled edge for the call graph
 */
class LabeledEdge(
    val confidence: EdgeConfidence,
    val lineNumber: Int
) : DefaultEdge() {
    override fun toString(): String = "[$confidence@$lineNumber]"
}

/**
 * Node representing a method in the call graph
 */
data class MethodNode(
    val id: MethodId,
    val parsedMethod: ParsedMethod,
    var deobfuscatedName: String? = null,
    var confidence: Float = 0f,
    var summary: String? = null
) {
    val isObfuscated: Boolean get() = parsedMethod.isObfuscated
    val isSynthetic: Boolean get() = parsedMethod.isSynthetic
    val bodyTokens: Int get() = parsedMethod.bodyTokenCount
    val signals: List<String> get() = parsedMethod.stringLiterals + parsedMethod.annotations
}

/**
 * Call graph structure with confidence-aware edges
 */
class CallGraph {
    val graph = DefaultDirectedGraph<String, LabeledEdge>(LabeledEdge::class.java)

    private val methodNodes = ConcurrentHashMap<String, MethodNode>()
    private val classNodes = ConcurrentHashMap<String, ParsedClass>()

    // Reverse mapping: who calls me
    private val callersMap = ConcurrentHashMap<String, MutableSet<String>>()

    // Forward mapping: who do I call
    private val calleesMap = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Add a method node to the graph
     */
    fun addMethod(node: MethodNode) {
        val key = node.id.toShortString()
        methodNodes[key] = node
        if (!graph.containsVertex(key)) {
            graph.addVertex(key)
        }
    }

    /**
     * Add a class to the graph
     */
    fun addClass(parsedClass: ParsedClass) {
        classNodes[parsedClass.fullyQualifiedName] = parsedClass
    }

    /**
     * Add a method call edge with confidence
     */
    fun addMethodCall(
        caller: MethodId,
        callee: MethodId,
        confidence: EdgeConfidence,
        lineNumber: Int
    ) {
        val callerKey = caller.toShortString()
        val calleeKey = callee.toShortString()

        if (!graph.containsVertex(callerKey)) {
            graph.addVertex(callerKey)
        }
        if (!graph.containsVertex(calleeKey)) {
            graph.addVertex(calleeKey)
        }

        try {
            graph.addEdge(callerKey, calleeKey, LabeledEdge(confidence, lineNumber))
        } catch (e: IllegalArgumentException) {
            // Edge already exists, ignore
        }

        callersMap.getOrPut(calleeKey) { ConcurrentHashMap.newKeySet() }.add(callerKey)
        calleesMap.getOrPut(callerKey) { ConcurrentHashMap.newKeySet() }.add(calleeKey)
    }

    /**
     * Get a method node by ID
     */
    fun getMethodNode(id: MethodId): MethodNode? = methodNodes[id.toShortString()]

    /**
     * Get a method node by key string
     */
    fun getMethodNode(key: String): MethodNode? = methodNodes[key]

    /**
     * Get all method nodes
     */
    fun getAllMethodNodes(): Collection<MethodNode> = methodNodes.values

    /**
     * Get all obfuscated method nodes
     */
    fun getObfuscatedMethods(): List<MethodNode> {
        return methodNodes.values.filter { it.isObfuscated && !it.isSynthetic }
    }

    /**
     * Get methods that call this method
     */
    fun getCallers(nodeId: MethodId): Set<String> {
        return callersMap[nodeId.toShortString()] ?: emptySet()
    }

    /**
     * Get methods that this method calls
     */
    fun getCallees(nodeId: MethodId): Set<String> {
        return calleesMap[nodeId.toShortString()] ?: emptySet()
    }

    /**
     * Get callee edges with confidence filtering
     */
    fun getCalleesWithConfidence(nodeId: MethodId, minConfidence: EdgeConfidence): List<Pair<MethodId, EdgeConfidence>> {
        val key = nodeId.toShortString()
        val callees = calleesMap[key] ?: return emptyList()

        return callees.mapNotNull { calleeKey ->
            val edge = graph.getEdge(key, calleeKey)
            val node = methodNodes[calleeKey]
            if (edge != null && node != null && edge.confidence.ordinal <= minConfidence.ordinal) {
                node.id to edge.confidence
            } else null
        }
    }

    /**
     * Find leaf nodes: methods that don't call other obfuscated methods
     */
    fun findLeafNodes(): List<MethodNode> {
        return methodNodes.values.filter { node ->
            if (!node.isObfuscated || node.isSynthetic) return@filter false

            val callees = calleesMap[node.id.toShortString()] ?: emptySet()

            // All callees are either non-obfuscated or external
            callees.all { calleeKey ->
                val calleeNode = methodNodes[calleeKey]
                calleeNode == null || !calleeNode.isObfuscated
            }
        }
    }

    /**
     * Get statistics about the graph
     */
    fun getStats(): GraphStats {
        val obfuscatedCount = methodNodes.values.count { it.isObfuscated }
        val syntheticCount = methodNodes.values.count { it.isSynthetic }
        val leafCount = findLeafNodes().size

        val edgesByConfidence = mutableMapOf<EdgeConfidence, Int>()
        graph.edgeSet().forEach { edge ->
            edgesByConfidence[edge.confidence] = (edgesByConfidence[edge.confidence] ?: 0) + 1
        }

        return GraphStats(
            totalMethods = methodNodes.size,
            obfuscatedMethods = obfuscatedCount,
            syntheticMethods = syntheticCount,
            leafNodes = leafCount,
            totalEdges = graph.edgeSet().size,
            edgesByConfidence = edgesByConfidence
        )
    }
}

/**
 * Statistics about the call graph
 */
data class GraphStats(
    val totalMethods: Int,
    val obfuscatedMethods: Int,
    val syntheticMethods: Int,
    val leafNodes: Int,
    val totalEdges: Int,
    val edgesByConfidence: Map<EdgeConfidence, Int>
)
