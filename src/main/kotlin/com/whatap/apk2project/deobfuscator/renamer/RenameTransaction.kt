package com.whatap.apk2project.deobfuscator.renamer

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 리네이밍 트랜잭션 관리자
 *
 * 여러 파일에 걸친 리네이밍 작업을 원자적으로 수행하기 위한 트랜잭션 메커니즘
 * 실패 시 모든 변경사항을 롤백하여 일관성 유지
 */
class RenameTransaction(
    private val sourceDir: File,
    private val tempDir: File = createTempDirectory()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // 백업된 파일들 (원본 경로 -> 백업 경로)
    private val backedUpFiles = ConcurrentHashMap<String, File>()

    // 수정된 파일들 (원본 경로 -> 새 내용)
    private val modifiedFiles = ConcurrentHashMap<String, String>()

    // 트랜잭션 상태
    @Volatile
    private var isCommitted = false

    @Volatile
    private var isRolledBack = false

    companion object {
        private fun createTempDirectory(): File {
            val tempDir = Files.createTempDirectory("rename_transaction_").toFile()
            tempDir.deleteOnExit()
            return tempDir
        }
    }

    /**
     * 파일 수정 등록
     *
     * 실제 파일을 수정하지 않고 변경 내용만 등록
     * commit() 호출 시 일괄 적용
     */
    fun registerModification(file: File, newContent: String): Boolean {
        check(!isCommitted && !isRolledBack) { "Transaction already finished" }

        return try {
            // 백업이 없으면 백업 생성
            if (!backedUpFiles.containsKey(file.absolutePath)) {
                val backupFile = File(tempDir, "${System.nanoTime()}_${file.name}")
                file.copyTo(backupFile, overwrite = true)
                backedUpFiles[file.absolutePath] = backupFile
                logger.debug("Backed up: ${file.name} -> ${backupFile.name}")
            }

            // 수정 내용 등록
            modifiedFiles[file.absolutePath] = newContent
            true
        } catch (e: Exception) {
            logger.error("Failed to register modification for ${file.name}: ${e.message}")
            false
        }
    }

    /**
     * 파일 내용 읽기 (수정된 내용이 있으면 그것을 반환)
     */
    fun readContent(file: File): String {
        return modifiedFiles[file.absolutePath] ?: file.readText()
    }

    /**
     * 트랜잭션 커밋
     *
     * 등록된 모든 수정사항을 원본 파일에 적용
     * 실패 시 자동 롤백
     */
    fun commit(): TransactionResult {
        check(!isCommitted && !isRolledBack) { "Transaction already finished" }

        logger.info("Committing transaction: ${modifiedFiles.size} files to modify")

        val failedFiles = mutableListOf<String>()

        try {
            // 모든 수정사항 적용
            modifiedFiles.forEach { (path, content) ->
                try {
                    val file = File(path)
                    file.writeText(content)
                    logger.debug("Applied changes to: ${file.name}")
                } catch (e: Exception) {
                    logger.error("Failed to apply changes to $path: ${e.message}")
                    failedFiles.add(path)
                    throw e  // 하나라도 실패하면 롤백
                }
            }

            isCommitted = true
            cleanupBackups()

            logger.info("Transaction committed successfully: ${modifiedFiles.size} files modified")
            return TransactionResult(
                success = true,
                modifiedFiles = modifiedFiles.keys.toList()
            )
        } catch (e: Exception) {
            // 롤백
            logger.error("Commit failed, rolling back: ${e.message}")
            rollback()
            return TransactionResult(
                success = false,
                modifiedFiles = emptyList(),
                failedFiles = failedFiles,
                error = e.message
            )
        }
    }

    /**
     * 트랜잭션 롤백
     *
     * 모든 변경사항을 취소하고 원본 상태로 복원
     */
    fun rollback(): Boolean {
        if (isCommitted) {
            logger.warn("Cannot rollback: transaction already committed")
            return false
        }

        if (isRolledBack) {
            logger.warn("Transaction already rolled back")
            return true
        }

        logger.info("Rolling back transaction: ${backedUpFiles.size} files to restore")

        var success = true
        backedUpFiles.forEach { (originalPath, backupFile) ->
            try {
                val originalFile = File(originalPath)
                Files.copy(
                    backupFile.toPath(),
                    originalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                logger.debug("Restored: ${originalFile.name}")
            } catch (e: Exception) {
                logger.error("Failed to restore $originalPath: ${e.message}")
                success = false
            }
        }

        isRolledBack = true
        cleanupBackups()

        if (success) {
            logger.info("Transaction rolled back successfully")
        } else {
            logger.error("Rollback completed with errors")
        }

        return success
    }

    /**
     * 백업 파일 정리
     */
    private fun cleanupBackups() {
        try {
            tempDir.deleteRecursively()
        } catch (e: Exception) {
            logger.debug("Failed to cleanup temp directory: ${e.message}")
        }
    }

    /**
     * 현재 등록된 수정 파일 수
     */
    val pendingModifications: Int
        get() = modifiedFiles.size

    /**
     * 트랜잭션 상태
     */
    val status: TransactionStatus
        get() = when {
            isCommitted -> TransactionStatus.COMMITTED
            isRolledBack -> TransactionStatus.ROLLED_BACK
            else -> TransactionStatus.ACTIVE
        }
}

/**
 * 트랜잭션 결과
 */
data class TransactionResult(
    val success: Boolean,
    val modifiedFiles: List<String>,
    val failedFiles: List<String> = emptyList(),
    val error: String? = null
)

/**
 * 트랜잭션 상태
 */
enum class TransactionStatus {
    ACTIVE,
    COMMITTED,
    ROLLED_BACK
}

/**
 * 트랜잭션 컨텍스트에서 작업 실행
 *
 * 사용 예:
 * ```
 * val result = withRenameTransaction(sourceDir) { transaction ->
 *     transaction.registerModification(file1, newContent1)
 *     transaction.registerModification(file2, newContent2)
 *     // commit은 블록 종료 시 자동 호출
 * }
 * ```
 */
inline fun <R> withRenameTransaction(
    sourceDir: File,
    block: (RenameTransaction) -> R
): TransactionResult {
    val transaction = RenameTransaction(sourceDir)
    return try {
        block(transaction)
        transaction.commit()
    } catch (e: Exception) {
        transaction.rollback()
        TransactionResult(
            success = false,
            modifiedFiles = emptyList(),
            error = e.message
        )
    }
}
