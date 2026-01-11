package com.whatap.apk2project.deobfuscator.client

/**
 * 로컬 변수 리네임 결과
 */
data class VariableRename(
    val originalName: String,
    val suggestedName: String,
    val description: String = ""
)

/**
 * AI 분석 결과
 */
data class MethodAnalysisResult(
    val methodId: String,
    val suggestedName: String,
    val description: String,
    val reasoning: String = "",
    val returnDescription: String? = null,
    val parameters: List<ParameterInfo> = emptyList(),
    val localVariables: Map<String, VariableRename> = emptyMap(),
    val usedFields: List<String> = emptyList()
)

/**
 * 파라미터 정보
 */
data class ParameterInfo(
    val name: String,
    val description: String
)

/**
 * 필드 분석 결과
 */
data class FieldAnalysisResult(
    val fieldId: String,
    val suggestedName: String,
    val description: String,
    val reasoning: String = ""
)

/**
 * 클래스 분석 결과
 */
data class ClassAnalysisResult(
    val originalClassName: String,
    val suggestedName: String,
    val description: String,
    val reasoning: String = "",
    val confidence: Float = 0.0f
)

/**
 * 패키지 분석 결과
 */
data class PackageAnalysisResult(
    val originalPackageName: String,
    val suggestedPackageName: String,
    val description: String,
    val reasoning: String = "",
    val confidence: Float = 0.0f
)
