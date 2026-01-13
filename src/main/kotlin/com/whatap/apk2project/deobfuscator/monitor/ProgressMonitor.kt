package com.whatap.apk2project.deobfuscator.monitor

import com.google.gson.GsonBuilder
import com.whatap.apk2project.deobfuscator.monitor.gpu.GpuSampler
import com.whatap.apk2project.deobfuscator.monitor.gpu.GpuSamplerFactory
import com.whatap.apk2project.deobfuscator.monitor.gpu.GpuInfo
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.sun.management.OperatingSystemMXBean
import kotlin.concurrent.fixedRateTimer

/**
 * 진행 상황 모니터링 및 웹 대시보드 지원
 */
class ProgressMonitor(
    private val outputDir: File,
    private val gpuSampler: GpuSampler = GpuSamplerFactory.create()
) {
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .serializeSpecialFloatingPointValues()  // NaN, Infinity 허용
        .create()
    private val statusFile = File(outputDir, "status.json")
    private val dashboardFile = File(outputDir, "dashboard.html")

    // 통계
    val totalClasses = AtomicInteger(0)
    val totalMethods = AtomicInteger(0)
    val leafMethods = AtomicInteger(0)
    val processedMethods = AtomicInteger(0)  // Phase 3: 메서드 처리
    val processedClasses = AtomicInteger(0)  // Phase 4: 클래스 처리
    val renamedMethods = AtomicInteger(0)
    val failedMethods = AtomicInteger(0)
    val currentIteration = AtomicInteger(0)
    val startTime = AtomicLong(0)

    // 최근 리네임 히스토리 (최대 50개)
    private val recentRenames = ConcurrentLinkedQueue<RenameEntry>()
    private val maxRecentRenames = 50

    // 최근 LLM 요청 히스토리 (최대 30개)
    private val recentLlmRequests = ConcurrentLinkedQueue<LlmRequestEntry>()
    private val maxRecentLlmRequests = 30

    // 시스템 리소스 히스토리 (최대 100개, 약 5분간 데이터)
    private val resourceHistory = ConcurrentLinkedQueue<ResourceSnapshot>()
    private val maxResourceHistory = 100

    // CPU 샘플링 (주기적으로 측정해서 최신 값 사용)
    @Volatile private var latestCpuUsage: Double = 0.0
    private val osBean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
    private var cpuSamplingTimer: java.util.Timer? = null

    // GPU 샘플링 (GpuSampler 사용)
    @Volatile private var latestGpuInfo: GpuInfo = GpuInfo(0.0, 0, 0)

    // 현재 상태
    @Volatile var currentPhase: PipelinePhase = PipelinePhase.INITIALIZING
    @Volatile var phase: String = "Initializing"  // 하위 호환성
    @Volatile var status: String = "Starting..."
    @Volatile var isRunning: Boolean = true

    // PARSE 0: APK → Gradle Project Generation
    @Volatile var parse0Step: Int = 0            // 현재 단계 (1-5)
    @Volatile var parse0Progress: Double = 0.0    // 전체 진행률 (0-100)
    @Volatile var apkFilePath: String = ""        // 원본 APK 경로
    @Volatile var outputProjectPath: String = ""  // 출력 프로젝트 경로
    @Volatile var decompileSuccessRate: Double = 0.0  // 디컴파일 성공률
    @Volatile var totalResourcesExtracted: Int = 0     // 추출된 리소스 수
    @Volatile var dependenciesDetected: Int = 0       // 감지된 의존성 수

    // Phase 1: 파일 파싱
    @Volatile var parsedFiles: Int = 0
    @Volatile var totalFilesToParse: Int = 0
    @Volatile var failedParseFiles: Int = 0
    @Volatile var phase1Progress: Double = 0.0  // 0-100

    // Phase 2: Call Graph 구축
    @Volatile var callGraphClasses: Int = 0
    @Volatile var totalCallGraphClasses: Int = 0
    @Volatile var callGraphEdges: Int = 0
    @Volatile var phase2Progress: Double = 0.0  // 0-100

    // Phase 3: AI 분석
    @Volatile var aiClientType: String = ""
    @Volatile var aiClientAvailable: Boolean = false
    @Volatile var phase3Progress: Double = 0.0  // 0-100

    // Phase 3 Pipeline queue sizes (메소드 리네이밍)
    @Volatile var deepseekQueueSize: Int = 0
    @Volatile var qwenQueueSize: Int? = null  // null이면 해당 스테이지 비활성
    @Volatile var renameQueueSize: Int = 0
    @Volatile var pipelineStages: List<String> = emptyList()  // 실제 파이프라인 스테이지

    // Phase 4: 클래스 리네이밍
    @Volatile var phase4Progress: Double = 0.0  // 0-100

    // Phase 4 Pipeline queue sizes (클래스 리네이밍)
    @Volatile var classDeepseekQueueSize: Int = 0
    @Volatile var classQwenQueueSize: Int? = null  // null이면 해당 스테이지 비활성
    @Volatile var classRenameQueueSize: Int = 0

    // Pipeline config
    @Volatile var batchSize: Int = 10
    @Volatile var koreanEnabled: Boolean = false

    init {
        outputDir.mkdirs()
        createDashboard()
    }

    /**
     * 즉시 status.json 파일 업데이트 (ProjectGenerator에서 PARSE 0 진행상황 반영용)
     */
    fun forceUpdate() {
        updateStatus()
    }

    fun start() {
        startTime.set(System.currentTimeMillis())
        recentRenames.clear()  // 이전 실행의 히스토리 제거

        // Note: DashboardServer is now started in FixCommand.run() before this method is called

        // CPU/GPU 샘플링 시작 (1초마다 OS 전체 CPU 및 GPU 측정)
        cpuSamplingTimer = fixedRateTimer("resource-sampler", daemon = true, initialDelay = 0, period = 1000) {
            try {
                // CPU 샘플링
                val cpu = (osBean.systemCpuLoad * 100).coerceIn(0.0, 100.0)
                if (!cpu.isNaN()) {
                    latestCpuUsage = cpu
                }

                // GPU 샘플링 (GpuSampler 사용 - 플랫폼 독립적)
                val gpuInfo = gpuSampler.getGpuInfo()
                if (gpuInfo != null) {
                    latestGpuInfo = gpuInfo
                }
            } catch (e: Exception) {
                // Ignore sampling errors
            }

            try {
                updateStatus()
            } catch (e: Exception) {
                // Ignore status update errors to prevent timer from crashing
                System.err.println("Warning: Failed to update status.json: ${e.message}")
            }
        }
    }

    fun setPhase(phase: String, status: String = "") {
        this.phase = phase
        this.status = status
        updateStatus()
    }

    fun addRename(
        type: String = "METHOD",  // "PACKAGE", "CLASS", "METHOD", "FIELD"
        original: String,
        suggested: String,
        description: String,
        reasoning: String = "",
        className: String,
        filePath: String = "",
        lineNumber: Int = 0,
        sourceCodeBefore: String = "",
        sourceCodeAfter: String = "",
        localVariableRenames: Map<String, String> = emptyMap(),
        referencesUpdated: Int = 0,
        updatedFiles: List<String> = emptyList()
    ) {
        recentRenames.add(RenameEntry(
            type = type,
            original = original,
            suggested = suggested,
            description = description.take(100),
            reasoning = reasoning.take(200),
            className = className.substringAfterLast("."),
            fullClassName = className,
            filePath = filePath.substringAfterLast("sources").removePrefix("\\").removePrefix("/"),
            lineNumber = lineNumber,
            sourceCodeBefore = sourceCodeBefore.lines().take(10).joinToString("\n"),
            sourceCodeAfter = sourceCodeAfter.lines().take(10).joinToString("\n"),
            localVariableRenames = localVariableRenames,
            referencesUpdated = referencesUpdated,
            updatedFiles = updatedFiles.take(10).map { it.substringAfterLast("sources").removePrefix("\\").removePrefix("/") },
            timestamp = System.currentTimeMillis()
        ))

        // 최대 개수 유지
        while (recentRenames.size > maxRecentRenames) {
            recentRenames.poll()
        }

        renamedMethods.incrementAndGet()
        updateStatus()
    }

    fun addLlmRequest(
        methodName: String,
        requestType: String,
        model: String,
        promptPreview: String,
        response: String,
        durationMs: Long,
        success: Boolean
    ) {
        recentLlmRequests.add(LlmRequestEntry(
            methodName = methodName,
            requestType = requestType,
            model = model,
            promptPreview = promptPreview.take(200),
            response = response.take(500),
            durationMs = durationMs,
            success = success,
            timestamp = System.currentTimeMillis()
        ))

        // 최대 개수 유지
        while (recentLlmRequests.size > maxRecentLlmRequests) {
            recentLlmRequests.poll()
        }

        updateStatus()
    }

    fun incrementProcessed() {
        processedMethods.incrementAndGet()
        updateStatus()
    }

    fun incrementProcessedClass() {
        processedClasses.incrementAndGet()
        updateStatus()
    }

    fun incrementFailed() {
        failedMethods.incrementAndGet()
        updateStatus()
    }

    fun nextIteration() {
        currentIteration.incrementAndGet()
        updateStatus()
    }

    fun complete() {
        isRunning = false
        currentPhase = PipelinePhase.COMPLETE
        phase = "Complete"
        status = "Pipeline finished successfully"

        // 서버는 계속 실행 (사용자가 대시보드를 볼 수 있도록)
        // dashboardServer?.stop()  // 주석 처리 - 수동으로 종료
        cpuSamplingTimer?.cancel()
        updateStatus()
    }

    fun fail(error: String) {
        isRunning = false
        currentPhase = PipelinePhase.FAILED
        phase = "Failed"
        status = error
        cpuSamplingTimer?.cancel()
        updateStatus()
    }

    private fun updateStatus() {
        val elapsed = if (startTime.get() > 0) {
            System.currentTimeMillis() - startTime.get()
        } else 0

        val processed = processedMethods.get()
        val total = leafMethods.get()
        val remaining = (total - processed).coerceAtLeast(0)

        val progress = if (total > 0) {
            (processed.toDouble() / total * 100).coerceAtMost(100.0)
        } else 0.0

        // 처리 속도 계산 (분당 메소드 수)
        val methodsPerMinute = if (elapsed > 60000 && processed > 0) {
            processed.toDouble() / (elapsed / 60000.0)
        } else if (elapsed > 10000 && processed > 0) {
            processed.toDouble() / (elapsed / 1000.0) * 60
        } else 0.0

        // 예상 남은 시간 계산
        val estimatedRemainingMs = if (methodsPerMinute > 0 && remaining > 0) {
            ((remaining / methodsPerMinute) * 60000).toLong()
        } else 0L

        // 예상 완료 시간
        val estimatedCompletionTime = if (estimatedRemainingMs > 0) {
            val completionTime = System.currentTimeMillis() + estimatedRemainingMs
            java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(completionTime))
        } else "--:--"

        // 성공률
        val successRate = if (processed > 0) {
            (renamedMethods.get().toDouble() / processed * 100)
        } else 0.0

        // 시스템 리소스 정보
        val runtime = Runtime.getRuntime()

        // 전체 시스템 CPU 사용률 - 최신 샘플 값 (Ollama 프로세스 포함)
        val cpuUsage = if (latestCpuUsage > 0.0 && !latestCpuUsage.isNaN()) {
            latestCpuUsage
        } else {
            val systemLoad = osBean.systemCpuLoad
            if (systemLoad >= 0 && !systemLoad.isNaN()) {
                (systemLoad * 100).coerceIn(0.0, 100.0)
            } else {
                0.0  // NaN 또는 유효하지 않은 값인 경우 0으로 대체
            }
        }
        val memoryUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val memoryTotal = runtime.maxMemory() / (1024 * 1024)
        val memoryUsagePercent = if (memoryTotal > 0) {
            (memoryUsed.toDouble() / memoryTotal * 100).coerceIn(0.0, 100.0)
        } else 0.0

        // 리소스 스냅샷 추가 (CPU + GPU)
        resourceHistory.add(ResourceSnapshot(
            timestamp = System.currentTimeMillis(),
            cpuUsagePercent = cpuUsage,
            memoryUsedMb = memoryUsed,
            memoryUsagePercent = memoryUsagePercent,
            gpuUsagePercent = latestGpuInfo.usagePercent,
            gpuMemoryUsedMb = latestGpuInfo.memoryUsedMb,
            gpuMemoryTotalMb = latestGpuInfo.memoryTotalMb
        ))

        // 최대 개수 유지
        while (resourceHistory.size > maxResourceHistory) {
            resourceHistory.poll()
        }

        // Phase 1 진행률 계산 (파일 파싱)
        val phase1Prog = if (totalFilesToParse > 0) {
            (parsedFiles.toDouble() / totalFilesToParse * 100).coerceAtMost(100.0)
        } else 0.0

        // Phase 2 진행률 계산 (Call Graph 구축)
        val phase2Prog = if (totalCallGraphClasses > 0) {
            (callGraphClasses.toDouble() / totalCallGraphClasses * 100).coerceAtMost(100.0)
        } else 0.0

        // Phase 3 진행률 계산 (AI 분석 - 메서드)
        val phase3Prog = if (leafMethods.get() > 0) {
            (processed.toDouble() / leafMethods.get() * 100).coerceAtMost(100.0)
        } else 0.0

        // Phase 4 진행률 계산 (클래스 리네이밍)
        val processedClassesCount = processedClasses.get()
        val totalClassesCount = totalClasses.get()
        val phase4Prog = if (totalClassesCount > 0) {
            (processedClassesCount.toDouble() / totalClassesCount * 100).coerceAtMost(100.0)
        } else 0.0

        val statusData = ProgressStatus(
            phase = phase,
            currentPhase = currentPhase.name,
            status = status,
            isRunning = isRunning,
            totalClasses = totalClasses.get(),
            totalMethods = totalMethods.get(),
            leafMethods = leafMethods.get(),
            processedMethods = processed,
            renamedMethods = renamedMethods.get(),
            failedMethods = failedMethods.get(),
            currentIteration = currentIteration.get(),
            progress = progress,
            elapsedMs = elapsed,
            elapsedFormatted = formatDuration(elapsed),
            methodsPerMinute = methodsPerMinute,
            estimatedRemainingMs = estimatedRemainingMs,
            estimatedRemainingFormatted = formatDuration(estimatedRemainingMs),
            estimatedCompletionTime = estimatedCompletionTime,
            successRate = successRate,
            // PARSE 0
            parse0Step = parse0Step,
            parse0Progress = parse0Progress,
            apkFilePath = apkFilePath,
            outputProjectPath = outputProjectPath,
            decompileSuccessRate = decompileSuccessRate,
            totalResourcesExtracted = totalResourcesExtracted,
            dependenciesDetected = dependenciesDetected,
            // Phase 1
            parsedFiles = parsedFiles,
            totalFilesToParse = totalFilesToParse,
            failedParseFiles = failedParseFiles,
            phase1Progress = phase1Prog,
            callGraphClasses = callGraphClasses,
            totalCallGraphClasses = totalCallGraphClasses,
            callGraphEdges = callGraphEdges,
            phase2Progress = phase2Prog,
            aiClientType = aiClientType,
            aiClientAvailable = aiClientAvailable,
            phase3Progress = phase3Prog,
            deepseekQueueSize = deepseekQueueSize,
            qwenQueueSize = qwenQueueSize,
            renameQueueSize = renameQueueSize,
            pipelineStages = pipelineStages,
            phase4Progress = phase4Prog,
            processedClasses = processedClassesCount,
            classDeepseekQueueSize = classDeepseekQueueSize,
            classQwenQueueSize = classQwenQueueSize,
            classRenameQueueSize = classRenameQueueSize,
            batchSize = batchSize,
            recentRenames = recentRenames.toList().reversed(),
            recentLlmRequests = recentLlmRequests.toList().reversed(),
            resourceHistory = resourceHistory.toList(),
            cpuUsagePercent = cpuUsage,
            memoryUsedMb = memoryUsed,
            memoryTotalMb = memoryTotal,
            memoryUsagePercent = memoryUsagePercent,
            gpuUsagePercent = latestGpuInfo.usagePercent,
            gpuMemoryUsedMb = latestGpuInfo.memoryUsedMb,
            gpuMemoryTotalMb = latestGpuInfo.memoryTotalMb,
            lastUpdated = System.currentTimeMillis()
        )

        try {
            val json = gson.toJson(statusData)
            statusFile.writeText(json)
        } catch (e: Exception) {
            System.err.println("Failed to write status.json: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = ms / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun createDashboard() {
        try {
            // 리소스에서 대시보드 HTML 로드
            val resourceStream = javaClass.classLoader.getResourceAsStream("dashboard.html")
            if (resourceStream != null) {
                dashboardFile.writeText(resourceStream.bufferedReader().readText())
            } else {
                // 리소스가 없으면 기본 HTML 생성
                dashboardFile.writeText(getDefaultDashboardHtml())
            }
        } catch (e: Exception) {
            // 실패하면 기본 HTML 사용
            dashboardFile.writeText(getDefaultDashboardHtml())
        }
    }

    private fun getDefaultDashboardHtml(): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Deobfuscation Monitor</title>
    <style>
        body { font-family: sans-serif; background: #1a1a2e; color: #eee; padding: 20px; }
        .stat { display: inline-block; margin: 10px; padding: 15px; background: #333; border-radius: 8px; }
        .value { font-size: 2em; color: #00d9ff; }
        pre { background: #222; padding: 10px; border-radius: 5px; overflow-x: auto; }
    </style>
</head>
<body>
    <h1>Deobfuscation Pipeline Monitor</h1>
    <div id="stats">Loading...</div>
    <h2>Recent Renames</h2>
    <pre id="renames">Waiting...</pre>
    <script>
        async function update() {
            try {
                const r = await fetch('status.json?' + Date.now());
                const d = await r.json();
                document.getElementById('stats').innerHTML =
                    '<div class="stat"><div class="value">' + d.renamedMethods + '</div>Renamed</div>' +
                    '<div class="stat"><div class="value">' + d.processedMethods + '/' + d.leafMethods + '</div>Processed</div>' +
                    '<div class="stat"><div class="value">' + d.currentIteration + '</div>Iteration</div>' +
                    '<div class="stat"><div class="value">' + d.elapsedFormatted + '</div>Elapsed</div>';
                if (d.recentRenames && d.recentRenames.length > 0) {
                    document.getElementById('renames').textContent = d.recentRenames.map(function(r) {
                        return r.original + ' -> ' + r.suggested + ' (' + r.className + ')';
                    }).join('\n');
                }
            } catch(e) {}
        }
        update();
        setInterval(update, 2000);
    </script>
</body>
</html>
        """.trimIndent()
    }
}

data class ProgressStatus(
    val phase: String,
    val currentPhase: String,  // PipelinePhase enum 값
    val status: String,
    val isRunning: Boolean,
    val totalClasses: Int,
    val totalMethods: Int,
    val leafMethods: Int,
    val processedMethods: Int,
    val processedClasses: Int = 0,  // Phase 4에서 처리된 클래스 수
    val renamedMethods: Int,
    val failedMethods: Int,
    val currentIteration: Int,
    val progress: Double,
    val elapsedMs: Long,
    val elapsedFormatted: String,
    val methodsPerMinute: Double,
    val estimatedRemainingMs: Long,
    val estimatedRemainingFormatted: String,
    val estimatedCompletionTime: String,
    val successRate: Double,
    // PARSE 0: APK → Gradle Project Generation
    val parse0Step: Int = 0,
    val parse0Progress: Double = 0.0,
    val apkFilePath: String = "",
    val outputProjectPath: String = "",
    val decompileSuccessRate: Double = 0.0,
    val totalResourcesExtracted: Int = 0,
    val dependenciesDetected: Int = 0,
    // Phase 1: 파일 파싱
    val parsedFiles: Int,
    val totalFilesToParse: Int,
    val failedParseFiles: Int,
    val phase1Progress: Double,  // 0-100
    // Phase 2: Call Graph 구축
    val callGraphClasses: Int,
    val totalCallGraphClasses: Int,
    val callGraphEdges: Int,
    val phase2Progress: Double,  // 0-100
    // Phase 3: AI 분석
    val aiClientType: String,
    val aiClientAvailable: Boolean,
    val phase3Progress: Double,  // 0-100
    // Phase 3 Pipeline queues (메소드 리네이밍)
    val deepseekQueueSize: Int,
    val qwenQueueSize: Int?,  // null이면 해당 스테이지 비활성
    val renameQueueSize: Int,
    val pipelineStages: List<String>,  // 실제 파이프라인 스테이지
    // Phase 4: 클래스 리네이밍
    val phase4Progress: Double,  // 0-100
    // Phase 4 Pipeline queues (클래스 리네이밍)
    val classDeepseekQueueSize: Int,
    val classQwenQueueSize: Int?,  // null이면 해당 스테이지 비활성
    val classRenameQueueSize: Int,
    // Pipeline config
    val batchSize: Int,
    val recentRenames: List<RenameEntry>,
    val recentLlmRequests: List<LlmRequestEntry>,
    val resourceHistory: List<ResourceSnapshot>,
    // System resources
    val cpuUsagePercent: Double,
    val memoryUsedMb: Long,
    val memoryTotalMb: Long,
    val memoryUsagePercent: Double,
    // GPU resources
    val gpuUsagePercent: Double = 0.0,
    val gpuMemoryUsedMb: Long = 0,
    val gpuMemoryTotalMb: Long = 0,
    val lastUpdated: Long
)

data class RenameEntry(
    val type: String,               // "PACKAGE", "CLASS", "METHOD", "FIELD", "LOCAL_VARIABLE"
    val original: String,
    val suggested: String,
    val description: String,        // 메소드가 하는 일
    val reasoning: String,          // 왜 이 이름으로 바꿨는지
    val className: String,          // 짧은 클래스명 (e.g., "a")
    val fullClassName: String,      // 패키지 포함 전체 경로 (e.g., "com.example.a")
    val filePath: String,           // 소스 파일 경로
    val lineNumber: Int,            // 메소드 시작 라인
    val sourceCodeBefore: String,   // 변경 전 코드 (최대 10줄)
    val sourceCodeAfter: String,    // 변경 후 코드 (최대 10줄)
    val localVariableRenames: Map<String, String> = emptyMap(),  // 로컬 변수 리네임 (원본명 → 새이름)
    val referencesUpdated: Int = 0,  // 참조 업데이트 개수
    val updatedFiles: List<String> = emptyList(),  // 참조 업데이트된 파일 경로 목록
    val timestamp: Long
)

data class LlmRequestEntry(
    val methodName: String,         // 분석 대상 메소드
    val requestType: String,        // "analysis" or "translation"
    val model: String,              // "deepseek-coder:6.7b" or "qwen2.5:7b"
    val promptPreview: String,      // 프롬프트 일부 (처음 200자)
    val response: String,           // 응답 (JSON)
    val durationMs: Long,           // 소요 시간
    val success: Boolean,           // 성공 여부
    val timestamp: Long
)

data class ResourceSnapshot(
    val timestamp: Long,            // 시간 (밀리초)
    val cpuUsagePercent: Double,    // CPU 사용률 (%)
    val memoryUsedMb: Long,         // 메모리 사용량 (MB)
    val memoryUsagePercent: Double, // 메모리 사용률 (%)
    val gpuUsagePercent: Double = 0.0,    // GPU 사용률 (%)
    val gpuMemoryUsedMb: Long = 0,        // GPU 메모리 사용량 (MB)
    val gpuMemoryTotalMb: Long = 0        // GPU 메모리 전체 (MB)
)

/**
 * 파이프라인 Phase
 */
enum class PipelinePhase {
    INITIALIZING,                   // 초기화

    // PARSE 0: APK → Gradle Project Generation
    PARSE0_PARSING_MANIFEST,        // Step 1: AndroidManifest 파싱
    PARSE0_DECOMPILING,             // Step 2: APK 디컴파일
    PARSE0_EXTRACTING_RESOURCES,    // Step 3: 리소스 추출
    PARSE0_ANALYZING_DEPENDENCIES,  // Step 4: 의존성 분석
    PARSE0_GENERATING_PROJECT,      // Step 5: Gradle 프로젝트 생성

    // PARSE 1-5: AI Deobfuscation Pipeline
    PHASE1_FILE_PARSING,            // Phase 1: Java 파일 파싱
    PHASE2_CALL_GRAPH,              // Phase 2: Call Graph 구축
    PHASE3_AI_ANALYSIS,             // Phase 3: AI 분석 (DeepSeek → Qwen → Rename)
    PHASE4_CLASSES,                 // Phase 4: 클래스 리네이밍
    PHASE5_SAVE,                    // Phase 5: 결과 저장

    COMPLETE,                       // 완료
    FAILED                          // 실패
}

