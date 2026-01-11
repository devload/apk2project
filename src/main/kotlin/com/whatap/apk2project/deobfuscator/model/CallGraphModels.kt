package com.whatap.apk2project.deobfuscator.model

import java.io.File

/**
 * 메소드 노드 - 콜 그래프의 정점
 */
data class MethodNode(
    val className: String,           // 클래스명 (FQN)
    val methodName: String,          // 메소드명
    val signature: String,           // 파라미터 시그니처
    val file: File,                  // 소스 파일
    val startLine: Int,              // 시작 라인
    val endLine: Int,                // 끝 라인
    val isObfuscated: Boolean        // 난독화 여부 (a, b, c 같은 이름)
) {
    val id: String get() = "$className#$methodName$signature"

    fun isLeaf(outgoingCalls: Set<MethodNode>): Boolean {
        // 난독화된 메소드를 호출하지 않으면 리프
        return outgoingCalls.none { it.isObfuscated }
    }
}

/**
 * 클래스 노드 - 클래스 정보
 */
data class ClassNode(
    val packageName: String,
    val className: String,
    val file: File,
    val methods: MutableList<MethodNode> = mutableListOf(),
    val fields: MutableList<FieldNode> = mutableListOf(),
    val imports: MutableSet<String> = mutableSetOf(),
    val isObfuscated: Boolean
) {
    val fqn: String get() = if (packageName.isNotEmpty()) "$packageName.$className" else className

    // 메소드 분석 완료율
    fun getAnalyzedRatio(): Double {
        val analyzed = methods.count { !it.isObfuscated }
        return if (methods.isEmpty()) 0.0 else analyzed.toDouble() / methods.size
    }
}

/**
 * 필드 노드
 */
data class FieldNode(
    val name: String,
    val type: String,
    val className: String,
    val file: File,
    val lineNumber: Int = 0,
    val isObfuscated: Boolean
) {
    val id: String get() = "$className#$name"
}

/**
 * 로컬 변수 노드
 */
data class LocalVariableNode(
    val name: String,
    val type: String,
    val methodId: String,
    val lineNumber: Int = 0,
    val isObfuscated: Boolean
) {
    val id: String get() = "$methodId#$name"
}

/**
 * 리네임 결과
 */
data class RenameResult(
    val originalName: String,
    val newName: String,
    val description: String,
    val confidence: Double = 0.0
)

/**
 * 메소드 분석 결과 (Claude로부터 받은 것)
 */
data class MethodAnalysis(
    val methodNode: MethodNode,
    val suggestedName: String,
    val description: String,
    val reasoning: String = "",
    val returnType: String? = null,
    val parameterNames: List<String> = emptyList(),
    val localVariableRenames: Map<String, String> = emptyMap()  // 원본명 → 제안명
)

/**
 * 필드 분석 결과
 */
data class FieldAnalysis(
    val fieldNode: FieldNode,
    val suggestedName: String,
    val description: String,
    val reasoning: String = ""
)

/**
 * 클래스 분석 결과
 */
data class ClassAnalysis(
    val classNode: ClassNode,
    val suggestedClassName: String,
    val suggestedPackageName: String,
    val description: String,
    val methodAnalyses: List<MethodAnalysis>,
    val fieldAnalyses: List<FieldAnalysis> = emptyList()
)

/**
 * 난독화 패턴 감지
 */
object ObfuscationDetector {
    // 단일 문자 또는 짧은 무의미한 이름
    private val OBFUSCATED_PATTERN = Regex("^[a-zA-Z]$|^[a-zA-Z][0-9]+$|^C[0-9]{4,}$|^m[0-9]{4,}$|^f[0-9]{3,}$")

    // 알려진 표준 메소드 (리프로 취급)
    private val KNOWN_METHODS = setOf(
        "toString", "hashCode", "equals", "clone", "finalize",
        "wait", "notify", "notifyAll", "getClass",
        "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
        "onClick", "onTouch", "onLongClick"
    )

    // 알려진 패키지 (난독화 아님)
    private val KNOWN_PACKAGES = setOf(
        "java.", "javax.", "android.", "androidx.", "kotlin.",
        "com.google.", "org.json.", "okhttp3.", "retrofit2.",
        "com.squareup.", "io.reactivex.", "rx.", "org.reactivestreams.",
        "com.facebook.", "com.crashlytics.", "com.firebase.",
        "org.apache.", "org.slf4j.", "org.junit.",
        "io.netty.", "com.fasterxml.", "org.greenrobot.",
        "com.bumptech.", "com.github.", "org.bouncycastle."
    )

    fun isObfuscatedName(name: String): Boolean {
        if (name in KNOWN_METHODS) return false
        return OBFUSCATED_PATTERN.matches(name)
    }

    fun isObfuscatedPackage(packageName: String): Boolean {
        if (KNOWN_PACKAGES.any { packageName.startsWith(it) }) return false
        // a.b.c 같은 단일 문자 패키지
        return packageName.split(".").any { it.length == 1 }
    }

    fun isKnownPackage(packageName: String): Boolean {
        return KNOWN_PACKAGES.any { packageName.startsWith(it) }
    }
}
