package com.whatap.apk2project.deobfuscator.cache

import com.google.gson.Gson
import com.whatap.apk2project.deobfuscator.client.MethodAnalysisResult
import com.whatap.apk2project.deobfuscator.model.MethodNode
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 메서드 소스 코드 캐시 (디스크 기반)
 *
 * 특징:
 * - 소스 코드는 파일 시스템에 저장 (OOM 방지)
 * - 메모리에는 메타데이터만 보관
 * - LRU eviction으로 메모리 사용량 제어
 * - 영속성: 파이프라인 중단되어도 재개 가능
 *
 * @param cacheDir 캐시 파일 저장 디렉토리
 * @param maxMemorySize 메모리에 보관할 최대 소스 코드 수 (기본 100)
 */
class MethodSourceCache(
    private val cacheDir: File,
    private val maxMemorySize: Int = 100
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val gson = Gson()

    // 메모리 캐시 (LRU)
    private val memoryCache = LinkedHashMap<String, CachedSource>(maxMemorySize, 0.75f, true)

    // 캐시 히트 통계
    private val memoryHits = AtomicInteger(0)
    private val diskHits = AtomicInteger(0)
    private val misses = AtomicInteger(0)

    init {
        cacheDir.mkdirs()
        logger.info("MethodSourceCache initialized: dir=$cacheDir, maxMemorySize=$maxMemorySize")
    }

    /**
     * 캐시된 소스 정보
     */
    data class CachedSource(
        val methodId: String,
        val sourceCode: String,
        val fileSize: Long,
        val lastAccessed: Long = System.currentTimeMillis()
    )

    /**
     * 분석 결과 캐시
     */
    data class CachedAnalysis(
        val methodId: String,
        val analysis: MethodAnalysisResult,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 소스 코드 저장 (디스크 + 메모리)
     */
    fun putSource(method: MethodNode, sourceCode: String) {
        val sourceFile = getSourceFile(method.id)

        // 디스크에 저장
        sourceFile.writeText(sourceCode)

        // 메모리에도 보관 (LRU)
        synchronized(memoryCache) {
            memoryCache[method.id] = CachedSource(
                methodId = method.id,
                sourceCode = sourceCode,
                fileSize = sourceFile.length()
            )

            // 용량 초과시 가장 오래된 항목 제거
            if (memoryCache.size > maxMemorySize) {
                val oldest = memoryCache.keys.first()
                memoryCache.remove(oldest)
                logger.debug("Evicted from memory cache: $oldest")
            }
        }
    }

    /**
     * 소스 코드 로드 (메모리 → 디스크 → 원본 파일)
     */
    fun getSource(method: MethodNode): String? {
        // 1. 메모리 캐시 확인
        synchronized(memoryCache) {
            memoryCache[method.id]?.let { cached ->
                memoryHits.incrementAndGet()
                return cached.sourceCode
            }
        }

        // 2. 디스크 캐시 확인
        val sourceFile = getSourceFile(method.id)
        if (sourceFile.exists()) {
            diskHits.incrementAndGet()
            val sourceCode = sourceFile.readText()

            // 메모리로 올리기
            synchronized(memoryCache) {
                memoryCache[method.id] = CachedSource(
                    methodId = method.id,
                    sourceCode = sourceCode,
                    fileSize = sourceFile.length()
                )
            }

            return sourceCode
        }

        // 3. 캐시 미스
        misses.incrementAndGet()
        return null
    }

    /**
     * 분석 결과 저장
     */
    fun putAnalysis(method: MethodNode, analysis: MethodAnalysisResult) {
        val analysisFile = getAnalysisFile(method.id)
        val cached = CachedAnalysis(method.id, analysis)
        analysisFile.writeText(gson.toJson(cached))
    }

    /**
     * 분석 결과 로드
     */
    fun getAnalysis(method: MethodNode): MethodAnalysisResult? {
        val analysisFile = getAnalysisFile(method.id)
        if (!analysisFile.exists()) return null

        return try {
            val json = analysisFile.readText()
            val cached = gson.fromJson(json, CachedAnalysis::class.java)
            cached.analysis
        } catch (e: Exception) {
            logger.warn("Failed to load analysis for ${method.id}: ${e.message}")
            null
        }
    }

    /**
     * 캐시 통계 출력
     */
    fun printStats() {
        val total = memoryHits.get() + diskHits.get() + misses.get()
        val hitRate = if (total > 0) {
            ((memoryHits.get() + diskHits.get()) * 100.0 / total)
        } else 0.0

        logger.info("Cache Statistics:")
        logger.info("  Memory hits: ${memoryHits.get()}")
        logger.info("  Disk hits: ${diskHits.get()}")
        logger.info("  Misses: ${misses.get()}")
        logger.info("  Hit rate: ${"%.2f".format(hitRate)}%")
        logger.info("  Memory cache size: ${memoryCache.size}/$maxMemorySize")
        logger.info("  Disk cache size: ${cacheDir.listFiles()?.size ?: 0} files")
    }

    /**
     * 캐시 정리 (오래된 파일 삭제)
     */
    fun cleanup(maxAgeMillis: Long = 24 * 60 * 60 * 1000) { // 기본 24시간
        val now = System.currentTimeMillis()
        var deletedCount = 0

        cacheDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > maxAgeMillis) {
                file.delete()
                deletedCount++
            }
        }

        logger.info("Cleaned up $deletedCount old cache files")
    }

    /**
     * 캐시 전체 삭제
     */
    fun clear() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        synchronized(memoryCache) {
            memoryCache.clear()
        }
        memoryHits.set(0)
        diskHits.set(0)
        misses.set(0)
        logger.info("Cache cleared")
    }

    /**
     * 소스 코드 파일 경로
     */
    private fun getSourceFile(methodId: String): File {
        // 해시 기반 분산 (한 디렉토리에 파일 너무 많은 것 방지)
        val hash = methodId.hashCode()
        val subDir = String.format("%02x", hash and 0xff)
        val subCacheDir = File(cacheDir, "sources/$subDir")
        subCacheDir.mkdirs()
        return File(subCacheDir, "$methodId.java")
    }

    /**
     * 분석 결과 파일 경로
     */
    private fun getAnalysisFile(methodId: String): File {
        val hash = methodId.hashCode()
        val subDir = String.format("%02x", hash and 0xff)
        val subCacheDir = File(cacheDir, "analyses/$subDir")
        subCacheDir.mkdirs()
        return File(subCacheDir, "$methodId.json")
    }
}
