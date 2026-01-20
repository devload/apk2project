package com.whatap.apk2project.deobfuscator.renamer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 메서드 인덱스 구조
 *
 * 파일을 한 번 파싱해서 메서드 정보를 저장하고,
 * 나중에 이 인덱스를 사용해서 빠르게 리네임 수행
 */
@Serializable
data class MethodIndex(
    val filePath: String,
    val lastModified: Long,
    val methods: Map<String, MethodInfo>
) {
    companion object {
        private val json = Json { prettyPrint = true }

        fun load(file: File): MethodIndex? {
            return try {
                file.readText().let { json.decodeFromString(it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(this))
    }

    fun isOutOfDate(sourceFile: File): Boolean {
        return sourceFile.lastModified() > lastModified
    }
}

/**
 * 개별 메서드 정보
 */
@Serializable
data class MethodInfo(
    val methodName: String,
    val className: String,
    val startLine: Int,
    val endLine: Int,
    val returnType: String,
    val parameters: List<ParameterInfo>,
    val localVariables: List<VariableInfo>
)

@Serializable
data class ParameterInfo(
    val name: String,
    val type: String
)

@Serializable
data class VariableInfo(
    val name: String,
    val type: String,
    val line: Int
)
