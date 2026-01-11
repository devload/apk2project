package com.whatap.apk2project.deobfuscator.session

import com.google.gson.GsonBuilder
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * 분석 세션 관리 - 이어하기 기능 지원
 */
class SessionManager(
    private val outputDir: File
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val sessionFile = File(outputDir, "session.json")

    /**
     * 새로운 세션 생성
     */
    fun createNewSession(sourceDir: File): Session {
        // 이전 세션 정리
        if (sessionFile.exists()) {
            val oldSession = loadSession()
            if (oldSession != null) {
                logger.info("Cleaning previous session: ${oldSession.sessionId}")
                cleanSessionFiles(oldSession)
            }
        }

        // 새 세션 ID 생성 (소스 디렉토리 경로 + 타임스탬프 해시)
        val sessionId = generateSessionId(sourceDir)
        val session = Session(
            sessionId = sessionId,
            sourceDir = sourceDir.absolutePath,
            outputDir = outputDir.absolutePath,
            startTime = Instant.now().toEpochMilli(),
            lastUpdated = Instant.now().toEpochMilli(),
            status = SessionStatus.RUNNING,
            processedMethods = 0,
            totalMethods = 0,
            currentIteration = 0
        )

        saveSession(session)
        logger.info("Created new session: $sessionId")
        return session
    }

    /**
     * 기존 세션 로드 (이어하기)
     */
    fun loadSession(): Session? {
        if (!sessionFile.exists()) {
            return null
        }

        return try {
            val json = sessionFile.readText()
            gson.fromJson(json, Session::class.java)
        } catch (e: Exception) {
            logger.error("Failed to load session: ${e.message}")
            null
        }
    }

    /**
     * 세션 저장
     */
    fun saveSession(session: Session) {
        try {
            val json = gson.toJson(session)
            sessionFile.writeText(json)
        } catch (e: Exception) {
            logger.error("Failed to save session: ${e.message}")
        }
    }

    /**
     * 세션 업데이트
     */
    fun updateSession(
        processedMethods: Int,
        totalMethods: Int,
        currentIteration: Int
    ) {
        val session = loadSession() ?: return
        val updated = session.copy(
            lastUpdated = Instant.now().toEpochMilli(),
            processedMethods = processedMethods,
            totalMethods = totalMethods,
            currentIteration = currentIteration
        )
        saveSession(updated)
    }

    /**
     * 세션 완료 표시
     */
    fun completeSession(success: Boolean) {
        val session = loadSession() ?: return
        val updated = session.copy(
            lastUpdated = Instant.now().toEpochMilli(),
            status = if (success) SessionStatus.COMPLETED else SessionStatus.FAILED
        )
        saveSession(updated)
    }

    /**
     * 세션 ID 생성 (소스 경로 + 타임스탬프 해시)
     */
    private fun generateSessionId(sourceDir: File): String {
        val input = "${sourceDir.absolutePath}:${Instant.now().toEpochMilli()}"
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * 세션 파일 정리
     */
    private fun cleanSessionFiles(session: Session) {
        val filesToDelete = listOf(
            "status.json",
            "mappings.json",
            "rename_history.md",
            "stats.txt"
        )

        filesToDelete.forEach { filename ->
            val file = File(outputDir, filename)
            if (file.exists() && file.delete()) {
                logger.info("  Deleted: $filename")
            }
        }
    }

    /**
     * 세션 유효성 검증
     */
    fun validateSession(sessionId: String): Boolean {
        val session = loadSession() ?: return false
        return session.sessionId == sessionId
    }

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(SessionManager::class.java)
    }
}

data class Session(
    val sessionId: String,          // 세션 고유 ID (16자 해시)
    val sourceDir: String,          // 소스 디렉토리
    val outputDir: String,          // 출력 디렉토리
    val startTime: Long,            // 시작 시간 (epoch millis)
    val lastUpdated: Long,          // 마지막 업데이트 시간
    val status: SessionStatus,      // 세션 상태
    val processedMethods: Int,      // 처리된 메소드 수
    val totalMethods: Int,          // 전체 메소드 수
    val currentIteration: Int       // 현재 이터레이션
)

enum class SessionStatus {
    RUNNING,    // 진행 중
    PAUSED,     // 일시정지
    COMPLETED,  // 완료
    FAILED      // 실패
}
