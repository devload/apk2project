package com.whatap.apk2project.deobfuscator.monitor

import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * status.json 저장을 전담하는 클래스
 * 
 * 특징:
 * - 비동기 파일 쓰기 (Coroutine 활용)
 * - 쓰로틀링 (너무 자주 쓰지 않도록)
 * - 마지막 데이터 보존 (앱 종료 시)
 */
class StatusFileStorage(
    private val statusFile: File,
    private val minWriteIntervalMs: Long = 1000L,  // 최소 쓰기 간격 (1초)
    private val gson: Gson = com.google.gson.GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .serializeSpecialFloatingPointValues()
        .create()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 저장 요청을 받는 채널 (버퍼 사이즈 1: 최신 데이터만 유지)
    private val updateChannel = Channel<ProgressStatus>(capacity = Channel.UNLIMITED)
    
    // 가장 최신 데이터 보존 (앱 종료 시 쓰기용)
    private val latestStatus = AtomicReference<ProgressStatus?>(null)
    
    // 저장 작업 Job
    private var writerJob: Job? = null
    
    // 통계
    private var writeCount = 0L
    private var skippedCount = 0L
    
    init {
        startWriter()
    }
    
    /**
     * 비동기 저장 시작
     */
    private fun startWriter() {
        writerJob = scope.launch {
            var lastWriteTime = 0L

            // 채널에서 업데이트 요청 처리
            updateChannel.consumeEach { status ->
                val now = System.currentTimeMillis()
                val elapsed = now - lastWriteTime

                // 최신 데이터 보존
                latestStatus.set(status)

                // 쓰로틀링: minWriteIntervalMs 이내면 대기 후 쓰기
                if (elapsed < minWriteIntervalMs) {
                    skippedCount++
                    delay(minWriteIntervalMs - elapsed)  // 남은 시간만큼 대기
                }

                // 실제 파일 쓰기 (매번 수행)
                writeToFile(status)
                lastWriteTime = System.currentTimeMillis()
                writeCount++
            }
        }
    }
    
    /**
     * 비동기 저장 요청 (Channel에 전송)
     */
    fun saveAsync(status: ProgressStatus) {
        val result = updateChannel.trySend(status)
        if (result.isFailure) {
            // 채널이 닫힌 경우 동기식으로 fallback
            System.err.println("Warning: Update channel closed, falling back to sync write: ${result.exceptionOrNull()?.message}")
            writeToFile(status)
        }
    }
    
    /**
     * 동기 저장 요청 (즉시 쓰기)
     */
    fun saveSync(status: ProgressStatus) {
        latestStatus.set(status)
        writeToFile(status)
        writeCount++
    }
    
    /**
     * 실제 파일 쓰기 (I/O 작업)
     */
    private fun writeToFile(status: ProgressStatus) {
        try {
            val json = gson.toJson(status)
            statusFile.writeText(json)
        } catch (e: Exception) {
            System.err.println("Failed to write status.json: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 마지막 데이터 강제 저장 (앱 종료 시 호출)
     */
    fun flush() {
        latestStatus.get()?.let { status ->
            writeToFile(status)
        }
    }
    
    /**
     * 저장 종료
     */
    fun shutdown() {
        runBlocking {
            // 마지막 데이터 저장
            flush()
            
            // 채널 닫기
            updateChannel.close()
            
            // Writer Job 종료 대기
            writerJob?.join()
            
            // Scope 취소
            scope.cancel()
        }
    }
    
    /**
     * 통계 정보
     */
    fun getStats(): StorageStats = StorageStats(
        totalWrites = writeCount,
        skippedWrites = skippedCount,
        currentBufferSize = updateChannel.tryReceive().getOrNull()?.let { 1 } ?: 0
    )
}

data class StorageStats(
    val totalWrites: Long,
    val skippedWrites: Long,
    val currentBufferSize: Int
)
