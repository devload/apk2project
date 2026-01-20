package com.whatap.apk2project.deobfuscator.renamer

import com.whatap.apk2project.deobfuscator.client.MethodAnalysisResult
import com.whatap.apk2project.deobfuscator.model.MethodNode
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 인덱스 기반 파일 리네이머
 *
 * 메서드 인덱스를 사용해서 파일을 직접 수정 (AST 직렬화 없이)
 *
 * 장점:
 * - 큰 파일에서도 빠름 (20MB 파일 < 1초)
 * - 메모리 효율적 (AST 전체를 메모리에 유지하지 않음)
 */
class IndexedFileRenamer {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val indexer = MethodIndexer()

    // 파일별로 변경사항을 모아두기 (한 파일에 여러 메서드 rename)
    private val pendingChanges = ConcurrentHashMap<String, FileChanges>()

    /**
     * 파일 변경사항을 모아둠
     */
    data class FileChanges(
        val file: File,
        val index: MethodIndex,
        val renames: MutableMap<String, RenameInfo> = mutableMapOf()  // oldName -> RenameInfo
    )

    data class RenameInfo(
        val methodName: String,
        val newName: String,
        val localVars: Map<String, String>  // oldName -> newName
    )

    /**
     * 메서드 리네임 요청을 큐에 추가 (즉시 적용하지 않음)
     */
    fun queueRename(
        file: File,
        methodNode: MethodNode,
        analysis: MethodAnalysisResult
    ) {
        val indexDir = File(file.parentFile, ".apk2project/index")
        val indexFile = File(indexDir, "${file.name}.idx")

        val index = indexer.getOrCreateIndex(file, indexFile)
        val changes = pendingChanges.computeIfAbsent(file.absolutePath) {
            FileChanges(file, index)
        }

        changes.renames[methodNode.methodName] = RenameInfo(
            methodName = methodNode.methodName,
            newName = analysis.suggestedName,
            localVars = analysis.localVariables.mapValues { it.value.suggestedName }
        )

        logger.debug("Queued rename: ${methodNode.methodName} -> ${analysis.suggestedName} (${changes.renames.size} pending for ${file.name})")
    }

    /**
     * 파일별로 모아둔 변경사항을 한 번에 적용
     */
    fun applyRenames(file: File): RenameResult {
        val changes = pendingChanges.remove(file.absolutePath)
            ?: return RenameResult.Failure("No pending renames for ${file.name}")

        if (changes.renames.isEmpty()) {
            return RenameResult.Failure("No renames to apply")
        }

        logger.info("Applying ${changes.renames.size} renames to ${file.name}...")

        return try {
            val startTime = System.currentTimeMillis()

            // 파일 읽기
            val lines = file.readLines()
            val modifiedLines = lines.toMutableList()

            var renamedMethods = 0
            var renamedVariables = 0

            // 각 메서드별로 리네임 적용
            changes.renames.forEach { (oldMethodName, renameInfo) ->
                val methodInfo = changes.index.methods[oldMethodName]

                if (methodInfo != null) {
                    // 메서드 선언부 리네임 (시그니처)
                    val signatureLine = methodInfo.startLine - 1  // 0-indexed
                    if (signatureLine < modifiedLines.size) {
                        val originalLine = modifiedLines[signatureLine]

                        // 메서드 이름 변경
                        val newSignature = originalLine.replace(
                            "\\b${Regex.escape(oldMethodName)}\\b".toRegex(),
                            renameInfo.newName
                        )

                        if (newSignature != originalLine) {
                            modifiedLines[signatureLine] = newSignature
                            renamedMethods++
                        }

                        // 로컬 변수 변경 (메서드 본문 내)
                        renameInfo.localVars.forEach { (oldVarName, newVarName) ->
                            // 메서드 본문 범위 내에서만 변경
                            for (lineIdx in methodInfo.startLine until methodInfo.endLine) {
                                if (lineIdx < modifiedLines.size) {
                                    val line = modifiedLines[lineIdx]

                                    // 변수명 변경 (단어 경계 확인)
                                    val newLine = line.replace(
                                        "\\b${Regex.escape(oldVarName)}\\b".toRegex(),
                                        newVarName
                                    )

                                    if (newLine != line) {
                                        modifiedLines[lineIdx] = newLine
                                        renamedVariables++
                                    }
                                }
                            }
                        }
                    }
                } else {
                    logger.warn("Method not found in index: $oldMethodName")
                }
            }

            // 파일 저장 (한 번만!)
            file.writeText(modifiedLines.joinToString("\n"))
            val elapsed = System.currentTimeMillis() - startTime

            logger.info("✓ Renamed $renamedMethods methods and $renamedVariables variables in ${file.name} (${elapsed}ms)")

            // 첫 번째 리네임을 기준으로 결과 반환
            val firstRename = changes.renames.entries.first()
            RenameResult.Success(
                entry = RenameEntry(
                    timestamp = java.time.LocalDateTime.now(),
                    file = file,
                    type = RenameType.METHOD,
                    originalName = firstRename.key,
                    newName = firstRename.value.newName,
                    description = "Batch rename (${changes.renames.size} methods)",
                    className = "Unknown"
                ),
                actualVariableRenames = mapOf()  // TODO: Track actual variable renames
            )

        } catch (e: Exception) {
            logger.error("Failed to apply renames to ${file.name}: ${e.message}", e)
            RenameResult.Failure("Apply failed: ${e.message}")
        }
    }

    /**
     * 모든 대기 중인 변경사항을 파일별로 적용
     */
    fun applyAllPendingRenames(): Map<String, RenameResult> {
        val results = mutableMapOf<String, RenameResult>()

        pendingChanges.keys.toList().forEach { filePath ->
            val file = File(filePath)
            if (file.exists()) {
                results[filePath] = applyRenames(file)
            }
        }

        return results
    }

    /**
     * 특정 파일에 대기 중인 변경사항 수
     */
    fun getPendingCount(file: File): Int {
        return pendingChanges[file.absolutePath]?.renames?.size ?: 0
    }

    /**
     * 총 대기 중인 변경사항 수
     */
    fun getTotalPendingCount(): Int {
        return pendingChanges.values.sumOf { it.renames.size }
    }
}
