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
 */
class MethodCallGraphBuilder {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val parser = JavaParser()

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

        return ParseResult(
            totalFiles = javaFiles.size,
            parsedFiles = parsed.get(),
            failedFiles = failed.get(),
            classCount = classes.size,
            methodCount = methods.size
        )
    }

    /**
     * 단일 파일 파싱 (각 호출마다 새로운 parser 생성 - thread-safe)
     */
    private fun parseFile(file: File) {
        val fileParser = JavaParser()  // Thread-safe: 각 파일마다 새 parser
        val parseResult = fileParser.parse(file)
        if (!parseResult.isSuccessful) {
            throw RuntimeException("Parse failed: ${parseResult.problems}")
        }

        val cu = parseResult.result.orElseThrow()
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
                            val fileParser = JavaParser()  // Thread-safe: 각 클래스마다 새 parser
                            val parseResult = fileParser.parse(classNode.file)
                            if (!parseResult.isSuccessful) return@async

                            val cu = parseResult.result.orElseThrow()

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
     * 호출 대상 메소드 찾기
     */
    private fun findCalleeMethod(
        callerClass: ClassNode,
        scope: String,
        methodName: String
    ): String? {
        // 1. 같은 클래스 내 메소드
        if (scope.isEmpty() || scope == "this") {
            callerClass.methods.find { it.methodName == methodName }?.let {
                return it.id
            }
        }

        // 2. import된 클래스의 메소드
        callerClass.imports.forEach { import ->
            val importedClass = import.substringAfterLast(".")
            if (scope == importedClass || scope.startsWith("$importedClass.")) {
                classes[import]?.methods?.find { it.methodName == methodName }?.let {
                    return it.id
                }
            }
        }

        // 3. 같은 패키지 내 클래스
        val samePackageClass = if (callerClass.packageName.isNotEmpty()) {
            "${callerClass.packageName}.$scope"
        } else {
            scope
        }
        classes[samePackageClass]?.methods?.find { it.methodName == methodName }?.let {
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
     * 캐시가 유효한지 확인 (소스 디렉토리의 수정 시간과 비교)
     */
    fun isCacheValid(cacheFile: File, sourceDir: File): Boolean {
        if (!cacheFile.exists()) return false

        val cacheTime = cacheFile.lastModified()
        val latestSourceTime = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .maxOfOrNull { it.lastModified() } ?: 0L

        return cacheTime > latestSourceTime
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
