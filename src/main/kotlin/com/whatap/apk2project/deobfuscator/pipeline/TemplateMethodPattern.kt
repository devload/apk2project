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
 * 템플릿 메소드 패턴 기반 파이프라인 워커
 *
 * 구조:
 * 1. process() = 템플릿 메소드 (전체 알고리즘 정의)
 * 2. hook 메소드들 = 확장 포인트 (하위 클래스에서 오버라이드)
 *
 * 장점:
 * - 알고리즘 구조와 구현 분리
 * - 코드 재사용성 극대화
 * - 확장 포인트 명확화
 */
abstract class PipelineWorker<T, R>(
    protected val scope: CoroutineScope,
    protected val workerCount: Int,
    protected val queue: Channel<T>,
    protected val queueCounter: AtomicInteger,
    protected val workerName: String
) {
    protected val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 템플릿 메소드: 워커의 전체 처리 흐름 정의
     *
     * 흐름:
     * 1. 큐에서 항목 수신
     * 2. 전처리 (preProcess)
     * 3. 유효성 검사 (validate)
     * 4. 실제 처리 (processItem - abstract)
     * 5. 후처리 (postProcess)
     * 6. 예외 처리 (handleError)
     */
    protected suspend fun processItem(item: T) {
        queueCounter.decrementAndGet()

        try {
            // Hook 1: 전처리
            preProcess(item)

            // Hook 2: 유효성 검사
            val validationResult = validate(item)
            if (!validationResult.isValid) {
                onValidationFailed(item, validationResult.reason!!)
                return
            }

            // Hook 3: 실제 처리 (추상 메소드 - 하위 클래스에서 구현)
            val result = processItem(item, validationResult.data!!)

            // Hook 4: 후처리
            postProcess(item, result)

            // Hook 5: 성공 처리
            onSuccess(item, result)

        } catch (e: Exception) {
            // Hook 6: 예외 처리
            handleError(item, e)
        }
    }

    /**
     * 추상 메소드: 실제 처리 로직 (하위 클래스에서 필수 구현)
     */
    protected abstract suspend fun processItem(item: T, data: Any?): R

    /**
     * Hook 1: 전처리 (선택적 오버라이드)
     */
    protected open suspend fun preProcess(item: T) {}

    /**
     * Hook 2: 유효성 검사 (선택적 오버라이드)
     */
    protected open fun validate(item: T): ValidationResult =
        ValidationResult(true, null, null)

    /**
     * Hook 3: 검증 실패 처리 (선택적 오버라이드)
     */
    protected open fun onValidationFailed(item: T, reason: String) {
        logger.warn("$workerName validation failed: $reason")
    }

    /**
     * Hook 4: 후처리 (선택적 오버라이드)
     */
    protected open suspend fun postProcess(item: T, result: R) {}

    /**
     * Hook 5: 성공 처리 (선택적 오버라이드)
     */
    protected open fun onSuccess(item: T, result: R) {}

    /**
     * Hook 6: 예외 처리 (선택적 오버라이드)
     */
    protected open fun handleError(item: T, error: Exception) {
        logger.error("$workerName error: ${error.message}", error)
    }

    /**
     * 워커 생성 및 시작
     */
    fun launch(): List<Job> {
        return List(workerCount) { workerIndex ->
            scope.launch(Dispatchers.IO) {
                logger.debug("$workerName worker #$workerIndex started")

                for (item in queue) {
                    processItem(item)
                }

                logger.debug("$workerName worker #$workerIndex completed")
            }
        }
    }

    /**
     * 유효성 검사 결과
     */
    data class ValidationResult(
        val isValid: Boolean,
        val reason: String?,
        val data: Any?
    )
}

/**
 * DeepSeek 워커 구현 (템플릿 메소드 패턴)
 */
class DeepSeekWorker(
    scope: CoroutineScope,
    workerCount: Int,
    queue: Channel<MethodNode>,
    queueCounter: AtomicInteger,
    private val sourceCache: MethodSourceCache,
    private val extractFunc: (MethodNode) -> String?,
    private val analyzeFunc: (MethodNode, String) -> MethodAnalysisResult?,
    private val nextQueue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
    private val nextQueueCounter: AtomicInteger,
    private val retryQueue: java.util.Queue<MethodNode>,
    private val retryCount: ConcurrentHashMap<String, Int>,
    private val maxRetries: Int,
    private val maxRetryQueueSize: Int,
    private val delayMs: Long
) : PipelineWorker<MethodNode, MethodAnalysisResult>(
    scope, workerCount, queue, queueCounter, "DeepSeek"
) {

    override fun validate(item: MethodNode): ValidationResult {
        // 소스 코드 로드 및 검증
        val sourceCode = loadSourceCode(item)
        return if (sourceCode != null) {
            ValidationResult(true, null, sourceCode)
        } else {
            ValidationResult(false, "No source code for ${item.methodName}", null)
        }
    }

    override suspend fun processItem(item: MethodNode, data: Any?): MethodAnalysisResult {
        val sourceCode = data as String
        val result = analyzeFunc(item, sourceCode)

        if (result == null) {
            throw RuntimeException("Analysis returned null for ${item.methodName}")
        }

        return result
    }

    override fun onSuccess(item: MethodNode, result: MethodAnalysisResult) {
        sourceCache.putAnalysis(item, result)
        nextQueueCounter.incrementAndGet()

        // 비동기 전송
        scope.launch(Dispatchers.IO) {
            nextQueue.send(item to result)
        }
    }

    override fun handleError(item: MethodNode, error: Exception) {
        logger.error("DeepSeek error for ${item.methodName}: ${error.message}")
        shouldRetry(item)
    }

    override suspend fun postProcess(item: MethodNode, result: MethodAnalysisResult) {
        if (delayMs > 0) {
            kotlinx.coroutines.delay(delayMs)
        }
    }

    private fun loadSourceCode(method: MethodNode): String? {
        val cached = sourceCache.getSource(method)
        if (cached != null) return cached

        val extracted = extractFunc(method)
        if (extracted != null) {
            sourceCache.putSource(method, extracted)
        }
        return extracted
    }

    private fun shouldRetry(method: MethodNode) {
        val currentRetry = retryCount.getOrDefault(method.id, 0)
        if (currentRetry < maxRetries && retryQueue.size < maxRetryQueueSize) {
            retryCount[method.id] = currentRetry + 1
            retryQueue.add(method)
            logger.debug("Queued for retry (${currentRetry + 1}/$maxRetries): ${method.methodName}")
        } else {
            logger.warn("Max retries reached or queue full: ${method.methodName}")
        }
    }
}

/**
 * 번역 워커 구현 (템플릿 메소드 패턴)
 */
class TranslationWorker(
    scope: CoroutineScope,
    workerCount: Int,
    queue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
    queueCounter: AtomicInteger,
    private val translateFunc: (MethodAnalysisResult) -> MethodAnalysisResult?,
    private val nextQueue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
    private val nextQueueCounter: AtomicInteger
) : PipelineWorker<Pair<MethodNode, MethodAnalysisResult>, MethodAnalysisResult>(
    scope, workerCount, queue, queueCounter, "Translation"
) {

    override suspend fun processItem(
        item: Pair<MethodNode, MethodAnalysisResult>,
        data: Any?
    ): MethodAnalysisResult {
        val (method, analysis) = item
        logger.debug("Translating: ${method.methodName}")

        return translateFunc(analysis) ?: analysis
    }

    override fun onSuccess(
        item: Pair<MethodNode, MethodAnalysisResult>,
        result: MethodAnalysisResult
    ) {
        nextQueueCounter.incrementAndGet()

        // 비동기 전송
        scope.launch(Dispatchers.IO) {
            nextQueue.send(item.first to result)
        }
    }
}

/**
 * 리네임 워커 구현 (템플릿 메소드 패턴)
 */
class RenameWorker(
    scope: CoroutineScope,
    workerCount: Int,
    queue: Channel<Pair<MethodNode, MethodAnalysisResult>>,
    queueCounter: AtomicInteger,
    private val sourceCache: MethodSourceCache,
    private val extractFunc: (MethodNode) -> String?,
    private val renameFunc: (File, MethodNode, MethodAnalysisResult) -> com.whatap.apk2project.deobfuscator.renamer.RenameResult,
    private val processedMethods: MutableSet<String>,
    private val onSuccessHandler: (MethodNode, MethodAnalysisResult, String, com.whatap.apk2project.deobfuscator.renamer.RenameResult.Success) -> Unit,
    private val onFailureHandler: (MethodNode, String) -> Unit
) : PipelineWorker<Pair<MethodNode, MethodAnalysisResult>, com.whatap.apk2project.deobfuscator.renamer.RenameResult>(
    scope, workerCount, queue, queueCounter, "Rename"
) {

    override fun validate(item: Pair<MethodNode, MethodAnalysisResult>): ValidationResult {
        val (method, _) = item
        val sourceCode = loadSourceCode(method)
        return if (sourceCode != null) {
            ValidationResult(true, null, sourceCode)
        } else {
            ValidationResult(false, "No source code for ${method.methodName}", null)
        }
    }

    override suspend fun processItem(
        item: Pair<MethodNode, MethodAnalysisResult>,
        data: Any?
    ): com.whatap.apk2project.deobfuscator.renamer.RenameResult {
        val (method, analysis) = item
        logger.debug("Renaming: ${method.methodName}")

        return renameFunc(method.file, method, analysis)
    }

    override fun onSuccess(
        item: Pair<MethodNode, MethodAnalysisResult>,
        result: com.whatap.apk2project.deobfuscator.renamer.RenameResult
    ) {
        val (method, analysis) = item
        processedMethods.add(method.id)

        when (result) {
            is com.whatap.apk2project.deobfuscator.renamer.RenameResult.Success -> {
                val sourceCode = loadSourceCode(method)!!
                onSuccessHandler(method, analysis, sourceCode, result)
            }
            is com.whatap.apk2project.deobfuscator.renamer.RenameResult.Failure -> {
                onFailureHandler(method, result.reason)
            }
        }
    }

    override fun handleError(item: Pair<MethodNode, MethodAnalysisResult>, error: Exception) {
        val (method, _) = item
        processedMethods.add(method.id)
        onFailureHandler(method, error.message ?: "Unknown error")
    }

    private fun loadSourceCode(method: MethodNode): String? {
        val cached = sourceCache.getSource(method)
        if (cached != null) return cached

        val extracted = extractFunc(method)
        if (extracted != null) {
            sourceCache.putSource(method, extracted)
        }
        return extracted
    }
}
