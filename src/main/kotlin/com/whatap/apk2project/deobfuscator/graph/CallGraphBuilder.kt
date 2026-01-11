package com.whatap.apk2project.deobfuscator.graph

import com.whatap.apk2project.deobfuscator.ast.JavaAstParser
import com.whatap.apk2project.deobfuscator.models.*
import com.whatap.apk2project.utils.Logger
import java.io.File

/**
 * Builds a call graph from parsed Java source files
 */
class CallGraphBuilder(
    private val parser: JavaAstParser = JavaAstParser()
) {
    private val classIndex = mutableMapOf<String, ParsedClass>()
    private val methodIndex = mutableMapOf<String, ParsedMethod>()

    /**
     * Build call graph from a source directory
     */
    fun buildFromDirectory(sourceDir: File): CallGraph {
        val graph = CallGraph()

        // Phase 1: Parse all classes and build indices
        Logger.info("Parsing source files...")
        val parsedClasses = parser.parseDirectory(sourceDir)
        indexClasses(parsedClasses)

        Logger.info("Found ${parsedClasses.size} classes, ${methodIndex.size} methods")

        // Phase 2: Add all methods to graph
        parsedClasses.forEach { parsedClass ->
            graph.addClass(parsedClass)

            parsedClass.methods.forEach { method ->
                val methodId = createMethodId(parsedClass, method)
                val node = MethodNode(
                    id = methodId,
                    parsedMethod = method
                )
                graph.addMethod(node)
            }
        }

        // Phase 3: Build edges with confidence
        Logger.info("Building call graph edges...")
        parsedClasses.forEach { parsedClass ->
            parsedClass.methods.forEach { method ->
                val callerId = createMethodId(parsedClass, method)
                addEdgesForMethod(graph, callerId, method, parsedClass)
            }
        }

        val stats = graph.getStats()
        Logger.info("Call graph built: ${stats.totalMethods} methods, ${stats.totalEdges} edges")
        Logger.info("  Obfuscated: ${stats.obfuscatedMethods}, Leaf nodes: ${stats.leafNodes}")

        return graph
    }

    private fun indexClasses(classes: List<ParsedClass>) {
        classIndex.clear()
        methodIndex.clear()

        classes.forEach { parsedClass ->
            classIndex[parsedClass.className] = parsedClass
            classIndex[parsedClass.fullyQualifiedName] = parsedClass

            parsedClass.methods.forEach { method ->
                val key = "${parsedClass.fullyQualifiedName}.${method.signature}"
                methodIndex[key] = method
            }
        }
    }

    private fun createMethodId(parsedClass: ParsedClass, method: ParsedMethod): MethodId {
        val desc = method.parameters.joinToString(", ") { it.type }
        return MethodId(
            ownerFqcn = parsedClass.fullyQualifiedName,
            name = method.name,
            desc = desc.ifEmpty { null },
            sourcePath = parsedClass.sourceFile.absolutePath,
            range = if (method.lineStart > 0) method.lineStart..method.lineEnd else null,
            isStatic = method.isStatic,
            isSynthetic = method.isSynthetic
        )
    }

    private fun addEdgesForMethod(
        graph: CallGraph,
        callerId: MethodId,
        method: ParsedMethod,
        ownerClass: ParsedClass
    ) {
        method.methodCalls.forEach { call ->
            val (calleeId, confidence) = resolveMethodCall(call, ownerClass)

            if (calleeId != null) {
                graph.addMethodCall(
                    caller = callerId,
                    callee = calleeId,
                    confidence = confidence,
                    lineNumber = call.lineNumber
                )
            }
        }
    }

    /**
     * Resolve a method call to its target MethodId with confidence level
     */
    private fun resolveMethodCall(
        call: MethodCallInfo,
        callerClass: ParsedClass
    ): Pair<MethodId?, EdgeConfidence> {
        val targetClass = call.targetClass

        // Case 1: this.method() or just method()
        if (targetClass == null || targetClass == "this") {
            val method = findMethodInClass(callerClass, call.methodName, call.argumentTypes)
            if (method != null) {
                val id = MethodId(
                    ownerFqcn = callerClass.fullyQualifiedName,
                    name = call.methodName,
                    desc = call.argumentTypes.joinToString(", ").ifEmpty { null }
                )
                return id to EdgeConfidence.HIGH
            }
            // Could be inherited method - mark as MEDIUM
            return null to EdgeConfidence.MEDIUM
        }

        // Case 2: super.method()
        if (targetClass == "super") {
            val superClass = callerClass.superClass?.let { classIndex[it] }
            if (superClass != null) {
                val method = findMethodInClass(superClass, call.methodName, call.argumentTypes)
                if (method != null) {
                    val id = MethodId(
                        ownerFqcn = superClass.fullyQualifiedName,
                        name = call.methodName,
                        desc = call.argumentTypes.joinToString(", ").ifEmpty { null }
                    )
                    return id to EdgeConfidence.HIGH
                }
            }
            return null to EdgeConfidence.MEDIUM
        }

        // Case 3: SomeClass.staticMethod() - static call
        if (call.isStatic) {
            val resolvedClass = classIndex[targetClass]
            if (resolvedClass != null) {
                val method = findMethodInClass(resolvedClass, call.methodName, call.argumentTypes)
                if (method != null) {
                    val id = MethodId(
                        ownerFqcn = resolvedClass.fullyQualifiedName,
                        name = call.methodName,
                        desc = call.argumentTypes.joinToString(", ").ifEmpty { null },
                        isStatic = true
                    )
                    return id to EdgeConfidence.HIGH
                }
            }
            // External static call
            val id = MethodId(
                ownerFqcn = targetClass,
                name = call.methodName,
                desc = call.argumentTypes.joinToString(", ").ifEmpty { null },
                isStatic = true
            )
            return id to EdgeConfidence.LOW
        }

        // Case 4: object.method() - instance call
        // Try to resolve the type of the object
        val resolvedClass = resolveVariableType(targetClass, callerClass)
        if (resolvedClass != null) {
            val method = findMethodInClass(resolvedClass, call.methodName, call.argumentTypes)
            if (method != null) {
                val id = MethodId(
                    ownerFqcn = resolvedClass.fullyQualifiedName,
                    name = call.methodName,
                    desc = call.argumentTypes.joinToString(", ").ifEmpty { null }
                )
                return id to EdgeConfidence.MEDIUM
            }
        }

        // Case 5: Chained call or unresolved - LOW confidence
        val id = MethodId(
            ownerFqcn = targetClass,
            name = call.methodName,
            desc = call.argumentTypes.joinToString(", ").ifEmpty { null }
        )
        return id to EdgeConfidence.LOW
    }

    private fun findMethodInClass(
        parsedClass: ParsedClass,
        methodName: String,
        argumentTypes: List<String>
    ): ParsedMethod? {
        // Exact match first
        val exactMatch = parsedClass.methods.find { method ->
            method.name == methodName &&
            method.parameters.size == argumentTypes.size &&
            method.parameters.zip(argumentTypes).all { (param, argType) ->
                param.type == argType || argType == "Object"
            }
        }
        if (exactMatch != null) return exactMatch

        // Name-only match (for overloading uncertainty)
        return parsedClass.methods.find { it.name == methodName }
    }

    private fun resolveVariableType(variableName: String, callerClass: ParsedClass): ParsedClass? {
        // Check if it's a class name directly
        classIndex[variableName]?.let { return it }

        // Check class fields
        val field = callerClass.fields.find { it.name == variableName }
        if (field != null) {
            return classIndex[field.type]
        }

        return null
    }
}
