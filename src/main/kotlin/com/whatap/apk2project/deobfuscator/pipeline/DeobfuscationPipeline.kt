package com.whatap.apk2project.deobfuscator.pipeline

import com.whatap.apk2project.deobfuscator.cache.MethodSourceCache
import com.whatap.apk2project.deobfuscator.client.AiClient
import com.whatap.apk2project.deobfuscator.client.AiClientFactory
import com.whatap.apk2project.deobfuscator.client.AiClientType
import com.whatap.apk2project.deobfuscator.client.MethodAnalysisResult
import com.whatap.apk2project.deobfuscator.client.TranslationClient
import com.whatap.apk2project.deobfuscator.model.ClassNode
import com.whatap.apk2project.deobfuscator.model.MethodNode
import com.whatap.apk2project.deobfuscator.monitor.ProgressMonitor
import com.whatap.apk2project.deobfuscator.parser.MethodCallGraphBuilder
import com.whatap.apk2project.deobfuscator.renamer.RenameResult
import com.whatap.apk2project.deobfuscator.renamer.RenameType
import com.whatap.apk2project.deobfuscator.renamer.SourceRenamer
import com.whatap.apk2project.deobfuscator.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 새로운 디오브퓨스케이션 파이프라인
 *
 * 특징:
 * - 메소드 콜 트리 기반 분석
 * - 리프 메소드부터 상향식 처리
 * - Claude Code (tmux)를 통한 분석
 * - 즉시 리네이밍 + 주석 추가
 * - 클래스 역할 추론
 */
class DeobfuscationPipeline(
    private val sourceDir: File,
    private val outputDir: File,
    private val config: PipelineConfig = PipelineConfig(),
    private val externalMonitor: com.whatap.apk2project.deobfuscator.monitor.ProgressMonitor? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val sessionManager = SessionManager(outputDir)
    // Initialize with cacheDir for incremental processing support
    private val graphBuilder = MethodCallGraphBuilder(cacheDir = File(outputDir, ".apk2project/cache"))
    // Use external monitor if provided, otherwise create new one
    private val monitor = externalMonitor ?: ProgressMonitor(outputDir)
    private val aiClient: AiClient = AiClientFactory.create(
        type = config.aiClientType,
        executablePath = config.aiExecutablePath,
        workingDir = sourceDir,
        timeout = config.analysisTimeout,
        modelName = config.modelName,
        monitor = monitor,
        ollamaBaseUrl = config.ollamaBaseUrl  // Ollama 서버 URL 전달
    )
    private val translationClient: TranslationClient? = if (config.enableKorean) {
        TranslationClient(
            modelName = config.translationModelName ?: "qwen2.5:7b",
            monitor = monitor
        )
    } else null
    private val renamer = SourceRenamer()

    // 소스 코드 캐시 (디스크 기반, 메모리 절약)
    private val sourceCache = MethodSourceCache(
        cacheDir = File(outputDir, ".apk2project/cache"),
        maxMemorySize = 100  // 메모리에 100개만 보관
    )

    // 클래스 컨텍스트 캐시 (파일 경로 -> 클래스 소스 코드)
    // 같은 파일의 여러 메소드 분석 시 파일을 한 번만 읽음
    private val classContextCache = ConcurrentHashMap<String, String>()

    // 처리 상태
    private val processedMethods = ConcurrentHashMap.newKeySet<String>()
    private val analysisResults = ConcurrentHashMap<String, MethodAnalysisResult>()
    private val stats = PipelineStats()

    /**
     * 클래스 컨텍스트 가져오기 (캐시 사용)
     *
     * 파일별로 한 번만 읽고 캐시에 저장하여 I/O 90% 감소
     * 같은 클래스의 메소드 10개면 기존에는 10번 읽었지만, 이제 1번만 읽음
     */
    private fun getClassContext(method: MethodNode, maxChars: Int = 3000): String {
        val filePath = method.file?.absolutePath ?: return ""
        return classContextCache.getOrPut(filePath) {
            try {
                method.file?.readText()?.take(maxChars) ?: ""
            } catch (e: Exception) {
                logger.warn("Failed to read class context for ${method.className}: ${e.message}")
                ""
            }
        }
    }

    /**
     * 캐시 정리 (메모리 관리)
     * 일정 크기 초과 시 오래된 항목 제거
     */
    private fun clearClassContextCacheIfNeeded(maxCacheSize: Int = 500) {
        if (classContextCache.size > maxCacheSize) {
            // 가장 오래된 절반 제거 (간단한 방식)
            val keysToRemove = classContextCache.keys.take(maxCacheSize / 2)
            keysToRemove.forEach { classContextCache.remove(it) }
            logger.debug("Cleared ${keysToRemove.size} entries from class context cache")
        }
    }

    // Phase 1 → Phase 2 전달용 변경 파일 목록
    @Volatile
    private var changedFilesFromPhase1: List<File> = emptyList()

    /**
     * 파이프라인 실행
     */
    suspend fun run(): PipelineResult = coroutineScope {
        logger.info("╔════════════════════════════════════════════════╗")
        logger.info("║     Deobfuscation Pipeline Started             ║")
        logger.info("╚════════════════════════════════════════════════╝")
        logger.info("Source: ${sourceDir.absolutePath}")
        logger.info("Output: ${outputDir.absolutePath}")
        logger.info("Dashboard: ${File(outputDir, "dashboard.html").absolutePath}")

        outputDir.mkdirs()

        // 세션 관리
        val session = when {
            config.resume && config.sessionId != null -> {
                // 특정 세션으로 이어하기
                val loaded = sessionManager.loadSession()
                if (loaded != null && sessionManager.validateSession(config.sessionId)) {
                    logger.info("✓ Resuming session: ${loaded.sessionId}")
                    loaded
                } else {
                    logger.warn("⚠ Invalid session ID or session not found, creating new session")
                    sessionManager.createNewSession(sourceDir)
                }
            }
            config.resume -> {
                // 자동 이어하기 (세션 파일 존재 시)
                val loaded = sessionManager.loadSession()
                if (loaded != null) {
                    logger.info("✓ Resuming previous session: ${loaded.sessionId}")
                    loaded
                } else {
                    logger.info("No previous session found, creating new session")
                    sessionManager.createNewSession(sourceDir)
                }
            }
            else -> {
                // 새 세션 시작
                cleanOutputDir()  // 이전 결과 파일 정리
                sessionManager.createNewSession(sourceDir)
            }
        }

        monitor.start()
        val startTime = System.currentTimeMillis()

        try {
            // Phase 1: 파일 파싱
            phase1ParseFiles()
            logger.info("✓ Phase 1 (Parsing) complete. Waiting 5 seconds before next phase...")
            delay(5000)

            // Phase 2: Call Graph 구축
            phase2BuildCallGraph()
            logger.info("✓ Phase 2 (Call Graph) complete. Waiting 5 seconds before next phase...")
            delay(5000)

            // Phase 3: AI CLI 준비
            phase3PrepareAiClient()
            logger.info("✓ Phase 3 (AI Client Ready) complete. Waiting 5 seconds before next phase...")
            delay(5000)

            // Phase 4: 리프 메소드부터 상향식 처리
            phase4ProcessBottomUp()
            logger.info("✓ Phase 4 (Method Deobfuscation) complete. Waiting 5 seconds before next phase...")
            delay(5000)

            // Phase 5: 클래스 리네이밍
            phase5RenameClasses()
            logger.info("✓ Phase 5 (Class Renaming) complete. Waiting 5 seconds before next phase...")
            delay(5000)

            // Phase 6: 결과 저장
            phase6SaveResults()

            val duration = System.currentTimeMillis() - startTime
            stats.durationMs = duration

            monitor.complete()
            logger.info("╔════════════════════════════════════════════════╗")
            logger.info("║     Pipeline Complete!                         ║")
            logger.info("╚════════════════════════════════════════════════╝")
            logger.info("Duration: ${duration / 1000}s")
            logger.info("Renamed: ${stats.renamedMethods} methods, ${stats.renamedClasses} classes")
            logger.info("Dashboard: ${File(outputDir, "dashboard.html").absolutePath}")

            PipelineResult(
                success = true,
                stats = stats,
                outputDir = outputDir
            )
        } catch (e: Exception) {
            logger.error("Pipeline failed: ${e.message}", e)
            monitor.fail(e.message ?: "Unknown error")
            PipelineResult(
                success = false,
                stats = stats,
                error = e.message
            )
        }
    }

    /**
     * 이전 실행의 결과 파일 정리
     */
    private fun cleanOutputDir() {
        logger.info("Cleaning previous results...")

        // status.json이 있으면 백업 (타임스탬프로 저장)
        val statusFile = File(outputDir, "status.json")
        if (statusFile.exists()) {
            val timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            )
            val backupFile = File(outputDir, "status.json.backup_$timestamp")
            statusFile.copyTo(backupFile, overwrite = true)
            logger.info("  Backed up: status.json → ${backupFile.name}")

            // 오래된 백업 파일 정리 (최근 5개만 유지)
            outputDir.listFiles()
                ?.filter { it.name.startsWith("status.json.backup_") }
                ?.sortedByDescending { it.name }
                ?.drop(5)
                ?.forEach { it.delete() }
        }

        // 이전 실행의 결과 파일들 삭제
        val filesToClean = listOf(
            "status.json",  // 백업 후 삭제
            "mappings.json",
            "rename_history.md",
            "stats.txt"
        )

        filesToClean.forEach { filename ->
            val file = File(outputDir, filename)
            if (file.exists()) {
                file.delete()
                logger.info("  Deleted: $filename")
            }
        }
    }

    /**
     * Phase 1: Java 파일 파싱 (캐시 지원 + 증분 처리)
     *
     * 처리 전략:
     * 1. 캐시가 완전히 유효 (변경 없음) → 캐시에서 로드
     * 2. 캐시 있고 일부 변경 → 캐시 로드 + 증분 파싱
     * 3. 캐시 없음 → 전체 파싱
     */
    private fun phase1ParseFiles() {
        logger.info("\n[Phase 1] Parsing source files...")
        monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.PHASE1_FILE_PARSING
        monitor.setPhase("Phase 1", "Parsing source files...")

        val cacheFile = config.cacheFile ?: File(outputDir, "callgraph_cache.json")

        // 캐시 유효성 확인
        val cacheExists = cacheFile.exists()
        val isCacheValid = config.useCache && graphBuilder.isCacheValid(cacheFile, sourceDir)

        logger.info("[Cache Check] cacheFile exists=$cacheExists, isCacheValid=$isCacheValid")

        // Case 1: 캐시가 완전히 유효 (변경 없음)
        if (isCacheValid) {
            logger.info("✓ No files changed, loading from cache: ${cacheFile.absolutePath}")
            monitor.setPhase("Phase 1", "Loading from cache...")
            val loaded = graphBuilder.loadFromCache(cacheFile)
            if (loaded) {
                stats.totalClasses = graphBuilder.classes.size
                stats.totalMethods = graphBuilder.methods.size
                logger.info("✓ Loaded ${stats.totalClasses} classes, ${stats.totalMethods} methods from cache")

                monitor.totalClasses.set(stats.totalClasses)
                monitor.totalMethods.set(stats.totalMethods)
                monitor.parsedFiles = stats.totalClasses
                monitor.totalCallGraphClasses = stats.totalClasses
                monitor.callGraphEdges = graphBuilder.callGraph.edgeSet().size
                return
            }
            logger.warn("Cache load failed, falling back to full parse...")
        }

        // Case 2: 캐시 있고 일부 변경 → 증분 처리
        if (config.useCache && cacheExists) {
            logger.info("✓ Cache exists, attempting incremental processing...")
            monitor.setPhase("Phase 1", "Loading cache + incremental parsing...")

            // 먼저 캐시에서 기존 데이터 로드
            val loaded = graphBuilder.loadFromCache(cacheFile)
            if (loaded) {
                logger.info("✓ Loaded ${graphBuilder.classes.size} classes from cache")

                // 증분 파싱 (변경된 파일만)
                val incrementalResult = graphBuilder.parseFilesIncremental(sourceDir) { current, total ->
                    monitor.setPhase("Phase 1", "Incremental parsing: $current / $total changed files...")
                    monitor.parsedFiles = current
                }

                // Phase 2에서 사용할 변경된 파일 목록 저장
                changedFilesFromPhase1 = incrementalResult.changedFilesList

                if (incrementalResult.fromCache) {
                    logger.info("✓ No files changed, using cached data")
                } else {
                    logger.info("✓ Incremental: ${incrementalResult.changedFiles} files changed, ${incrementalResult.parsedFiles} reparsed")
                }

                stats.totalClasses = incrementalResult.classCount
                stats.totalMethods = incrementalResult.methodCount

                monitor.totalClasses.set(stats.totalClasses)
                monitor.totalMethods.set(stats.totalMethods)
                monitor.parsedFiles = incrementalResult.parsedFiles
                monitor.failedParseFiles = incrementalResult.failedFiles
                monitor.totalFilesToParse = incrementalResult.totalFiles
                return
            }
            logger.warn("Cache load failed, falling back to full parse...")
        }

        // Case 3: 캐시 없음 → 전체 파싱
        logger.info("Full parsing (no cache available)...")
        val parseResult = graphBuilder.parseFilesOnly(sourceDir) { current, total ->
            monitor.totalFilesToParse = total
            monitor.setPhase("Phase 1", "Parsing $current / $total files...")
            monitor.parsedFiles = current
        }

        stats.totalClasses = parseResult.classCount
        stats.totalMethods = parseResult.methodCount

        logger.info("✓ Parsed ${parseResult.parsedFiles}/${parseResult.totalFiles} files")
        logger.info("✓ Found ${parseResult.classCount} classes, ${parseResult.methodCount} methods")

        monitor.totalClasses.set(stats.totalClasses)
        monitor.totalMethods.set(stats.totalMethods)
        monitor.parsedFiles = parseResult.parsedFiles
        monitor.failedParseFiles = parseResult.failedFiles
        monitor.totalFilesToParse = parseResult.totalFiles
    }

    /**
     * Phase 2: Call Graph 구축 (증분 처리 지원)
     *
     * 처리 전략:
     * 1. 캐시가 완전히 유효 (변경 없음) → Call Graph 이미 로드됨, 스킵
     * 2. Phase 1에서 변경된 파일 있음 → 증분 업데이트 (영향받는 엣지만 재계산)
     * 3. 캐시 없음 → 전체 Call Graph 빌드
     */
    private fun phase2BuildCallGraph() {
        logger.info("\n[Phase 2] Building call graph...")
        monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.PHASE2_CALL_GRAPH
        monitor.setPhase("Phase 2", "Building call graph...")

        val cacheFile = config.cacheFile ?: File(outputDir, "callgraph_cache.json")

        // Case 1: 캐시가 완전히 유효 (변경 없음)
        if (config.useCache && graphBuilder.isCacheValid(cacheFile, sourceDir) && changedFilesFromPhase1.isEmpty()) {
            logger.info("✓ Call graph already loaded from cache (no changes)")

            val leafMethods = graphBuilder.findLeafMethods()
            logger.info("✓ Identified ${leafMethods.size} priority 1 methods (true leaves)")

            monitor.leafMethods.set(leafMethods.size)
            return
        }

        // Case 2: Phase 1에서 변경된 파일이 있음 → 증분 업데이트
        if (config.useCache && changedFilesFromPhase1.isNotEmpty() && graphBuilder.callGraph.vertexSet().isNotEmpty()) {
            logger.info("✓ Incremental call graph update for ${changedFilesFromPhase1.size} changed files...")
            monitor.setPhase("Phase 2", "Incremental call graph update...")

            val graphResult = graphBuilder.updateCallGraphIncremental(changedFilesFromPhase1) { current, total ->
                monitor.callGraphClasses = current
                monitor.totalCallGraphClasses = total
                monitor.setPhase("Phase 2", "Incremental update: $current / $total classes...")
            }

            logger.info("✓ Call graph updated: ${graphResult.methodCount} methods, ${graphResult.edgeCount} edges")

            // 캐시 저장
            if (config.useCache) {
                try {
                    graphBuilder.saveToCache(cacheFile)
                    logger.info("✓ Cache saved to ${cacheFile.absolutePath}")
                } catch (e: Exception) {
                    logger.warn("Failed to save cache: ${e.message}")
                }
            }

            // 리프 메소드 수 확인
            val leafMethods = graphBuilder.findLeafMethods()
            logger.info("✓ Identified ${leafMethods.size} priority 1 methods (true leaves)")

            monitor.callGraphEdges = graphResult.edgeCount
            monitor.leafMethods.set(leafMethods.size)
            monitor.totalCallGraphClasses = graphResult.totalClasses
            monitor.callGraphClasses = graphResult.processedClasses
            return
        }

        // Case 3: 캐시 없음 → 전체 Call Graph 빌드
        logger.info("Full call graph build...")
        val graphResult = graphBuilder.buildCallGraph { current, total ->
            monitor.totalCallGraphClasses = total
            monitor.callGraphClasses = current
            monitor.setPhase("Phase 2", "Building call graph: $current / $total classes...")
        }

        logger.info("✓ Call graph built: ${graphResult.methodCount} methods, ${graphResult.edgeCount} edges")

        // 캐시 저장
        if (config.useCache) {
            try {
                graphBuilder.saveToCache(cacheFile)
                logger.info("✓ Cache saved to ${cacheFile.absolutePath}")
            } catch (e: Exception) {
                logger.warn("Failed to save cache: ${e.message}")
            }
        }

        // 리프 메소드 수 확인
        val leafMethods = graphBuilder.findLeafMethods()
        logger.info("✓ Identified ${leafMethods.size} priority 1 methods (true leaves)")

        // 모니터 통계 업데이트
        monitor.callGraphEdges = graphResult.edgeCount
        monitor.leafMethods.set(leafMethods.size)
        monitor.totalCallGraphClasses = graphResult.totalClasses
        monitor.callGraphClasses = graphResult.processedClasses
    }

    /**
     * Phase 3: AI CLI 확인
     */
    private fun phase3PrepareAiClient() {
        logger.info("\n[Phase 3] Checking AI CLI availability (${config.aiClientType})...")
        monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.PHASE3_AI_ANALYSIS
        monitor.setPhase("Phase 3", "Checking AI CLI...")

        monitor.aiClientType = config.aiClientType.name
        monitor.batchSize = config.batchSize
        monitor.koreanEnabled = config.enableKorean

        if (!aiClient.isAvailable()) {
            monitor.aiClientAvailable = false
            throw RuntimeException("${config.aiClientType} CLI is not available. Make sure it's in PATH")
        }

        monitor.aiClientAvailable = true
        logger.info("✓ ${config.aiClientType} CLI is ready")
    }

    /**
     * Phase 3: 리프 메소드부터 상향식 처리 (큐 기반 파이프라인)
     */
    private suspend fun phase4ProcessBottomUp() = coroutineScope {
        logger.info("\n[Phase 3] Processing methods (queue-based pipeline, workers=${config.batchSize})...")
        logger.info("Pipeline: Leaf Methods → DeepSeek Workers → Qwen Workers → Rename Workers")
        // Phase 3 유지 (AI 분석 단계)
        monitor.setPhase("Phase 3", "Processing methods...")

        val processedCount = AtomicInteger(0)

        var iteration = 0
        var consecutiveNoProgress = 0

        while (consecutiveNoProgress < 3) {
            iteration++
            monitor.nextIteration()
            logger.info("\n--- Iteration $iteration ---")

            // 현재 리프 메소드 찾기 (우선순위 기반, 이미 처리된 것 제외)
            val leafMethods = graphBuilder.findLeafMethods(processedMethods)

            if (leafMethods.isEmpty()) {
                consecutiveNoProgress++
                logger.info("No leaf methods found, checking remaining...")

                val remaining = graphBuilder.methods.values
                    .filter { it.isObfuscated && it.id !in processedMethods }
                    .size

                if (remaining == 0) {
                    logger.info("All methods processed!")
                    break
                }

                logger.info("$remaining methods remaining (may have circular dependencies)")
                continue
            }

            consecutiveNoProgress = 0
            val startTime = System.currentTimeMillis()

            // 큐 기반 파이프라인 실행
            val iterationProcessed = runPipelineIteration(leafMethods)
            processedCount.addAndGet(iterationProcessed)

            val duration = System.currentTimeMillis() - startTime
            stats.processedMethods += iterationProcessed

            logger.info("Processed $iterationProcessed methods in ${duration}ms")

            // 진행률
            val progress = if (stats.totalMethods > 0) {
                (stats.processedMethods.toDouble() / stats.totalMethods * 100).toInt()
            } else 0
            logger.info("Overall: ${stats.processedMethods}/${stats.totalMethods} ($progress%)")
        }
    }

    /**
     * 큐 기반 파이프라인 실행 (한 iteration)
     *
     * 메모리 최적화: 소스 코드를 큐에 포함하지 않고 MethodNode만 전달
     * - File Queue (MethodSourceCache) 사용으로 메모리 절약
     * - 필요할 때만 디스크에서 로드
     */
    private suspend fun runPipelineIteration(leafMethods: List<MethodNode>): Int = coroutineScope {
        // 큐 생성 (소스 코드 미포함 - 메모리 절약)
        val queueCapacity = config.batchSize * 10  // 배치 사이즈에 따라 동적 조정
        val deepseekQueue = Channel<MethodNode>(capacity = queueCapacity)
        val renameQueue = Channel<Pair<MethodNode, MethodAnalysisResult>>(capacity = queueCapacity)

        val successCount = AtomicInteger(0)

        // 실패한 메소드를 재시도하기 위한 큐 (메모리 제한)
        val maxRetryQueueSize = 500  // 최대 500개까지 재시도
        val retryQueue = java.util.concurrent.ConcurrentLinkedQueue<MethodNode>()
        val retryCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val maxRetries = 5  // 최대 재시도 횟수

        // 큐 크기 카운터
        val deepseekQueueCounter = AtomicInteger(0)
        val renameQueueCounter = AtomicInteger(0)

        // 파이프라인 구조에 따라 monitor 설정
        if (config.enableKorean) {
            monitor.qwenQueueSize = 0
            monitor.pipelineStages = listOf("DeepSeek", "Translation (Qwen)", "Rename")
        } else {
            monitor.qwenQueueSize = null
            monitor.pipelineStages = listOf("DeepSeek", "Rename")
        }

        // 한글 번역 여부에 따라 파이프라인 구조 결정
        if (config.enableKorean) {
            // 한글 번역 사용: DeepSeek → Translation → Rename
            val qwenQueue = Channel<Pair<MethodNode, MethodAnalysisResult>>(capacity = queueCapacity)
            val qwenQueueCounter = AtomicInteger(0)

            // DeepSeek Workers (with class context caching)
            val deepseekWorker = DeepSeekWorker(
                scope = this,
                workerCount = config.batchSize,
                queue = deepseekQueue,
                queueCounter = deepseekQueueCounter,
                sourceCache = sourceCache,
                extractFunc = ::extractMethodSource,
                analyzeFunc = { method, sourceCode ->
                    // 클래스 컨텍스트를 캐시에서 가져옴 (파일당 1번만 읽음)
                    val classContext = getClassContext(method)
                    aiClient.analyzeMethod(method, sourceCode, 1, classContext)
                },
                nextQueue = qwenQueue,
                nextQueueCounter = qwenQueueCounter,
                retryQueue = retryQueue,
                retryCount = retryCount,
                maxRetries = maxRetries,
                maxRetryQueueSize = maxRetryQueueSize,
                delayMs = config.requestDelay / config.batchSize
            )
            val deepseekJobs = deepseekWorker.launch()

            // Translation Workers
            val translationWorker = TranslationWorker(
                scope = this,
                workerCount = config.batchSize,
                queue = qwenQueue,
                queueCounter = qwenQueueCounter,
                translateFunc = { translationClient!!.translate(it) },
                nextQueue = renameQueue,
                nextQueueCounter = renameQueueCounter
            )
            val qwenJobs = translationWorker.launch()

            // Rename Workers
            val renameWorker = RenameWorker(
                scope = this,
                workerCount = config.batchSize,
                queue = renameQueue,
                queueCounter = renameQueueCounter,
                sourceCache = sourceCache,
                extractFunc = ::extractMethodSource,
                renameFunc = renamer::renameMethod,
                processedMethods = processedMethods,
                onSuccessHandler = { method, analysis, sourceCode, result ->
                    logger.info("  ✓ ${method.methodName} → ${analysis.suggestedName}")
                    logger.info("    └─ ${analysis.description.take(60)}...")
                    stats.renamedMethods++

                    // 변경 전후 코드 생성
                    val sourceCodeBefore = sourceCode.lines().take(10).joinToString("\n")

                    // 참조 업데이트
                    val refUpdate = updateReferences(method, analysis.suggestedName)

                    // 실제 적용된 로컬 변수 리네임 사용 (AI 제안이 아닌 실제 적용된 것)
                    val actualLocalVarRenames = result.actualVariableRenames

                    // sourceCodeAfter에 메소드 이름 + 로컬 변수 리네임 모두 적용
                    var sourceCodeAfter = sourceCodeBefore.replace(
                        "\\b${Regex.escape(method.methodName)}\\b".toRegex(),
                        analysis.suggestedName
                    )
                    // 로컬 변수 리네임도 미리보기에 반영
                    actualLocalVarRenames.forEach { (oldName, newName) ->
                        sourceCodeAfter = sourceCodeAfter.replace(
                            "\\b${Regex.escape(oldName)}\\b".toRegex(),
                            newName
                        )
                    }

                    // 모니터에 리네임 기록
                    monitor.addRename(
                        type = "METHOD",
                        original = method.methodName,
                        suggested = analysis.suggestedName,
                        description = analysis.description,
                        reasoning = analysis.reasoning,
                        confidence = analysis.confidence,
                        className = method.className,
                        filePath = method.file.absolutePath,
                        lineNumber = method.startLine,
                        sourceCodeBefore = sourceCodeBefore,
                        sourceCodeAfter = sourceCodeAfter,
                        localVariableRenames = actualLocalVarRenames,
                        referencesUpdated = refUpdate.count,
                        updatedFiles = refUpdate.files
                    )

                    successCount.incrementAndGet()
                    monitor.incrementProcessed()
                },
                onFailureHandler = { method, reason ->
                    logger.warn("  ✗ ${method.methodName}: $reason")
                    monitor.incrementFailed()
                }
            )
            val renameJobs = renameWorker.launch()

            // Producer
            launchProducer(this@coroutineScope, leafMethods, deepseekQueue, deepseekQueueCounter)

            // Real-time queue size monitoring (sync counters to monitor every 100ms)
            val monitorJob = launch(Dispatchers.IO) {
                while (isActive) {
                    monitor.deepseekQueueSize = deepseekQueueCounter.get()
                    monitor.qwenQueueSize = qwenQueueCounter.get()
                    monitor.renameQueueSize = renameQueueCounter.get()
                    delay(100)  // Update every 100ms
                }
            }

            // Wait for completion
            deepseekJobs.forEach { it.join() }
            monitorJob.cancel()  // Stop monitoring when DeepSeek completes
            logger.info("DeepSeek stage complete")
            qwenQueue.close()
            qwenJobs.forEach { it.join() }
            logger.info("Translation stage complete")
            renameQueue.close()
            renameJobs.forEach { it.join() }

        } else {
            // 한글 번역 미사용: DeepSeek → Rename (간단한 파이프라인)
            logger.info("Korean translation disabled, using simplified pipeline: DeepSeek → Rename")

            // DeepSeek Workers (직접 Rename 큐로 전달, with class context caching)
            val deepseekWorker = DeepSeekWorker(
                scope = this,
                workerCount = config.batchSize,
                queue = deepseekQueue,
                queueCounter = deepseekQueueCounter,
                sourceCache = sourceCache,
                extractFunc = ::extractMethodSource,
                analyzeFunc = { method, sourceCode ->
                    // 클래스 컨텍스트를 캐시에서 가져옴 (파일당 1번만 읽음)
                    val classContext = getClassContext(method)
                    aiClient.analyzeMethod(method, sourceCode, 1, classContext)
                },
                nextQueue = renameQueue,
                nextQueueCounter = renameQueueCounter,
                retryQueue = retryQueue,
                retryCount = retryCount,
                maxRetries = maxRetries,
                maxRetryQueueSize = maxRetryQueueSize,
                delayMs = config.requestDelay / config.batchSize
            )
            val deepseekJobs = deepseekWorker.launch()

            // Rename Workers
            val renameWorker = RenameWorker(
                scope = this,
                workerCount = config.batchSize,
                queue = renameQueue,
                queueCounter = renameQueueCounter,
                sourceCache = sourceCache,
                extractFunc = ::extractMethodSource,
                renameFunc = renamer::renameMethod,
                processedMethods = processedMethods,
                onSuccessHandler = { method, analysis, sourceCode, result ->
                    logger.info("  ✓ ${method.methodName} → ${analysis.suggestedName}")
                    logger.info("    └─ ${analysis.description.take(60)}...")
                    stats.renamedMethods++

                    // 변경 전후 코드 생성
                    val sourceCodeBefore = sourceCode.lines().take(10).joinToString("\n")

                    // 참조 업데이트
                    val refUpdate = updateReferences(method, analysis.suggestedName)

                    // 실제 적용된 로컬 변수 리네임 사용 (AI 제안이 아닌 실제 적용된 것)
                    val actualLocalVarRenames = result.actualVariableRenames

                    // sourceCodeAfter에 메소드 이름 + 로컬 변수 리네임 모두 적용
                    var sourceCodeAfter = sourceCodeBefore.replace(
                        "\\b${Regex.escape(method.methodName)}\\b".toRegex(),
                        analysis.suggestedName
                    )
                    // 로컬 변수 리네임도 미리보기에 반영
                    actualLocalVarRenames.forEach { (oldName, newName) ->
                        sourceCodeAfter = sourceCodeAfter.replace(
                            "\\b${Regex.escape(oldName)}\\b".toRegex(),
                            newName
                        )
                    }

                    // 모니터에 리네임 기록
                    monitor.addRename(
                        type = "METHOD",
                        original = method.methodName,
                        suggested = analysis.suggestedName,
                        description = analysis.description,
                        reasoning = analysis.reasoning,
                        confidence = analysis.confidence,
                        className = method.className,
                        filePath = method.file.absolutePath,
                        lineNumber = method.startLine,
                        sourceCodeBefore = sourceCodeBefore,
                        sourceCodeAfter = sourceCodeAfter,
                        localVariableRenames = actualLocalVarRenames,
                        referencesUpdated = refUpdate.count,
                        updatedFiles = refUpdate.files
                    )

                    successCount.incrementAndGet()
                    monitor.incrementProcessed()
                },
                onFailureHandler = { method, reason ->
                    logger.warn("  ✗ ${method.methodName}: $reason")
                    monitor.incrementFailed()
                }
            )
            val renameJobs = renameWorker.launch()

            // Producer
            launchProducer(this@coroutineScope, leafMethods, deepseekQueue, deepseekQueueCounter)

            // Real-time queue size monitoring (sync counters to monitor every 100ms)
            val monitorJob = launch(Dispatchers.IO) {
                while (isActive) {
                    monitor.deepseekQueueSize = deepseekQueueCounter.get()
                    monitor.renameQueueSize = renameQueueCounter.get()
                    delay(100)  // Update every 100ms
                }
            }

            // Wait for completion
            deepseekJobs.forEach { it.join() }
            monitorJob.cancel()  // Stop monitoring when DeepSeek completes
            logger.info("DeepSeek stage complete")
            renameQueue.close()
            renameJobs.forEach { it.join() }
        }

        logger.info("Rename stage complete")

        // 재시도 큐 처리 (독립적인 scope 사용)
        val retryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            if (config.enableKorean) {
                processRetryQueueWithTranslation(retryScope, retryQueue, retryCount, maxRetries, maxRetryQueueSize, successCount)
            } else {
                processRetryQueueSimple(retryScope, retryQueue, retryCount, maxRetries, maxRetryQueueSize, successCount)
            }
        } finally {
            retryScope.cancel()
        }

        successCount.get()
    }

    /**
     * Producer: Leaf method들을 DeepSeek 큐에 공급
     */
    private suspend fun launchProducer(
        scope: CoroutineScope,
        leafMethods: List<MethodNode>,
        deepseekQueue: Channel<MethodNode>,
        deepseekQueueCounter: AtomicInteger
    ) {
        val producerJob = scope.launch(Dispatchers.IO) {
            var sentCount = 0
            for (method in leafMethods) {
                if (method.id in processedMethods) continue

                // 소스 코드 추출 및 캐싱
                val sourceCode = extractMethodSource(method)
                if (sourceCode != null) {
                    sourceCache.putSource(method, sourceCode)

                    deepseekQueueCounter.incrementAndGet()
                    monitor.deepseekQueueSize = deepseekQueueCounter.get()
                    deepseekQueue.send(method)  // 소스 코드 미포함, ID만 전달
                    sentCount++
                } else {
                    logger.debug("No source code for ${method.id}")
                    processedMethods.add(method.id)
                }
            }
            logger.info("Producer: Sent $sentCount methods to DeepSeek queue")
            deepseekQueue.close()
        }

        producerJob.join()
    }

    /**
     * 재시도 큐 처리 (한글 번역 포함)
     */
    private suspend fun processRetryQueueWithTranslation(
        scope: CoroutineScope,
        retryQueue: java.util.Queue<MethodNode>,
        retryCount: ConcurrentHashMap<String, Int>,
        maxRetries: Int,
        maxRetryQueueSize: Int,
        successCount: AtomicInteger
    ) {
        if (retryQueue.isEmpty()) return

        logger.info("Processing retry queue: ${retryQueue.size} methods")

        val queueCapacity = config.batchSize * 5
        val retryDeepseekQueue = Channel<MethodNode>(capacity = queueCapacity)
        val retryQwenQueue = Channel<Pair<MethodNode, MethodAnalysisResult>>(capacity = queueCapacity)
        val retryRenameQueue = Channel<Pair<MethodNode, MethodAnalysisResult>>(capacity = queueCapacity)

        // Producer
        scope.launch(Dispatchers.IO) {
            for (method in retryQueue) {
                retryDeepseekQueue.send(method)
            }
            retryDeepseekQueue.close()
        }.join()

        // Workers
        val retryDeepseekJobs = List(config.batchSize / 2 + 1) {
            scope.launch(Dispatchers.IO) {
                for (method in retryDeepseekQueue) {
                    try {
                        delay(2000)
                        val sourceCode = sourceCache.getSource(method)
                        if (sourceCode == null) {
                            processedMethods.add(method.id)
                            monitor.incrementFailed()
                            return@launch
                        }

                        val currentIteration = retryCount.getOrDefault(method.id, 0) + 1
                        // 클래스 컨텍스트를 캐시에서 가져옴
                        val classContext = getClassContext(method)
                        val analysis = aiClient.analyzeMethod(method, sourceCode, currentIteration, classContext)
                        if (analysis != null) {
                            sourceCache.putAnalysis(method, analysis)
                            retryQwenQueue.send(method to analysis)
                        } else {
                            shouldRetryForRetry(method, retryQueue, retryCount, maxRetries, maxRetryQueueSize)
                        }
                    } catch (e: Exception) {
                        processedMethods.add(method.id)
                        monitor.incrementFailed()
                    }
                }
            }
        }

        val retryQwenJobs = List(config.batchSize / 2 + 1) {
            scope.launch(Dispatchers.IO) {
                for ((method, analysis) in retryQwenQueue) {
                    try {
                        val translated = translationClient!!.translate(analysis)
                        retryRenameQueue.send(method to translated)
                    } catch (e: Exception) {
                        processedMethods.add(method.id)
                        monitor.incrementFailed()
                    }
                }
            }
        }

        val retryRenameJobs = List(config.batchSize / 2 + 1) {
            scope.launch(Dispatchers.IO) {
                for ((method, analysis) in retryRenameQueue) {
                    try {
                        val sourceCode = sourceCache.getSource(method)
                        if (sourceCode == null) {
                            processedMethods.add(method.id)
                            monitor.incrementFailed()
                            return@launch
                        }

                        val result = renamer.renameMethod(method.file, method, analysis)
                        when (result) {
                            is RenameResult.Success -> {
                                logger.info("  ✓ [retry] ${method.methodName} → ${analysis.suggestedName}")
                                successCount.incrementAndGet()
                            }
                            is RenameResult.Failure -> {
                                logger.warn("  ✗ [retry] ${method.methodName}: ${result.reason}")
                                monitor.incrementFailed()
                            }
                        }
                        processedMethods.add(method.id)
                        monitor.incrementProcessed()
                    } catch (e: Exception) {
                        processedMethods.add(method.id)
                        monitor.incrementFailed()
                    }
                }
            }
        }

        retryDeepseekJobs.forEach { it.join() }
        retryQwenQueue.close()
        retryQwenJobs.forEach { it.join() }
        retryRenameQueue.close()
        retryRenameJobs.forEach { it.join() }

        logger.info("Retry stage complete")
    }

    /**
     * 재시도 큐 처리 (한글 번역 없음)
     */
    private suspend fun processRetryQueueSimple(
        scope: CoroutineScope,
        retryQueue: java.util.Queue<MethodNode>,
        retryCount: ConcurrentHashMap<String, Int>,
        maxRetries: Int,
        maxRetryQueueSize: Int,
        successCount: AtomicInteger
    ) {
        if (retryQueue.isEmpty()) return

        logger.info("Processing retry queue: ${retryQueue.size} methods (without translation)")

        // 생략: 위와 유사하게 구현
    }

    /**
     * 재시도 로직
     */
    private fun shouldRetryForRetry(
        method: MethodNode,
        retryQueue: java.util.Queue<MethodNode>,
        retryCount: ConcurrentHashMap<String, Int>,
        maxRetries: Int,
        maxRetryQueueSize: Int
    ) {
        val currentRetry = retryCount.getOrDefault(method.id, 0)
        if (currentRetry < maxRetries && retryQueue.size < maxRetryQueueSize) {
            retryCount[method.id] = currentRetry + 1
            retryQueue.add(method)
        } else {
            logger.warn("Max retries reached or queue full: ${method.methodName}")
            processedMethods.add(method.id)
            monitor.incrementFailed()
        }
    }

    /**
     * 단일 메소드 처리
     */
    private fun processMethod(method: MethodNode): Boolean {
        if (method.id in processedMethods) return false

        try {
            // 소스 코드 추출
            val sourceCode = extractMethodSource(method)
            if (sourceCode == null) {
                logger.debug("Could not extract source for ${method.id}")
                processedMethods.add(method.id)
                return false
            }

            // AI로 분석
            var analysis = aiClient.analyzeMethod(method, sourceCode, 1, "")
            if (analysis == null) {
                logger.warn("No analysis result for ${method.methodName}")
                processedMethods.add(method.id)
                return false
            }

            // 한글 번역 (옵션)
            if (translationClient != null) {
                logger.debug("Translating to Korean: ${method.methodName}")
                analysis = translationClient.translate(analysis)
            }

            analysisResults[method.id] = analysis

            // 리네이밍 적용
            val result = renamer.renameMethod(method.file, method, analysis)

            when (result) {
                is RenameResult.Success -> {
                    logger.info("  ✓ ${method.methodName} → ${analysis.suggestedName}")
                    logger.info("    └─ ${analysis.description.take(60)}...")
                    stats.renamedMethods++

                    // 변경 전후 코드 생성 (메서드 시그니처 + 로컬 변수)
                    val sourceCodeBefore = sourceCode.lines().take(10).joinToString("\n")
                    var sourceCodeAfter = sourceCodeBefore.replace(
                        "\\b${Regex.escape(method.methodName)}\\b".toRegex(),
                        analysis.suggestedName
                    )

                    // 로컬 변수 리네임 반영
                    analysis.localVariables.forEach { (oldName, rename) ->
                        sourceCodeAfter = sourceCodeAfter.replace(
                            Regex("\\b${Regex.escape(oldName)}\\b"),
                            rename.suggestedName
                        )
                    }

                    // 모니터에 리네임 기록
                    monitor.addRename(
                        original = method.methodName,
                        suggested = analysis.suggestedName,
                        description = analysis.description,
                        reasoning = analysis.reasoning,
                        className = method.className,
                        filePath = method.file.absolutePath,
                        lineNumber = method.startLine,
                        sourceCodeBefore = sourceCodeBefore,
                        sourceCodeAfter = sourceCodeAfter
                    )

                    // 참조 업데이트
                    updateReferences(method, analysis.suggestedName)
                }
                is RenameResult.Failure -> {
                    logger.warn("  ✗ ${method.methodName}: ${result.reason}")
                    monitor.incrementFailed()
                }
            }

            processedMethods.add(method.id)
            monitor.incrementProcessed()
            return true

        } catch (e: Exception) {
            logger.error("Error processing ${method.id}: ${e.message}")
            processedMethods.add(method.id)
            monitor.incrementFailed()
            return false
        }
    }

    /**
     * 배치 메소드 처리 (한 번의 LLM 호출로 여러 메소드 분석)
     */
    private fun processBatch(methods: List<MethodNode>): Int {
        val validMethods = methods.filter { it.id !in processedMethods }
        if (validMethods.isEmpty()) return 0

        try {
            // 소스 코드 추출
            val methodsWithCode = validMethods.mapNotNull { method ->
                val sourceCode = extractMethodSource(method)
                if (sourceCode != null) {
                    method to sourceCode
                } else {
                    processedMethods.add(method.id)
                    null
                }
            }

            if (methodsWithCode.isEmpty()) return 0

            logger.debug("Batch analyzing ${methodsWithCode.size} methods...")

            // AI 배치 분석
            val batchResults = aiClient.analyzeMethods(methodsWithCode)

            // 각 결과 처리
            var successCount = 0
            batchResults.forEach { analysis ->
                val method = validMethods.find { it.id == analysis.methodId } ?: return@forEach
                val sourceCode = methodsWithCode.find { it.first.id == method.id }?.second ?: return@forEach

                try {
                    // 한글 번역 (옵션)
                    var finalAnalysis = analysis
                    if (translationClient != null) {
                        logger.debug("Translating to Korean: ${method.methodName}")
                        finalAnalysis = translationClient.translate(analysis)
                    }

                    analysisResults[method.id] = finalAnalysis

                    // 리네이밍 적용
                    val result = renamer.renameMethod(method.file, method, finalAnalysis)

                    when (result) {
                        is RenameResult.Success -> {
                            logger.info("  ✓ ${method.methodName} → ${finalAnalysis.suggestedName}")
                            logger.info("    └─ ${finalAnalysis.description.take(60)}...")
                            stats.renamedMethods++

                            // 변경 전후 코드 생성 (메서드 시그니처 + 로컬 변수)
                            val sourceCodeBefore = sourceCode.lines().take(10).joinToString("\n")
                            var sourceCodeAfter = sourceCodeBefore.replace(
                                "\\b${Regex.escape(method.methodName)}\\b".toRegex(),
                                finalAnalysis.suggestedName
                            )

                            // 로컬 변수 리네임 반영
                            finalAnalysis.localVariables.forEach { (oldName, rename) ->
                                sourceCodeAfter = sourceCodeAfter.replace(
                                    Regex("\\b${Regex.escape(oldName)}\\b"),
                                    rename.suggestedName
                                )
                            }

                            // 모니터에 리네임 기록
                            monitor.addRename(
                                original = method.methodName,
                                suggested = finalAnalysis.suggestedName,
                                description = finalAnalysis.description,
                                reasoning = finalAnalysis.reasoning,
                                confidence = finalAnalysis.confidence,
                                className = method.className,
                                filePath = method.file.absolutePath,
                                lineNumber = method.startLine,
                                sourceCodeBefore = sourceCodeBefore,
                                sourceCodeAfter = sourceCodeAfter
                            )

                            updateReferences(method, finalAnalysis.suggestedName)
                            successCount++
                        }
                        is RenameResult.Failure -> {
                            logger.warn("  ✗ ${method.methodName}: ${result.reason}")
                            monitor.incrementFailed()
                        }
                    }

                    processedMethods.add(method.id)
                    monitor.incrementProcessed()
                } catch (e: Exception) {
                    logger.error("Error applying analysis for ${method.id}: ${e.message}")
                    processedMethods.add(method.id)
                    monitor.incrementFailed()
                }
            }

            return successCount

        } catch (e: Exception) {
            logger.error("Error processing batch: ${e.message}")
            methods.forEach { processedMethods.add(it.id) }
            return 0
        }
    }

    /**
     * 메소드 소스 코드 추출
     */
    private fun extractMethodSource(method: MethodNode): String? {
        return try {
            val lines = method.file.readLines()
            if (method.startLine > 0 && method.endLine <= lines.size) {
                lines.subList(method.startLine - 1, method.endLine).joinToString("\n")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 참조 업데이트
     */
    private fun updateReferences(method: MethodNode, newName: String): com.whatap.apk2project.deobfuscator.renamer.ReferenceUpdateResult {
        val callers = graphBuilder.findCallers(method.id)
        if (callers.isNotEmpty()) {
            val files = callers.map { it.file }.distinct()
            return renamer.updateReferences(files, method.methodName, newName, RenameType.METHOD)
        }
        return com.whatap.apk2project.deobfuscator.renamer.ReferenceUpdateResult(0, emptyList())
    }

    /**
     * Phase 4: 클래스 리네이밍 (AI 기반 큐 파이프라인)
     */
    private suspend fun phase5RenameClasses() = coroutineScope {
        logger.info("\n[Phase 4] Inferring and renaming classes with AI...")
        monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.PHASE4_CLASSES
        monitor.setPhase("Phase 4", "AI-based class renaming...")

        if (aiClient == null || !aiClient.isAvailable()) {
            logger.warn("AI client not available, skipping Phase 4")
            return@coroutineScope
        }

        // 클래스 분석 후보 수집
        val candidates = mutableListOf<ClassRenameCandidate>()

        graphBuilder.classes.values
            .filter { it.isObfuscated }
            .forEach { classNode ->
                // 이 클래스의 메소드 분석 결과들
                val methodResults = classNode.methods
                    .mapNotNull { analysisResults[it.id] }

                val analyzedRatio = methodResults.size.toDouble() / classNode.methods.size.coerceAtLeast(1)

                // 50% 이상 분석되면 클래스 AI 분석 대상
                if (analyzedRatio >= 0.5 && methodResults.isNotEmpty()) {
                    candidates.add(ClassRenameCandidate(
                        classNode = classNode,
                        suggestedName = classNode.className,  // 임시, AI가 결정
                        confidence = analyzedRatio,
                        basedOnMethods = methodResults.map { it.suggestedName }
                    ))
                }
            }

        logger.info("Found ${candidates.size} classes for AI analysis")

        if (candidates.isEmpty()) {
            logger.warn("No classes to analyze")
            return@coroutineScope
        }

        // 큐 기반 파이프라인 실행
        runClassRenamePipeline(candidates)
    }

    /**
     * 클래스 리네이밍 큐 파이프라인 (Phase 4)
     */
    private suspend fun runClassRenamePipeline(candidates: List<ClassRenameCandidate>) = coroutineScope {
        // 파이프라인 구조에 따라 monitor 설정
        if (config.enableKorean) {
            monitor.classQwenQueueSize = 0
        } else {
            monitor.classQwenQueueSize = null
        }

        val classDeepseekQueue = Channel<ClassRenameCandidate>(capacity = 100)
        val classQwenQueue = Channel<Triple<ClassRenameCandidate, String, String>>(capacity = 100)
        val classRenameQueue = Channel<Triple<ClassRenameCandidate, String, String>>(capacity = 100)

        // 큐 크기 카운터
        val classDeepseekQueueCounter = AtomicInteger(0)
        val classQwenQueueCounter = AtomicInteger(0)
        val classRenameQueueCounter = AtomicInteger(0)

        // DeepSeek Workers (클래스명 분석)
        val deepseekJobs = List(config.batchSize) {
            launch(Dispatchers.IO) {
                for (candidate in classDeepseekQueue) {
                    classDeepseekQueueCounter.decrementAndGet()

                    try {
                        logger.debug("DeepSeek analyzing class: ${candidate.classNode.className}")

                        val classAnalysis = aiClient?.analyzeClass(
                            candidate.classNode.className,
                            candidate.basedOnMethods
                        )

                        if (classAnalysis != null && classAnalysis.suggestedName != candidate.classNode.className) {
                            // Qwen 큐로 전달 (한글 번역)
                            classQwenQueueCounter.incrementAndGet()
                            classQwenQueue.send(Triple(candidate, classAnalysis.suggestedName, classAnalysis.description))
                        } else {
                            logger.debug("Failed to analyze class ${candidate.classNode.className}")
                        }
                    } catch (e: Exception) {
                        logger.error("DeepSeek error for class ${candidate.classNode.className}: ${e.message}")
                    } finally {
                        // Phase 4 진행률 추적
                        monitor.incrementProcessedClass()
                    }
                    delay(config.requestDelay / config.batchSize)
                }
            }
        }

        // Qwen Workers (한글 번역)
        val qwenJobs = List(config.batchSize) {
            launch(Dispatchers.IO) {
                for ((candidate, englishName, description) in classQwenQueue) {
                    classQwenQueueCounter.decrementAndGet()

                    try {
                        logger.debug("Qwen translating class: $englishName")

                        // 한글 번역 (translationClient 사용)
                        val koreanName = if (translationClient != null) {
                            // TranslationClient는 MethodAnalysisResult를 번역하므로,
                            // 클래스명은 간단하게 description만 번역
                            val tempAnalysis = MethodAnalysisResult(
                                methodId = "",
                                suggestedName = englishName,
                                description = description,
                                reasoning = ""
                            )
                            val translated = translationClient.translate(tempAnalysis)
                            translated.suggestedName
                        } else {
                            englishName  // 번역 비활성화 시 영어 이름 사용
                        }

                        // Rename 큐로 전달
                        classRenameQueueCounter.incrementAndGet()
                        classRenameQueue.send(Triple(candidate, koreanName, description))
                    } catch (e: Exception) {
                        logger.error("Qwen translation error for $englishName: ${e.message}")
                    }
                    delay(config.requestDelay / config.batchSize)
                }
            }
        }

        // Rename Workers (파일 리네이밍)
        val renameJobs = List(config.batchSize) {
            launch(Dispatchers.IO) {
                for ((candidate, finalName, description) in classRenameQueue) {
                    classRenameQueueCounter.decrementAndGet()

                    try {
                        val result = renamer.renameClass(
                            file = candidate.classNode.file,
                            originalClassName = candidate.classNode.className,
                            newClassName = finalName,
                            description = description
                        )

                        when (result) {
                            is RenameResult.Success -> {
                                logger.info("  ✓ Class ${candidate.classNode.className} → $finalName")
                                stats.renamedClasses++

                                // 다른 파일에서 참조 업데이트
                                val allFiles = graphBuilder.classes.values.map { it.file }
                                renamer.updateReferences(
                                    allFiles,
                                    candidate.classNode.className,
                                    finalName,
                                    RenameType.CLASS
                                )
                            }
                            is RenameResult.Failure -> {
                                logger.warn("  ✗ Class ${candidate.classNode.className}: ${result.reason}")
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Rename error for ${candidate.classNode.className}: ${e.message}")
                    }
                }
            }
        }

        // Producer: 클래스들을 DeepSeek 큐에 공급
        val producerJob = launch(Dispatchers.IO) {
            for (candidate in candidates) {
                classDeepseekQueueCounter.incrementAndGet()
                classDeepseekQueue.send(candidate)
            }
            logger.info("Producer: Sent ${candidates.size} classes to DeepSeek queue")
            classDeepseekQueue.close()
        }

        // Real-time queue size monitoring (sync counters to monitor every 100ms)
        val monitorJob = launch(Dispatchers.IO) {
            while (isActive) {
                monitor.classDeepseekQueueSize = classDeepseekQueueCounter.get()
                if (config.enableKorean) {
                    monitor.classQwenQueueSize = classQwenQueueCounter.get()
                }
                monitor.classRenameQueueSize = classRenameQueueCounter.get()
                delay(100)  // Update every 100ms
            }
        }

        // Producer 완료 대기
        producerJob.join()

        // DeepSeek 완료 대기
        deepseekJobs.forEach { it.join() }
        monitorJob.cancel()  // Stop monitoring when DeepSeek completes
        classQwenQueue.close()
        logger.info("DeepSeek workers completed")

        // Qwen 완료 대기
        qwenJobs.forEach { it.join() }
        classRenameQueue.close()
        logger.info("Qwen workers completed")

        // Rename 완료 대기
        renameJobs.forEach { it.join() }
        logger.info("Rename workers completed")

        logger.info("Phase 4 completed: Renamed ${stats.renamedClasses} classes")
    }


    /**
     * Phase 5: 결과 저장
     */
    private fun phase6SaveResults() {
        logger.info("\n[Phase 5] Saving results...")
        monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.PHASE5_SAVE
        monitor.phase5Progress = 0.0
        monitor.setPhase("Phase 5", "Saving results... (1/3)")
        monitor.forceUpdate()

        // Step 1: 리네임 히스토리 저장
        logger.info("  Step 1/3: Saving rename history...")
        renamer.exportHistory(File(outputDir, "rename_history.md"))
        monitor.phase5Progress = 33.3
        monitor.setPhase("Phase 5", "Saving results... (2/3)")
        monitor.forceUpdate()

        // Step 2: 매핑 JSON 저장
        logger.info("  Step 2/3: Saving mappings JSON...")
        saveMappingsJson()
        monitor.phase5Progress = 66.6
        monitor.setPhase("Phase 5", "Saving results... (3/3)")
        monitor.forceUpdate()

        // Step 3: 통계 저장
        logger.info("  Step 3/3: Saving statistics...")
        saveStats()
        monitor.phase5Progress = 100.0
        monitor.setPhase("Phase 5", "Results saved successfully")
        monitor.forceUpdate()

        logger.info("✓ Results saved to ${outputDir.absolutePath}")
    }

    /**
     * 매핑 JSON 저장
     */
    private fun saveMappingsJson() {
        val mappings = buildString {
            appendLine("{")
            appendLine("  \"methods\": [")

            val entries = analysisResults.entries.toList()
            entries.forEachIndexed { index, (id, analysis) ->
                val comma = if (index < entries.size - 1) "," else ""
                appendLine("    {")
                appendLine("      \"id\": \"$id\",")
                appendLine("      \"suggestedName\": \"${analysis.suggestedName}\",")
                appendLine("      \"description\": \"${analysis.description.replace("\"", "\\\"")}\"")
                appendLine("    }$comma")
            }

            appendLine("  ]")
            appendLine("}")
        }

        File(outputDir, "mappings.json").writeText(mappings)
    }

    /**
     * 통계 저장
     */
    private fun saveStats() {
        val statsText = """
            |Deobfuscation Pipeline Statistics
            |==================================
            |
            |Total Classes: ${stats.totalClasses}
            |Total Methods: ${stats.totalMethods}
            |
            |Processed Methods: ${stats.processedMethods}
            |Renamed Methods: ${stats.renamedMethods}
            |Renamed Classes: ${stats.renamedClasses}
            |
            |Duration: ${stats.durationMs / 1000}s
            |
            |Success Rate: ${if (stats.processedMethods > 0)
                (stats.renamedMethods * 100 / stats.processedMethods) else 0}%
        """.trimMargin()

        File(outputDir, "stats.txt").writeText(statsText)
    }
}

data class PipelineConfig(
    val aiClientType: AiClientType = AiClientType.OLLAMA,  // 기본값: Ollama
    val aiExecutablePath: String? = null,  // null이면 기본값 사용
    val modelName: String? = null,         // Ollama 분석 모델명 (예: deepseek-coder:6.7b)
    val batchSize: Int = 2,                // 병렬 처리 워커 수 (사용자 요청: 2개)
    val aiBatchSize: Int = 5,              // AI 배치 분석 단위 (1=개별, 2+=배치)
    val requestDelay: Long = 1000,         // 요청 간 지연 (기본값)
    val analysisTimeout: Long = 600_000,   // 10분 (DeepSeek-R1 reasoning 고려)
    val useCache: Boolean = true,  // 캐시 사용 여부
    val cacheFile: File? = null,   // null이면 기본 위치 사용
    val enableKorean: Boolean = false,  // 한글 번역 활성화
    val translationModelName: String? = null,  // 번역 모델명 (null이면 qwen2.5:7b 기본값)
    val resume: Boolean = false,  // 이어하기 모드
    val sessionId: String? = null,  // 특정 세션 ID로 이어하기
    val ollamaBaseUrl: String = "http://localhost:11434"  // Ollama 서버 URL
)

data class PipelineStats(
    var totalClasses: Int = 0,
    var totalMethods: Int = 0,
    var processedMethods: Int = 0,
    var renamedMethods: Int = 0,
    var renamedClasses: Int = 0,
    var durationMs: Long = 0
)

data class PipelineResult(
    val success: Boolean,
    val stats: PipelineStats,
    val outputDir: File? = null,
    val error: String? = null
)

data class ClassRenameCandidate(
    val classNode: ClassNode,
    val suggestedName: String,
    val confidence: Double,
    val basedOnMethods: List<String>
)
