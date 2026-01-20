package com.whatap.apk2project.utils

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 성능 메트릭 로거
 *
 * 파이프라인 처리 성능을 추적하고 로그에 기록
 */
object MetricsLogger {
    private val metricsLogger = LoggerFactory.getLogger("metrics")
    private val aiLogger = LoggerFactory.getLogger("ai.requests")
    private val logger = LoggerFactory.getLogger(javaClass)

    // 메트릭 데이터
    private val methodsProcessed = AtomicLong(0)
    private val methodsRenamed = AtomicLong(0)
    private val methodsFailed = AtomicLong(0)
    private val aiRequestCount = AtomicLong(0)
    private val aiTotalLatency = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)

    // Phase 별 타이밍
    private val phaseStartTimes = ConcurrentHashMap<String, Long>()
    private val phaseDurations = ConcurrentHashMap<String, Long>()

    /**
     * Phase 시작 기록
     */
    fun startPhase(phaseName: String) {
        phaseStartTimes[phaseName] = System.currentTimeMillis()
        metricsLogger.info("PHASE_START|$phaseName|${System.currentTimeMillis()}")
    }

    /**
     * Phase 종료 기록
     */
    fun endPhase(phaseName: String) {
        val startTime = phaseStartTimes[phaseName]
        if (startTime != null) {
            val duration = System.currentTimeMillis() - startTime
            phaseDurations[phaseName] = duration
            metricsLogger.info("PHASE_END|$phaseName|${System.currentTimeMillis()}|duration_ms=$duration")
        }
    }

    /**
     * 메소드 처리 기록
     */
    fun recordMethodProcessed(success: Boolean, renamed: Boolean) {
        methodsProcessed.incrementAndGet()
        if (renamed) methodsRenamed.incrementAndGet()
        if (!success) methodsFailed.incrementAndGet()
    }

    /**
     * AI 요청 기록
     */
    fun recordAiRequest(
        methodName: String,
        model: String,
        latencyMs: Long,
        success: Boolean,
        confidence: Float? = null
    ) {
        aiRequestCount.incrementAndGet()
        aiTotalLatency.addAndGet(latencyMs)

        aiLogger.info(buildString {
            append("AI_REQUEST|")
            append("method=$methodName|")
            append("model=$model|")
            append("latency_ms=$latencyMs|")
            append("success=$success")
            if (confidence != null) {
                append("|confidence=$confidence")
            }
        })
    }

    /**
     * 캐시 히트/미스 기록
     */
    fun recordCacheAccess(hit: Boolean) {
        if (hit) cacheHits.incrementAndGet() else cacheMisses.incrementAndGet()
    }

    /**
     * 리소스 사용량 기록
     */
    fun recordResourceUsage(
        cpuPercent: Double,
        memoryMb: Long,
        gpuPercent: Double? = null,
        gpuMemoryMb: Long? = null
    ) {
        metricsLogger.info(buildString {
            append("RESOURCE|")
            append("cpu_percent=${"%.1f".format(cpuPercent)}|")
            append("memory_mb=$memoryMb")
            if (gpuPercent != null) {
                append("|gpu_percent=${"%.1f".format(gpuPercent)}")
            }
            if (gpuMemoryMb != null) {
                append("|gpu_memory_mb=$gpuMemoryMb")
            }
        })
    }

    /**
     * 큐 상태 기록
     */
    fun recordQueueStatus(
        deepseekQueueSize: Int,
        translationQueueSize: Int?,
        renameQueueSize: Int
    ) {
        metricsLogger.info(buildString {
            append("QUEUE|")
            append("deepseek=$deepseekQueueSize|")
            if (translationQueueSize != null) {
                append("translation=$translationQueueSize|")
            }
            append("rename=$renameQueueSize")
        })
    }

    /**
     * 주기적 통계 출력 (5초마다 호출 권장)
     */
    fun logPeriodicStats() {
        val avgAiLatency = if (aiRequestCount.get() > 0) {
            aiTotalLatency.get() / aiRequestCount.get()
        } else 0

        val cacheHitRate = if (cacheHits.get() + cacheMisses.get() > 0) {
            cacheHits.get().toDouble() / (cacheHits.get() + cacheMisses.get()) * 100
        } else 0.0

        metricsLogger.info(buildString {
            append("STATS|")
            append("methods_processed=${methodsProcessed.get()}|")
            append("methods_renamed=${methodsRenamed.get()}|")
            append("methods_failed=${methodsFailed.get()}|")
            append("ai_requests=${aiRequestCount.get()}|")
            append("avg_ai_latency_ms=$avgAiLatency|")
            append("cache_hit_rate=${"%.1f".format(cacheHitRate)}%")
        })
    }

    /**
     * 최종 요약 출력
     */
    fun logFinalSummary() {
        logger.info("=" .repeat(60))
        logger.info("PIPELINE METRICS SUMMARY")
        logger.info("=" .repeat(60))

        // Phase 별 소요 시간
        logger.info("Phase Durations:")
        phaseDurations.forEach { (phase, duration) ->
            logger.info("  $phase: ${formatDuration(duration)}")
        }

        // 처리 통계
        logger.info("Processing Statistics:")
        logger.info("  Methods Processed: ${methodsProcessed.get()}")
        logger.info("  Methods Renamed: ${methodsRenamed.get()}")
        logger.info("  Methods Failed: ${methodsFailed.get()}")

        // AI 통계
        val avgLatency = if (aiRequestCount.get() > 0) {
            aiTotalLatency.get() / aiRequestCount.get()
        } else 0
        logger.info("AI Statistics:")
        logger.info("  Total Requests: ${aiRequestCount.get()}")
        logger.info("  Average Latency: ${avgLatency}ms")

        // 캐시 통계
        val totalCacheAccess = cacheHits.get() + cacheMisses.get()
        val hitRate = if (totalCacheAccess > 0) {
            cacheHits.get().toDouble() / totalCacheAccess * 100
        } else 0.0
        logger.info("Cache Statistics:")
        logger.info("  Hits: ${cacheHits.get()}")
        logger.info("  Misses: ${cacheMisses.get()}")
        logger.info("  Hit Rate: ${"%.1f".format(hitRate)}%")

        logger.info("=" .repeat(60))

        // 메트릭 로그에도 기록
        metricsLogger.info("SUMMARY|" +
            "methods_processed=${methodsProcessed.get()}|" +
            "methods_renamed=${methodsRenamed.get()}|" +
            "methods_failed=${methodsFailed.get()}|" +
            "ai_requests=${aiRequestCount.get()}|" +
            "avg_ai_latency_ms=$avgLatency|" +
            "cache_hit_rate=${"%.1f".format(hitRate)}%"
        )
    }

    /**
     * 메트릭 초기화
     */
    fun reset() {
        methodsProcessed.set(0)
        methodsRenamed.set(0)
        methodsFailed.set(0)
        aiRequestCount.set(0)
        aiTotalLatency.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
        phaseStartTimes.clear()
        phaseDurations.clear()
    }

    /**
     * 현재 통계 가져오기
     */
    fun getCurrentStats(): MetricsSnapshot {
        return MetricsSnapshot(
            methodsProcessed = methodsProcessed.get(),
            methodsRenamed = methodsRenamed.get(),
            methodsFailed = methodsFailed.get(),
            aiRequestCount = aiRequestCount.get(),
            avgAiLatencyMs = if (aiRequestCount.get() > 0) {
                aiTotalLatency.get() / aiRequestCount.get()
            } else 0,
            cacheHits = cacheHits.get(),
            cacheMisses = cacheMisses.get(),
            phaseDurations = phaseDurations.toMap()
        )
    }

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60000 -> "${"%.1f".format(ms / 1000.0)}s"
            else -> "${"%.1f".format(ms / 60000.0)}m"
        }
    }
}

/**
 * 메트릭 스냅샷
 */
data class MetricsSnapshot(
    val methodsProcessed: Long,
    val methodsRenamed: Long,
    val methodsFailed: Long,
    val aiRequestCount: Long,
    val avgAiLatencyMs: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val phaseDurations: Map<String, Long>
) {
    val cacheHitRate: Double
        get() = if (cacheHits + cacheMisses > 0) {
            cacheHits.toDouble() / (cacheHits + cacheMisses) * 100
        } else 0.0

    val successRate: Double
        get() = if (methodsProcessed > 0) {
            methodsRenamed.toDouble() / methodsProcessed * 100
        } else 0.0
}
