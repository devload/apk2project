package com.whatap.apk2project.deobfuscator.parser

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.whatap.apk2project.deobfuscator.cache.ASTCache
import com.whatap.apk2project.deobfuscator.cache.FileHashCache
import com.whatap.apk2project.deobfuscator.model.*
import org.jgrapht.graph.DefaultDirectedGraph
import org.jgrapht.graph.DefaultEdge
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

/**
 * Java 소스 파일을 파싱하여 메소드 콜 그래프를 구축
 *
 * Performance optimizations:
 * - ThreadLocal JavaParser pool (avoid re-creation overhead)
 * - AST cache (eliminate double-parsing between Phase 1 and Phase 2)
 * - Method index (O(1) method lookup instead of O(n))
 * - FileHashCache (incremental processing based on content hash)
 */
class MethodCallGraphBuilder(
    private val cacheDir: File? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // ThreadLocal parser pool - reuse parser instances per thread
    private val parserPool = ThreadLocal.withInitial { JavaParser() }

    // AST cache - stores parsed ASTs for reuse in Phase 2
    val astCache = ASTCache(maxSize = 10000)

    // File hash cache for incremental processing
    private val fileHashCache: FileHashCache? = cacheDir?.let { FileHashCache(it) }

    companion object {
        // 병렬 처리 설정
        const val MIN_WORKERS = 4  // 최소 워커 수
        const val FILE_PARSE_CHUNK_SIZE = 100  // 파일 파싱 청크 크기
        const val CALL_GRAPH_CHUNK_SIZE = 500  // 콜 그래프 빌딩 청크 크기

        // 진행률 보고 주기
        const val PROGRESS_REPORT_INTERVAL = 100  // 100개마다 진행률 보고

        // 우선순위 후보 수
        const val PRIORITY_CANDIDATE_LIMIT = 100  // 우선순위 3 후보 수 제한
    }

    // 파싱된 클래스들
    val classes = ConcurrentHashMap<String, ClassNode>()

    // 메소드 노드 맵 (id -> MethodNode)
    val methods = ConcurrentHashMap<String, MethodNode>()

    // Method index for O(1) lookup: className -> (methodName -> List<MethodNode>)
    // Multiple methods can have the same name (overloads)
    private val methodIndex = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableList<MethodNode>>>()

    // 콜 그래프 (메소드 -> 호출하는 메소드들)
    val callGraph = DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge::class.java)

    // 역방향 콜 그래프 (메소드 -> 호출받는 메소드들)
    val reverseCallGraph = DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge::class.java)

    /**
     * Phase 1: 디렉토리 내 모든 Java 파일 파싱 (Call Graph 구축 제외)
     * 병렬 처리로 성능 최적화
     */
    fun parseFilesOnly(sourceDir: File, progressCallback: ((Int, Int) -> Unit)? = null): ParseResult {
        val javaFiles = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filter { !it.absolutePath.contains(".apk2project") }  // 캐시 디렉토리 제외
            .toList()

        logger.info("Found ${javaFiles.size} Java files to parse")

        val parsed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val processed = AtomicInteger(0)

        // 병렬 처리 (CPU 코어 수에 맞춰 워커 수 결정)
        val numWorkers = Runtime.getRuntime().availableProcessors().coerceAtLeast(MIN_WORKERS)
        val chunkSize = FILE_PARSE_CHUNK_SIZE

        logger.info("Using $numWorkers parallel workers, chunk size: $chunkSize")

        runBlocking {
            javaFiles.chunked(chunkSize).forEach { chunk ->
                // 각 청크를 병렬로 처리
                val jobs = chunk.map { file ->
                    async(Dispatchers.IO) {
                        try {
                            parseFile(file)
                            parsed.incrementAndGet()
                        } catch (e: Exception) {
                            logger.debug("Failed to parse ${file.absolutePath}: ${e.message}")
                            failed.incrementAndGet()
                        } finally {
                            val current = processed.incrementAndGet()
                            // 진행 상황 업데이트 (1000개마다 로깅)
                            if (current % 1000 == 0) {
                                logger.info("Parsed $current/${javaFiles.size} files...")
                            }
                            if (current % PROGRESS_REPORT_INTERVAL == 0 || current == javaFiles.size) {
                                progressCallback?.invoke(current, javaFiles.size)
                            }
                        }
                    }
                }

                // 모든 job 완료 대기
                jobs.awaitAll()
            }
        }

        progressCallback?.invoke(javaFiles.size, javaFiles.size)

        logger.info("Parsing complete: ${parsed.get()} success, ${failed.get()} failed")
        logger.info("Found ${classes.size} classes, ${methods.size} methods")
        logger.info("AST Cache stats: ${astCache.getStats()}")

        // Save file hashes for incremental processing
        fileHashCache?.let { cache ->
            javaFiles.forEach { cache.updateHash(it) }
            cache.saveToDisk()
            logger.info("File hashes saved for incremental processing")
        }

        return ParseResult(
            totalFiles = javaFiles.size,
            parsedFiles = parsed.get(),
            failedFiles = failed.get(),
            classCount = classes.size,
            methodCount = methods.size
        )
    }

    /**
     * Phase 1 (Incremental): 변경된 파일만 파싱
     *
     * Performance improvement:
     * - 변경 없음: ~10초 (98% reduction)
     * - 100개 파일 변경: ~30초 (92% reduction)
     * - 1,000개 파일 변경: ~1분 (83% reduction)
     */
    fun parseFilesIncremental(
        sourceDir: File,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): IncrementalParseResult {
        val allFiles = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filter { !it.absolutePath.contains(".apk2project") }
            .toList()

        // Check which files have changed
        val changedFiles = if (fileHashCache != null) {
            fileHashCache.getChangedFiles(allFiles)
        } else {
            allFiles  // No hash cache, process all files
        }

        logger.info("Found ${allFiles.size} Java files, ${changedFiles.size} changed")

        if (changedFiles.isEmpty() && classes.isNotEmpty()) {
            logger.info("✓ No files changed, using cached data")
            return IncrementalParseResult(
                totalFiles = allFiles.size,
                changedFiles = 0,
                parsedFiles = 0,
                failedFiles = 0,
                classCount = classes.size,
                methodCount = methods.size,
                fromCache = true,
                changedFilesList = emptyList()
            )
        }

        val parsed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val processed = AtomicInteger(0)

        val numWorkers = Runtime.getRuntime().availableProcessors().coerceAtLeast(MIN_WORKERS)
        val chunkSize = FILE_PARSE_CHUNK_SIZE

        logger.info("Processing ${changedFiles.size} changed files with $numWorkers workers...")

        // Remove old data for changed files
        changedFiles.forEach { file ->
            val filePath = file.absolutePath
            // Remove old class entries for this file
            classes.entries.removeIf { (_, classNode) ->
                if (classNode.file.absolutePath == filePath) {
                    // Also remove methods from this class
                    classNode.methods.forEach { method ->
                        methods.remove(method.id)
                        methodIndex[classNode.fqn]?.remove(method.methodName)
                        // Remove vertices from graphs
                        if (callGraph.containsVertex(method.id)) {
                            callGraph.removeVertex(method.id)
                        }
                        if (reverseCallGraph.containsVertex(method.id)) {
                            reverseCallGraph.removeVertex(method.id)
                        }
                    }
                    true
                } else false
            }
            // Remove from AST cache
            astCache.remove(filePath)
        }

        runBlocking {
            changedFiles.chunked(chunkSize).forEach { chunk ->
                val jobs = chunk.map { file ->
                    async(Dispatchers.IO) {
                        try {
                            parseFile(file)
                            parsed.incrementAndGet()
                            fileHashCache?.updateHash(file)
                        } catch (e: Exception) {
                            logger.debug("Failed to parse ${file.absolutePath}: ${e.message}")
                            failed.incrementAndGet()
                        } finally {
                            val current = processed.incrementAndGet()
                            if (current % 100 == 0) {
                                logger.info("Parsed $current/${changedFiles.size} changed files...")
                            }
                            progressCallback?.invoke(current, changedFiles.size)
                        }
                    }
                }
                jobs.awaitAll()
            }
        }

        fileHashCache?.saveToDisk()

        logger.info("Incremental parsing complete: ${parsed.get()} success, ${failed.get()} failed")
        logger.info("Total: ${classes.size} classes, ${methods.size} methods")
        logger.info("AST Cache stats: ${astCache.getStats()}")

        return IncrementalParseResult(
            totalFiles = allFiles.size,
            changedFiles = changedFiles.size,
            parsedFiles = parsed.get(),
            failedFiles = failed.get(),
            classCount = classes.size,
            methodCount = methods.size,
            fromCache = false,
            changedFilesList = changedFiles
        )
    }

    /**
     * 단일 파일 파싱
     *
     * Optimizations:
     * - Uses ThreadLocal parser pool (avoid new JavaParser() overhead)
     * - Stores parsed AST in cache for Phase 2 reuse
     */
    private fun parseFile(file: File) {
        // Use ThreadLocal parser pool instead of creating new instance each time
        val fileParser = parserPool.get()
        val parseResult = fileParser.parse(file)
        if (!parseResult.isSuccessful) {
            throw RuntimeException("Parse failed: ${parseResult.problems}")
        }

        val cu = parseResult.result.orElseThrow()

        // Store AST in cache for Phase 2 reuse (eliminates double-parsing)
        astCache.put(file.absolutePath, cu)

        val packageName = cu.packageDeclaration
            .map { it.nameAsString }
            .orElse("")

        // import 문 수집
        val imports = cu.imports.map { it.nameAsString }.toMutableSet()

        // 클래스 방문
        cu.accept(object : VoidVisitorAdapter<Void>() {
            override fun visit(n: ClassOrInterfaceDeclaration, arg: Void?) {
                val className = n.nameAsString
                val fqn = if (packageName.isNotEmpty()) "$packageName.$className" else className
                val isObfuscated = ObfuscationDetector.isObfuscatedName(className) ||
                        ObfuscationDetector.isObfuscatedPackage(packageName)

                val classNode = ClassNode(
                    packageName = packageName,
                    className = className,
                    file = file,
                    imports = imports,
                    isObfuscated = isObfuscated
                )

                // 필드 수집
                n.fields.forEach { field ->
                    field.variables.forEach { variable ->
                        classNode.fields.add(
                            FieldNode(
                                name = variable.nameAsString,
                                type = field.elementType.asString(),
                                className = classNode.fqn,
                                file = file,
                                lineNumber = field.begin.map { it.line }.orElse(0),
                                isObfuscated = ObfuscationDetector.isObfuscatedName(variable.nameAsString)
                            )
                        )
                    }
                }

                // 메소드 수집
                n.methods.forEach { method ->
                    val methodNode = createMethodNode(fqn, method, file)
                    classNode.methods.add(methodNode)
                    methods[methodNode.id] = methodNode
                    callGraph.addVertex(methodNode.id)
                    reverseCallGraph.addVertex(methodNode.id)

                    // Add to method index for O(1) lookup
                    methodIndex
                        .computeIfAbsent(fqn) { ConcurrentHashMap() }
                        .computeIfAbsent(methodNode.methodName) { mutableListOf() }
                        .add(methodNode)
                }

                classes[fqn] = classNode
                super.visit(n, arg)
            }
        }, null)
    }

    /**
     * MethodDeclaration으로부터 MethodNode 생성
     */
    private fun createMethodNode(
        className: String,
        method: MethodDeclaration,
        file: File
    ): MethodNode {
        val signature = method.parameters.joinToString(",") { it.typeAsString }
        val startLine = method.begin.map { it.line }.orElse(0)
        val endLine = method.end.map { it.line }.orElse(0)

        // 알려진 패키지의 메소드는 난독화가 아님
        val isKnownPackage = ObfuscationDetector.isKnownPackage(className)
        val isObfuscated = !isKnownPackage &&
                (ObfuscationDetector.isObfuscatedName(method.nameAsString) ||
                 ObfuscationDetector.isObfuscatedPackage(className.substringBeforeLast(".")))

        return MethodNode(
            className = className,
            methodName = method.nameAsString,
            signature = "($signature)",
            file = file,
            startLine = startLine,
            endLine = endLine,
            isObfuscated = isObfuscated
        )
    }

    /**
     * Phase 2: 메소드 호출 관계로 콜 그래프 구축 (병렬 처리)
     */
    fun buildCallGraph(progressCallback: ((Int, Int) -> Unit)? = null): BuildGraphResult {
        logger.info("Building call graph...")

        val classValues = classes.values.toList()
        val totalClasses = classValues.size
        val processed = AtomicInteger(0)

        // Edge 정보를 thread-safe하게 수집
        data class EdgeInfo(val from: String, val to: String)
        val edgeList = ConcurrentHashMap.newKeySet<EdgeInfo>()

        logger.info("Using parallel processing for call graph building...")

        runBlocking {
            val chunkSize = CALL_GRAPH_CHUNK_SIZE
            classValues.chunked(chunkSize).forEach { chunk ->
                val jobs = chunk.map { classNode ->
                    async(Dispatchers.IO) {
                        try {
                            // Try to get AST from cache first (eliminates double-parsing)
                            val cu = astCache.get(classNode.file.absolutePath)
                                ?: run {
                                    // Cache miss - parse the file
                                    val fileParser = parserPool.get()
                                    val parseResult = fileParser.parse(classNode.file)
                                    if (!parseResult.isSuccessful) return@async
                                    val parsed = parseResult.result.orElseThrow()
                                    astCache.put(classNode.file.absolutePath, parsed)
                                    parsed
                                }

                            cu.accept(object : VoidVisitorAdapter<Void>() {
                                override fun visit(n: MethodDeclaration, arg: Void?) {
                                    val callerId = "${classNode.fqn}#${n.nameAsString}(${n.parameters.joinToString(",") { it.typeAsString }})"

                                    // 이 메소드 내에서 호출하는 다른 메소드들 찾기
                                    n.accept(object : VoidVisitorAdapter<Void>() {
                                        override fun visit(call: MethodCallExpr, arg: Void?) {
                                            val calleeName = call.nameAsString
                                            val scope = call.scope.map { it.toString() }.orElse("")

                                            // 가능한 callee 후보들 찾기
                                            findCalleeMethod(classNode, scope, calleeName)?.let { calleeId ->
                                                if (callerId != calleeId && callGraph.containsVertex(calleeId)) {
                                                    edgeList.add(EdgeInfo(callerId, calleeId))
                                                }
                                            }

                                            super.visit(call, arg)
                                        }
                                    }, null)

                                    super.visit(n, arg)
                                }
                            }, null)
                        } catch (e: Exception) {
                            logger.debug("Failed to build call graph for ${classNode.fqn}: ${e.message}")
                        } finally {
                            val current = processed.incrementAndGet()
                            if (current % 1000 == 0) {
                                logger.info("Building call graph: $current/$totalClasses classes...")
                            }
                            if (current % PROGRESS_REPORT_INTERVAL == 0 || current == totalClasses) {
                                progressCallback?.invoke(current, totalClasses)
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        }

        // Edge들을 graph에 추가 (단일 스레드에서 안전하게)
        logger.info("Adding ${edgeList.size} edges to graph...")
        var skippedEdges = 0
        edgeList.forEach { edge ->
            // 양쪽 vertex가 callGraph와 reverseCallGraph 모두에 존재하는 경우만 edge 추가
            val fromExists = callGraph.containsVertex(edge.from) && reverseCallGraph.containsVertex(edge.from)
            val toExists = callGraph.containsVertex(edge.to) && reverseCallGraph.containsVertex(edge.to)

            if (fromExists && toExists) {
                try {
                    callGraph.addEdge(edge.from, edge.to)
                    reverseCallGraph.addEdge(edge.to, edge.from)
                } catch (e: Exception) {
                    logger.debug("Failed to add edge ${edge.from} -> ${edge.to}: ${e.message}")
                    skippedEdges++
                }
            } else {
                skippedEdges++
            }
        }
        if (skippedEdges > 0) {
            logger.info("Skipped $skippedEdges edges due to missing vertices (external/library methods)")
        }

        progressCallback?.invoke(totalClasses, totalClasses)

        val edgeCount = callGraph.edgeSet().size
        logger.info("Call graph built: ${methods.size} methods, $edgeCount edges")

        return BuildGraphResult(
            totalClasses = totalClasses,
            processedClasses = processed.get(),
            methodCount = methods.size,
            edgeCount = edgeCount
        )
    }

    /**
     * Phase 2 (Incremental): 변경된 클래스만 Call Graph 업데이트
     *
     * Performance improvement:
     * - 영향받는 노드만 재계산
     * - 기존 엣지 제거 후 새 엣지만 추가
     */
    fun updateCallGraphIncremental(
        changedFiles: List<File>,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): BuildGraphResult {
        logger.info("Updating call graph incrementally for ${changedFiles.size} files...")

        // 1. 변경된 파일의 클래스들 식별
        val affectedClasses = changedFiles.mapNotNull { file ->
            classes.values.find { it.file.absolutePath == file.absolutePath }
        }

        if (affectedClasses.isEmpty()) {
            logger.info("No affected classes found")
            return BuildGraphResult(0, 0, methods.size, callGraph.edgeSet().size)
        }

        logger.info("Found ${affectedClasses.size} affected classes")

        // 2. 해당 클래스의 기존 엣지 제거
        var removedEdges = 0
        affectedClasses.forEach { classNode ->
            classNode.methods.forEach { method ->
                // Remove outgoing edges from this method
                if (callGraph.containsVertex(method.id)) {
                    val outEdges = callGraph.outgoingEdgesOf(method.id).toList()
                    outEdges.forEach { edge ->
                        val target = callGraph.getEdgeTarget(edge)
                        callGraph.removeEdge(edge)
                        // Also remove from reverse graph
                        if (reverseCallGraph.containsVertex(target) && reverseCallGraph.containsVertex(method.id)) {
                            val reverseEdge = reverseCallGraph.getEdge(target, method.id)
                            if (reverseEdge != null) {
                                reverseCallGraph.removeEdge(reverseEdge)
                            }
                        }
                        removedEdges++
                    }
                }
            }
        }
        logger.info("Removed $removedEdges existing edges")

        // 3. 변경된 클래스만 재분석하여 엣지 추가
        data class EdgeInfo(val from: String, val to: String)
        val newEdges = ConcurrentHashMap.newKeySet<EdgeInfo>()
        val processed = AtomicInteger(0)

        runBlocking {
            affectedClasses.chunked(CALL_GRAPH_CHUNK_SIZE).forEach { chunk ->
                val jobs = chunk.map { classNode ->
                    async(Dispatchers.IO) {
                        try {
                            val cu = astCache.get(classNode.file.absolutePath)
                                ?: run {
                                    val fileParser = parserPool.get()
                                    val parseResult = fileParser.parse(classNode.file)
                                    if (!parseResult.isSuccessful) return@async
                                    val parsed = parseResult.result.orElseThrow()
                                    astCache.put(classNode.file.absolutePath, parsed)
                                    parsed
                                }

                            cu.accept(object : VoidVisitorAdapter<Void>() {
                                override fun visit(n: MethodDeclaration, arg: Void?) {
                                    val callerId = "${classNode.fqn}#${n.nameAsString}(${n.parameters.joinToString(",") { it.typeAsString }})"

                                    n.accept(object : VoidVisitorAdapter<Void>() {
                                        override fun visit(call: MethodCallExpr, arg: Void?) {
                                            val calleeName = call.nameAsString
                                            val scope = call.scope.map { it.toString() }.orElse("")

                                            findCalleeMethod(classNode, scope, calleeName)?.let { calleeId ->
                                                if (callerId != calleeId && callGraph.containsVertex(calleeId)) {
                                                    newEdges.add(EdgeInfo(callerId, calleeId))
                                                }
                                            }
                                            super.visit(call, arg)
                                        }
                                    }, null)
                                    super.visit(n, arg)
                                }
                            }, null)
                        } catch (e: Exception) {
                            logger.debug("Failed to update call graph for ${classNode.fqn}: ${e.message}")
                        } finally {
                            val current = processed.incrementAndGet()
                            progressCallback?.invoke(current, affectedClasses.size)
                        }
                    }
                }
                jobs.awaitAll()
            }
        }

        // 4. 새 엣지 추가
        var addedEdges = 0
        newEdges.forEach { edge ->
            val fromExists = callGraph.containsVertex(edge.from) && reverseCallGraph.containsVertex(edge.from)
            val toExists = callGraph.containsVertex(edge.to) && reverseCallGraph.containsVertex(edge.to)

            if (fromExists && toExists) {
                try {
                    callGraph.addEdge(edge.from, edge.to)
                    reverseCallGraph.addEdge(edge.to, edge.from)
                    addedEdges++
                } catch (e: Exception) {
                    // Ignore duplicate edges
                }
            }
        }

        logger.info("Added $addedEdges new edges")
        logger.info("Call graph updated: ${methods.size} methods, ${callGraph.edgeSet().size} edges")

        return BuildGraphResult(
            totalClasses = affectedClasses.size,
            processedClasses = processed.get(),
            methodCount = methods.size,
            edgeCount = callGraph.edgeSet().size
        )
    }

    /**
     * 호출 대상 메소드 찾기
     *
     * Optimized with method index for O(1) lookup instead of O(n) iteration.
     */
    private fun findCalleeMethod(
        callerClass: ClassNode,
        scope: String,
        methodName: String
    ): String? {
        // 1. 같은 클래스 내 메소드 (O(1) lookup via method index)
        if (scope.isEmpty() || scope == "this") {
            methodIndex[callerClass.fqn]?.get(methodName)?.firstOrNull()?.let {
                return it.id
            }
        }

        // 2. import된 클래스의 메소드 (O(1) lookup via method index)
        callerClass.imports.forEach { import ->
            val importedClass = import.substringAfterLast(".")
            if (scope == importedClass || scope.startsWith("$importedClass.")) {
                methodIndex[import]?.get(methodName)?.firstOrNull()?.let {
                    return it.id
                }
            }
        }

        // 3. 같은 패키지 내 클래스 (O(1) lookup via method index)
        val samePackageClass = if (callerClass.packageName.isNotEmpty()) {
            "${callerClass.packageName}.$scope"
        } else {
            scope
        }
        methodIndex[samePackageClass]?.get(methodName)?.firstOrNull()?.let {
            return it.id
        }

        return null
    }

    /**
     * 리프 메소드 찾기 (우선순위 기반)
     *
     * Priority 1: 난독화된 메소드를 호출하지 않는 메소드 (true leaves)
     * Priority 2: 호출하는 난독화된 메소드가 모두 처리된 메소드
     * Priority 3: 처리되지 않은 callee가 가장 적은 메소드 (순환 참조 해결)
     *
     * IMPORTANT: 처리되지 않은 난독화된 메소드만 대상으로 함
     */
    fun findLeafMethods(processedMethods: Set<String> = emptySet()): List<MethodNode> {
        // 처리되지 않은 난독화된 메소드만 필터링
        val unprocessedObfuscated = methods.values
            .filter { it.isObfuscated && it.id !in processedMethods }

        logger.info("[Priority Check] total=${methods.size}, obfuscated=${methods.values.count { it.isObfuscated }}, " +
                "unprocessed=${unprocessedObfuscated.size}, processed=${processedMethods.size}")

        if (unprocessedObfuscated.isEmpty()) {
            logger.info("[Priority Check] All methods processed or no obfuscated methods")
            return emptyList()  // 모든 메소드 처리 완료
        }

        // Priority 1: True leaves (난독화된 메소드를 호출하지 않음)
        val trueLeaves = unprocessedObfuscated.filter { method ->
            // Vertex가 그래프에 존재하는지 먼저 확인
            if (!callGraph.containsVertex(method.id)) {
                true  // 그래프에 없으면 leaf로 간주
            } else {
                val outgoingEdges = callGraph.outgoingEdgesOf(method.id)
                val callees = outgoingEdges.map { callGraph.getEdgeTarget(it) }
                    .mapNotNull { methods[it] }
                callees.none { it.isObfuscated }
            }
        }

        logger.info("[Priority 1] true leaves: ${trueLeaves.size}")

        if (trueLeaves.isNotEmpty()) {
            return trueLeaves.sortedBy { it.className }
        }

        // Priority 2: 호출하는 난독화된 메소드가 모두 처리됨
        val ready = unprocessedObfuscated.filter { method ->
            // Vertex가 그래프에 존재하는지 먼저 확인
            if (!callGraph.containsVertex(method.id)) {
                false  // 그래프에 없으면 Priority 2 해당 안됨
            } else {
                val outgoingEdges = callGraph.outgoingEdgesOf(method.id)
                val callees = outgoingEdges.map { callGraph.getEdgeTarget(it) }
                    .mapNotNull { methods[it] }
                    .filter { it.isObfuscated }

                // 난독화된 callee가 있지만 모두 처리됨
                callees.isNotEmpty() && callees.all { it.id in processedMethods }
            }
        }

        logger.info("[Priority 2] dependencies met: ${ready.size}")

        if (ready.isNotEmpty()) {
            return ready.sortedBy { it.className }
        }

        // Priority 3: 처리되지 않은 callee가 가장 적은 메소드
        // (순환 참조나 복잡한 의존성 해결 - 강제로 처리 시작)
        val withUnprocessedCount = unprocessedObfuscated.map { method ->
            // Vertex가 그래프에 존재하는지 먼저 확인
            if (!callGraph.containsVertex(method.id)) {
                method to 0  // 그래프에 없으면 callee 0개로 간주
            } else {
                val outgoingEdges = callGraph.outgoingEdgesOf(method.id)
                val callees = outgoingEdges.map { callGraph.getEdgeTarget(it) }
                    .mapNotNull { methods[it] }
                    .filter { it.isObfuscated && it.id !in processedMethods }
                method to callees.size
            }
        }

        if (withUnprocessedCount.isEmpty()) {
            // 이론상 여기 도달하면 안됨 (unprocessedObfuscated가 비어있지 않으므로)
            return emptyList()
        }

        val minUnprocessed = withUnprocessedCount.minOfOrNull { it.second } ?: return emptyList()

        val priority3Candidates = withUnprocessedCount
            .filter { it.second == minUnprocessed }
            .map { it.first }
            .take(PRIORITY_CANDIDATE_LIMIT)

        logger.info("[Priority 3] min dependencies=$minUnprocessed, candidates=${priority3Candidates.size}")

        // 가장 의존성이 적은 메소드들 중 최대 100개씩 처리
        return priority3Candidates.sortedBy { it.className }
    }

    /**
     * 특정 메소드를 호출하는 메소드들 찾기 (상위 노드)
     */
    fun findCallers(methodId: String): List<MethodNode> {
        if (!reverseCallGraph.containsVertex(methodId)) return emptyList()

        return reverseCallGraph.outgoingEdgesOf(methodId)
            .map { reverseCallGraph.getEdgeTarget(it) }
            .mapNotNull { methods[it] }
    }

    /**
     * 특정 메소드가 호출하는 메소드들 찾기 (하위 노드)
     */
    fun findCallees(methodId: String): List<MethodNode> {
        if (!callGraph.containsVertex(methodId)) return emptyList()

        return callGraph.outgoingEdgesOf(methodId)
            .map { callGraph.getEdgeTarget(it) }
            .mapNotNull { methods[it] }
    }

    /**
     * 콜 그래프를 JSON 파일로 저장
     */
    fun saveToCache(cacheFile: File) {
        logger.info("Saving call graph cache to ${cacheFile.absolutePath}...")

        val cacheData = CallGraphCache(
            classes = classes.values.map { classNode ->
                ClassCacheEntry(
                    packageName = classNode.packageName,
                    className = classNode.className,
                    filePath = classNode.file.absolutePath,
                    imports = classNode.imports.toList(),
                    isObfuscated = classNode.isObfuscated,
                    fields = classNode.fields.map { FieldCacheEntry(it.name, it.type, it.isObfuscated) },
                    methods = classNode.methods.map { it.id }
                )
            },
            methods = methods.values.map { methodNode ->
                MethodCacheEntry(
                    id = methodNode.id,
                    className = methodNode.className,
                    methodName = methodNode.methodName,
                    signature = methodNode.signature,
                    filePath = methodNode.file.absolutePath,
                    startLine = methodNode.startLine,
                    endLine = methodNode.endLine,
                    isObfuscated = methodNode.isObfuscated
                )
            },
            edges = callGraph.edgeSet().map { edge ->
                EdgeCacheEntry(
                    source = callGraph.getEdgeSource(edge),
                    target = callGraph.getEdgeTarget(edge)
                )
            }
        )

        val gson = GsonBuilder().setPrettyPrinting().create()
        cacheFile.writeText(gson.toJson(cacheData))
        logger.info("Cache saved: ${classes.size} classes, ${methods.size} methods, ${cacheData.edges.size} edges")
    }

    /**
     * JSON 캐시 파일에서 콜 그래프 로드
     */
    fun loadFromCache(cacheFile: File): Boolean {
        if (!cacheFile.exists()) {
            logger.info("Cache file not found: ${cacheFile.absolutePath}")
            return false
        }

        logger.info("Loading call graph cache from ${cacheFile.absolutePath}...")

        return try {
            val gson = Gson()
            val cacheData = gson.fromJson(cacheFile.readText(), CallGraphCache::class.java)

            // 메소드 노드 복원
            cacheData.methods.forEach { entry ->
                val methodNode = MethodNode(
                    className = entry.className,
                    methodName = entry.methodName,
                    signature = entry.signature,
                    file = File(entry.filePath),
                    startLine = entry.startLine,
                    endLine = entry.endLine,
                    isObfuscated = entry.isObfuscated
                )
                methods[methodNode.id] = methodNode
                callGraph.addVertex(methodNode.id)
                reverseCallGraph.addVertex(methodNode.id)
            }

            // 클래스 노드 복원
            cacheData.classes.forEach { entry ->
                val classNode = ClassNode(
                    packageName = entry.packageName,
                    className = entry.className,
                    file = File(entry.filePath),
                    imports = entry.imports.toMutableSet(),
                    isObfuscated = entry.isObfuscated
                )
                entry.fields.forEach { f ->
                    classNode.fields.add(
                        FieldNode(
                            name = f.name,
                            type = f.type,
                            className = classNode.fqn,
                            file = classNode.file,
                            lineNumber = 0,  // 캐시에서는 라인 정보 없음
                            isObfuscated = f.isObfuscated
                        )
                    )
                }
                entry.methods.forEach { methodId ->
                    methods[methodId]?.let { classNode.methods.add(it) }
                }
                classes[classNode.fqn] = classNode
            }

            // 엣지 복원
            cacheData.edges.forEach { edge ->
                if (callGraph.containsVertex(edge.source) && callGraph.containsVertex(edge.target)) {
                    callGraph.addEdge(edge.source, edge.target)
                    reverseCallGraph.addEdge(edge.target, edge.source)
                }
            }

            logger.info("Cache loaded: ${classes.size} classes, ${methods.size} methods, ${cacheData.edges.size} edges")
            true
        } catch (e: Exception) {
            logger.error("Failed to load cache: ${e.message}")
            false
        }
    }

    /**
     * 캐시가 유효한지 확인
     *
     * 검증 방법:
     * 1. FileHashCache가 있으면: 변경된 파일이 없으면 유효 (콘텐츠 기반)
     * 2. FileHashCache가 없으면: 타임스탬프 기반 비교
     *
     * Note: CodeFixer가 소스 파일을 수정하므로 타임스탬프만으로는 부정확함
     */
    fun isCacheValid(cacheFile: File, sourceDir: File): Boolean {
        if (!cacheFile.exists()) {
            logger.debug("[isCacheValid] Cache file does not exist: ${cacheFile.absolutePath}")
            return false
        }

        // FileHashCache가 있으면 콘텐츠 기반 검증 사용
        if (fileHashCache != null) {
            val excludeDirs = listOf(".apk2project", "decompiler_stubs")
            val javaFiles = sourceDir.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .filter { file -> excludeDirs.none { file.absolutePath.contains(it) } }
                .toList()

            val changedFiles = fileHashCache.getChangedFiles(javaFiles)
            val isValid = changedFiles.isEmpty()
            logger.info("[isCacheValid] Using FileHashCache: ${javaFiles.size} files, ${changedFiles.size} changed, isValid=$isValid")
            if (!isValid && changedFiles.isNotEmpty()) {
                logger.info("[isCacheValid] Changed files: ${changedFiles.take(10).map { it.name }}")
            }
            return isValid
        }

        // FileHashCache 없으면 타임스탬프 기반
        val cacheTime = cacheFile.lastModified()
        val excludeDirs = listOf(".apk2project", "decompiler_stubs")

        val latestSourceFile = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filter { file -> excludeDirs.none { file.absolutePath.contains(it) } }
            .maxByOrNull { it.lastModified() }

        val latestSourceTime = latestSourceFile?.lastModified() ?: 0L

        val isValid = cacheTime > latestSourceTime
        logger.debug("[isCacheValid] Using timestamp: cacheTime=$cacheTime, latestSourceTime=$latestSourceTime, isValid=$isValid")
        return isValid
    }
}

// 캐시 데이터 클래스들
data class CallGraphCache(
    val classes: List<ClassCacheEntry>,
    val methods: List<MethodCacheEntry>,
    val edges: List<EdgeCacheEntry>
)

data class ClassCacheEntry(
    val packageName: String,
    val className: String,
    val filePath: String,
    val imports: List<String>,
    val isObfuscated: Boolean,
    val fields: List<FieldCacheEntry>,
    val methods: List<String>
)

data class MethodCacheEntry(
    val id: String,
    val className: String,
    val methodName: String,
    val signature: String,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val isObfuscated: Boolean
)

data class FieldCacheEntry(
    val name: String,
    val type: String,
    val isObfuscated: Boolean
)

data class EdgeCacheEntry(
    val source: String,
    val target: String
)

data class ParseResult(
    val totalFiles: Int,
    val parsedFiles: Int,
    val failedFiles: Int,
    val classCount: Int,
    val methodCount: Int
)

data class BuildGraphResult(
    val totalClasses: Int,
    val processedClasses: Int,
    val methodCount: Int,
    val edgeCount: Int
)

/**
 * Result of incremental parsing.
 */
data class IncrementalParseResult(
    val totalFiles: Int,
    val changedFiles: Int,
    val parsedFiles: Int,
    val failedFiles: Int,
    val classCount: Int,
    val methodCount: Int,
    val fromCache: Boolean,
    val changedFilesList: List<File> = emptyList()  // Actual list of changed files for Phase 2
)
