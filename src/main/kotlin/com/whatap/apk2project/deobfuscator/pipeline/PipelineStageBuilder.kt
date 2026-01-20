package com.whatap.apk2project.deobfuscator.pipeline

import com.whatap.apk2project.deobfuscator.cache.MethodSourceCache
import com.whatap.apk2project.deobfuscator.client.MethodAnalysisResult
import com.whatap.apk2project.deobfuscator.model.MethodNode
import com.whatap.apk2project.deobfuscator.renamer.RenameResult
import com.whatap.apk2project.deobfuscator.renamer.SourceRenamer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 파이프라인 스테이지 빌더
 *
 * Korean translation 활성화/비활성화에 따른 코드 중복을 제거하기 위한 추상화
 *
 * 파이프라인 구조:
 * - Korean 활성화: DeepSeek → Translation → Rename
 * - Korean 비활성화: DeepSeek → Rename
 */
class PipelineStageBuilder(
    private val config: PipelineConfig,
    private val sourceCache: MethodSourceCache,
    private val processedMethods: MutableSet<String>,
    private val extractFunc: (MethodNode) -> String?,
    private val analyzeFunc: (MethodNode, String) -> MethodAnalysisResult?,
    private val translateFunc: ((MethodAnalysisResult) -> MethodAnalysisResult)?,
    private val renameFunc: (java.io.File, MethodNode, MethodAnalysisResult) -> RenameResult
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 메소드 처리 파이프라인 실행
     *
     * @param scope 코루틴 스코프
     * @param methods 처리할 메소드 목록
     * @param onSuccess 성공 콜백
     * @param onFailure 실패 콜백
     * @param onQueueSizeUpdate 큐 크기 업데이트 콜백
     * @return 성공적으로 처리된 메소드 수
     */
    suspend fun runMethodPipeline(
        scope: CoroutineScope,
        methods: List<MethodNode>,
        onSuccess: (MethodNode, MethodAnalysisResult, String, RenameResult) -> Unit,
        onFailure: (MethodNode, String) -> Unit,
        onQueueSizeUpdate: (deepseek: Int, translation: Int?, rename: Int) -> Unit
    ): Int {
        val queueCapacity = config.batchSize * 10
        val deepseekQueue = Channel<MethodNode>(capacity = queueCapacity)
        val renameQueue = Channel<Pair<MethodNode, MethodAnalysisResult>>(capacity = queueCapacity)

        val successCount = AtomicInteger(0)

        // 재시도 관련
        val maxRetryQueueSize = 500
        val retryQueue = java.util.concurrent.ConcurrentLinkedQueue<MethodNode>()
        val retryCount = ConcurrentHashMap<String, Int>()
        val maxRetries = 5

        // 큐 크기 카운터
        val deepseekQueueCounter = AtomicInteger(0)
        val translationQueueCounter = AtomicInteger(0)
        val renameQueueCounter = AtomicInteger(0)

        return if (translateFunc != null) {
            // Korean translation 활성화: 3-stage pipeline
            runThreeStagePipeline(
                scope = scope,
                methods = methods,
                queueCapacity = queueCapacity,
                deepseekQueue = deepseekQueue,
                renameQueue = renameQueue,
                deepseekQueueCounter = deepseekQueueCounter,
                translationQueueCounter = translationQueueCounter,
                renameQueueCounter = renameQueueCounter,
                retryQueue = retryQueue,
                retryCount = retryCount,
                maxRetries = maxRetries,
                maxRetryQueueSize = maxRetryQueueSize,
                successCount = successCount,
                onSuccess = onSuccess,
                onFailure = onFailure,
                onQueueSizeUpdate = onQueueSizeUpdate
            )
        } else {
            // Korean translation 비활성화: 2-stage pipeline
            runTwoStagePipeline(
                scope = scope,
                methods = methods,
                deepseekQueue = deepseekQueue,
                renameQueue = renameQueue,
                deepseekQueueCounter = deepseekQueueCounter,
                renameQueueCounter = renameQueueCounter,
                retryQueue = retryQueue,
                retryCount = retryCount,
                maxRetries = maxRetries,
                maxRetryQueueSize = maxRetryQueueSize,
                successCount = successCount,
                onSuccess = onSuccess,
                onFailure = onFailure,
                onQueueSizeUpdate = { deepseek, _, rename ->
                    onQueueSizeUpdate(deepseek, null, rename)
                }
            )
        }
    }

    /**
     * 3-stage pipeline: DeepSeek → Translation → Rename
     */
    private suspend fun runThreeStagePipeline(
        scope: CoroutineScope,
        methods: List<MethodNode>,
        queueCapacity: Int,
        deepseekQueue: Channel<MethodNode>,
        renameQueue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
        deepseekQueueCounter: AtomicInteger,
        translationQueueCounter: AtomicInteger,
        renameQueueCounter: AtomicInteger,
        retryQueue: java.util.Queue<MethodNode>,
        retryCount: ConcurrentHashMap<String, Int>,
        maxRetries: Int,
        maxRetryQueueSize: Int,
        successCount: AtomicInteger,
        onSuccess: (MethodNode, MethodAnalysisResult, String, RenameResult) -> Unit,
        onFailure: (MethodNode, String) -> Unit,
        onQueueSizeUpdate: (Int, Int?, Int) -> Unit
    ): Int = scope.run {
        val translationQueue = Channel<Pair<MethodNode, MethodAnalysisResult>>(capacity = queueCapacity)

        // DeepSeek Workers
        val deepseekJobs = launchDeepSeekWorkers(
            scope = this,
            queue = deepseekQueue,
            queueCounter = deepseekQueueCounter,
            nextQueue = translationQueue,
            nextQueueCounter = translationQueueCounter,
            retryQueue = retryQueue,
            retryCount = retryCount,
            maxRetries = maxRetries,
            maxRetryQueueSize = maxRetryQueueSize
        )

        // Translation Workers
        val translationJobs = launchTranslationWorkers(
            scope = this,
            queue = translationQueue,
            queueCounter = translationQueueCounter,
            nextQueue = renameQueue,
            nextQueueCounter = renameQueueCounter
        )

        // Rename Workers
        val renameJobs = launchRenameWorkers(
            scope = this,
            queue = renameQueue,
            queueCounter = renameQueueCounter,
            successCount = successCount,
            onSuccess = onSuccess,
            onFailure = onFailure
        )

        // Producer
        launchProducer(this, methods, deepseekQueue, deepseekQueueCounter)

        // Queue monitoring
        val monitorJob = launch(Dispatchers.IO) {
            while (isActive) {
                onQueueSizeUpdate(
                    deepseekQueueCounter.get(),
                    translationQueueCounter.get(),
                    renameQueueCounter.get()
                )
                delay(100)
            }
        }

        // Wait for completion
        deepseekJobs.forEach { it.join() }
        monitorJob.cancel()
        logger.info("DeepSeek stage complete")
        translationQueue.close()
        translationJobs.forEach { it.join() }
        logger.info("Translation stage complete")
        renameQueue.close()
        renameJobs.forEach { it.join() }
        logger.info("Rename stage complete")

        successCount.get()
    }

    /**
     * 2-stage pipeline: DeepSeek → Rename
     */
    private suspend fun runTwoStagePipeline(
        scope: CoroutineScope,
        methods: List<MethodNode>,
        deepseekQueue: Channel<MethodNode>,
        renameQueue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
        deepseekQueueCounter: AtomicInteger,
        renameQueueCounter: AtomicInteger,
        retryQueue: java.util.Queue<MethodNode>,
        retryCount: ConcurrentHashMap<String, Int>,
        maxRetries: Int,
        maxRetryQueueSize: Int,
        successCount: AtomicInteger,
        onSuccess: (MethodNode, MethodAnalysisResult, String, RenameResult) -> Unit,
        onFailure: (MethodNode, String) -> Unit,
        onQueueSizeUpdate: (Int, Int?, Int) -> Unit
    ): Int = scope.run {
        logger.info("Korean translation disabled, using simplified pipeline: DeepSeek → Rename")

        // DeepSeek Workers (직접 Rename 큐로 전달)
        val deepseekJobs = launchDeepSeekWorkers(
            scope = this,
            queue = deepseekQueue,
            queueCounter = deepseekQueueCounter,
            nextQueue = renameQueue,
            nextQueueCounter = renameQueueCounter,
            retryQueue = retryQueue,
            retryCount = retryCount,
            maxRetries = maxRetries,
            maxRetryQueueSize = maxRetryQueueSize
        )

        // Rename Workers
        val renameJobs = launchRenameWorkers(
            scope = this,
            queue = renameQueue,
            queueCounter = renameQueueCounter,
            successCount = successCount,
            onSuccess = onSuccess,
            onFailure = onFailure
        )

        // Producer
        launchProducer(this, methods, deepseekQueue, deepseekQueueCounter)

        // Queue monitoring
        val monitorJob = launch(Dispatchers.IO) {
            while (isActive) {
                onQueueSizeUpdate(
                    deepseekQueueCounter.get(),
                    null,
                    renameQueueCounter.get()
                )
                delay(100)
            }
        }

        // Wait for completion
        deepseekJobs.forEach { it.join() }
        monitorJob.cancel()
        logger.info("DeepSeek stage complete")
        renameQueue.close()
        renameJobs.forEach { it.join() }
        logger.info("Rename stage complete")

        successCount.get()
    }

    private fun launchDeepSeekWorkers(
        scope: CoroutineScope,
        queue: Channel<MethodNode>,
        queueCounter: AtomicInteger,
        nextQueue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
        nextQueueCounter: AtomicInteger,
        retryQueue: java.util.Queue<MethodNode>,
        retryCount: ConcurrentHashMap<String, Int>,
        maxRetries: Int,
        maxRetryQueueSize: Int
    ): List<Job> {
        return List(config.batchSize) { workerIndex ->
            scope.launch(Dispatchers.IO) {
                for (method in queue) {
                    queueCounter.decrementAndGet()

                    try {
                        val sourceCode = sourceCache.getSource(method) ?: extractFunc(method)
                        if (sourceCode == null) {
                            processedMethods.add(method.id)
                            continue
                        }

                        val analysis = analyzeFunc(method, sourceCode)
                        if (analysis != null) {
                            sourceCache.putSource(method, sourceCode)
                            sourceCache.putAnalysis(method, analysis)
                            nextQueueCounter.incrementAndGet()
                            nextQueue.send(method to analysis)
                        } else {
                            handleRetry(method, retryQueue, retryCount, maxRetries, maxRetryQueueSize)
                        }
                    } catch (e: Exception) {
                        logger.error("DeepSeek worker $workerIndex error: ${e.message}")
                        handleRetry(method, retryQueue, retryCount, maxRetries, maxRetryQueueSize)
                    }

                    delay(config.requestDelay / config.batchSize)
                }
            }
        }
    }

    private fun launchTranslationWorkers(
        scope: CoroutineScope,
        queue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
        queueCounter: AtomicInteger,
        nextQueue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
        nextQueueCounter: AtomicInteger
    ): List<Job> {
        val translationFn = translateFunc ?: return emptyList()

        return List(config.batchSize) { workerIndex ->
            scope.launch(Dispatchers.IO) {
                for ((method, analysis) in queue) {
                    queueCounter.decrementAndGet()

                    try {
                        val translated = translationFn(analysis)
                        nextQueueCounter.incrementAndGet()
                        nextQueue.send(method to translated)
                    } catch (e: Exception) {
                        logger.error("Translation worker $workerIndex error: ${e.message}")
                        // 번역 실패 시 원본 전달
                        nextQueueCounter.incrementAndGet()
                        nextQueue.send(method to analysis)
                    }
                }
            }
        }
    }

    private fun launchRenameWorkers(
        scope: CoroutineScope,
        queue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
        queueCounter: AtomicInteger,
        successCount: AtomicInteger,
        onSuccess: (MethodNode, MethodAnalysisResult, String, RenameResult) -> Unit,
        onFailure: (MethodNode, String) -> Unit
    ): List<Job> {
        return List(config.batchSize) { workerIndex ->
            scope.launch(Dispatchers.IO) {
                for ((method, analysis) in queue) {
                    queueCounter.decrementAndGet()

                    try {
                        val sourceCode = sourceCache.getSource(method) ?: ""
                        val result = renameFunc(method.file, method, analysis)

                        when (result) {
                            is RenameResult.Success -> {
                                onSuccess(method, analysis, sourceCode, result)
                                successCount.incrementAndGet()
                            }
                            is RenameResult.Failure -> {
                                onFailure(method, result.reason)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Rename worker $workerIndex error: ${e.message}")
                        onFailure(method, e.message ?: "Unknown error")
                    } finally {
                        processedMethods.add(method.id)
                    }
                }
            }
        }
    }

    private suspend fun launchProducer(
        scope: CoroutineScope,
        methods: List<MethodNode>,
        queue: Channel<MethodNode>,
        queueCounter: AtomicInteger
    ) {
        val producerJob = scope.launch(Dispatchers.IO) {
            var sentCount = 0
            for (method in methods) {
                if (method.id in processedMethods) continue

                val sourceCode = extractFunc(method)
                if (sourceCode != null) {
                    sourceCache.putSource(method, sourceCode)
                    queueCounter.incrementAndGet()
                    queue.send(method)
                    sentCount++
                } else {
                    logger.debug("No source code for ${method.id}")
                    processedMethods.add(method.id)
                }
            }
            logger.info("Producer: Sent $sentCount methods to queue")
            queue.close()
        }

        producerJob.join()
    }

    private fun handleRetry(
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
        }
    }
}
