package com.whatap.apk2project.deobfuscator.pipeline

import com.whatap.apk2project.deobfuscator.cache.MethodSourceCache
import com.whatap.apk2project.deobfuscator.client.MethodAnalysisResult
import com.whatap.apk2project.deobfuscator.model.MethodNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 파이프라인 워커 추상화
 *
 * 목적:
 * - 큐 처리 워커 생성 로직 재사용
 * - 소스 코드 로드 로직 중복 제거
 * - 재시도 로직 표준화
 */
object PipelineWorkers {
    private val logger = LoggerFactory.getLogger(PipelineWorkers::class.java)

    /**
     * 워커 설정
     */
    data class WorkerConfig<T>(
        val workerCount: Int,
        val queue: Channel<T>,
        val queueCounter: AtomicInteger,
        val name: String,
        val delayMs: Long = 0
    )

    /**
     * 소스 코드 로더 (캐시 + 파일 추출)
     */
    fun loadSourceCode(
        method: MethodNode,
        sourceCache: MethodSourceCache,
        extractFunc: (MethodNode) -> String?
    ): String? {
        // 1. 캐시 확인
        val cached = sourceCache.getSource(method)
        if (cached != null) return cached

        // 2. 파일에서 추출
        val extracted = extractFunc(method)
        if (extracted != null) {
            sourceCache.putSource(method, extracted)
        }

        return extracted
    }

    /**
     * 재시도 로직
     *
     * @param item 재시도할 항목
     * @param retryCount 현재 재시도 횟수 맵
     * @param maxRetries 최대 재시도 횟수
     * @param retryQueue 재시도 큐
     * @param maxSize 최대 큐 크기
     * @param itemId 항목 ID (예: method.id)
     * @return 재시도 여부
     */
    fun <T> shouldRetry(
        item: T,
        retryCount: ConcurrentHashMap<String, Int>,
        maxRetries: Int,
        retryQueue: java.util.Queue<T>,
        maxSize: Int,
        itemId: String
    ): Boolean {
        val currentRetry = retryCount.getOrDefault(itemId, 0)

        return if (currentRetry < maxRetries && retryQueue.size < maxSize) {
            retryCount[itemId] = currentRetry + 1
            retryQueue.add(item)
            logger.debug("Queued for retry (${currentRetry + 1}/$maxRetries): $itemId")
            true
        } else {
            logger.warn("Max retries reached or queue full: $itemId")
            false
        }
    }

    /**
     * 제네릭 워커 생성
     *
     * @param config 워커 설정
     * @param processFunc 처리 함수 (item을 받아 Unit 반환)
     * @return Job 리스트
     */
    fun <T> launchWorkers(
        scope: CoroutineScope,
        config: WorkerConfig<T>,
        processFunc: suspend (T) -> Unit
    ): List<Job> {
        return List(config.workerCount) { workerIndex ->
            scope.launch(Dispatchers.IO) {
                logger.debug("${config.name} worker #$workerIndex started")

                for (item in config.queue) {
                    config.queueCounter.decrementAndGet()

                    try {
                        processFunc(item)

                        if (config.delayMs > 0) {
                            kotlinx.coroutines.delay(config.delayMs)
                        }
                    } catch (e: Exception) {
                        logger.error("${config.name} worker #$workerIndex error: ${e.message}")
                    }
                }

                logger.debug("${config.name} worker #$workerIndex completed")
            }
        }
    }

    /**
     * DeepSeek 워커 생성 (전용)
     */
    fun launchDeepSeekWorkers(
        scope: CoroutineScope,
        workerCount: Int,
        queue: Channel<MethodNode>,
        queueCounter: AtomicInteger,
        sourceCache: MethodSourceCache,
        extractFunc: (MethodNode) -> String?,
        analyzeFunc: (MethodNode, String) -> MethodAnalysisResult?,
        nextQueue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
        nextQueueCounter: AtomicInteger,
        retryQueue: java.util.Queue<MethodNode>,
        retryCount: ConcurrentHashMap<String, Int>,
        maxRetries: Int,
        maxRetryQueueSize: Int,
        processedMethods: MutableSet<String>,
        failureHandler: (MethodNode, String) -> Unit,
        delayMs: Long
    ): List<Job> {
        return launchWorkers(scope, WorkerConfig(workerCount, queue, queueCounter, "DeepSeek", delayMs)) { method ->
            val sourceCode = loadSourceCode(method, sourceCache, extractFunc)

            if (sourceCode == null) {
                logger.warn("No source code for ${method.methodName}")
                processedMethods.add(method.id)
                failureHandler(method, "No source code")
                return@launchWorkers
            }

            val analysis = analyzeFunc(method, sourceCode)

            if (analysis != null) {
                sourceCache.putAnalysis(method, analysis)

                nextQueueCounter.incrementAndGet()
                nextQueue.send(method to analysis)
            } else {
                processedMethods.add(method.id)
                if (!shouldRetry(method, retryCount, maxRetries, retryQueue, maxRetryQueueSize, method.id)) {
                    failureHandler(method, "Analysis failed after $maxRetries retries")
                }
            }
        }
    }

    /**
     * Qwen (번역) 워커 생성 (전용)
     */
    fun launchTranslationWorkers(
        scope: CoroutineScope,
        workerCount: Int,
        queue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
        queueCounter: AtomicInteger,
        translateFunc: (MethodAnalysisResult) -> MethodAnalysisResult?,
        nextQueue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
        nextQueueCounter: AtomicInteger,
        processedMethods: MutableSet<String>,
        failureHandler: (MethodNode, String) -> Unit
    ): List<Job> {
        return launchWorkers(scope, WorkerConfig(workerCount, queue, queueCounter, "Translation")) { (method, analysis) ->
            try {
                val translated = translateFunc(analysis) ?: analysis

                nextQueueCounter.incrementAndGet()
                nextQueue.send(method to translated)
            } catch (e: Exception) {
                logger.error("Translation error for ${method.methodName}: ${e.message}")
                processedMethods.add(method.id)
                failureHandler(method, "Translation failed: ${e.message}")
            }
        }
    }

    /**
     * Rename 워커 생성 (전용)
     */
    fun launchRenameWorkers(
        scope: CoroutineScope,
        workerCount: Int,
        queue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
        queueCounter: AtomicInteger,
        sourceCache: MethodSourceCache,
        extractFunc: (MethodNode) -> String?,
        renameFunc: (File, MethodNode, MethodAnalysisResult) -> RenameResult,
        processedMethods: MutableSet<String>,
        successHandler: (MethodNode, MethodAnalysisResult, String, RenameResult.Success) -> Unit,
        failureHandler: (MethodNode, String) -> Unit
    ): List<Job> where RenameResult : com.whatap.apk2project.deobfuscator.renamer.RenameResult {
        return launchWorkers(scope, WorkerConfig(workerCount, queue, queueCounter, "Rename")) { (method, analysis) ->
            val sourceCode = loadSourceCode(method, sourceCache, extractFunc)

            if (sourceCode == null) {
                logger.warn("No source code for renaming ${method.methodName}")
                processedMethods.add(method.id)
                failureHandler(method, "No source code")
                return@launchWorkers
            }

            val result = renameFunc(method.file, method, analysis)

            when (result) {
                is com.whatap.apk2project.deobfuscator.renamer.RenameResult.Success -> {
                    successHandler(method, analysis, sourceCode, result)
                }
                is com.whatap.apk2project.deobfuscator.renamer.RenameResult.Failure -> {
                    failureHandler(method, result.reason)
                }
            }

            processedMethods.add(method.id)
        }
    }
}
