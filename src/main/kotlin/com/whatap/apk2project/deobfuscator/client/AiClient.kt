package com.whatap.apk2project.deobfuscator.client

import com.whatap.apk2project.deobfuscator.model.MethodNode
import com.whatap.apk2project.deobfuscator.monitor.ProgressMonitor

/**
 * AI 클라이언트 공통 인터페이스
 */
interface AiClient {
    /**
     * 단일 메소드 분석
     */
    fun analyzeMethod(method: MethodNode, sourceCode: String): MethodAnalysisResult?

    /**
     * 여러 메소드 일괄 분석
     */
    fun analyzeMethods(methods: List<Pair<MethodNode, String>>): List<MethodAnalysisResult>

    /**
     * 클래스명 추론
     */
    fun analyzeClass(className: String, methodNames: List<String>): ClassAnalysisResult?

    /**
     * 패키지명 추론
     *
     * @param packageName 원본 난독화된 패키지명 (예: "a.b.c")
     * @param classNames 이 패키지에 속한 클래스들의 이름 (예: ["PaymentService", "UserRepository"])
     * @param description 클래스들의 설명 요약
     * @return 추천 패키지명 및 설명, 실패시 null
     */
    fun analyzePackage(
        packageName: String,
        classNames: List<String>,
        description: String
    ): PackageAnalysisResult?

    /**
     * 클라이언트 사용 가능 여부
     */
    fun isAvailable(): Boolean
}

/**
 * AI 클라이언트 타입
 */
enum class AiClientType {
    CLAUDE,
    CODEX,
    OLLAMA  // 로컬 LLM (DeepSeek Coder 등)
}

/**
 * AI 클라이언트 팩토리
 */
object AiClientFactory {
    fun create(
        type: AiClientType,
        executablePath: String? = null,
        workingDir: java.io.File? = null,
        timeout: Long = 120_000,
        modelName: String? = null,
        monitor: ProgressMonitor? = null,
        ollamaBaseUrl: String = "http://localhost:11434"  // Ollama 서버 URL
    ): AiClient {
        return when (type) {
            AiClientType.CLAUDE -> ClaudeCodeClient(
                claudePath = executablePath ?: "claude",
                workingDir = workingDir,
                timeout = timeout
            )
            AiClientType.CODEX -> CodexClient(
                codexPath = executablePath ?: "codex",
                workingDir = workingDir,
                timeout = if (timeout > 60_000) 60_000 else timeout  // Codex는 더 빠름
            )
            AiClientType.OLLAMA -> OllamaClient(
                baseUrl = ollamaBaseUrl,  // 명시적으로 ollamaBaseUrl 사용
                modelName = modelName ?: "deepseek-coder:6.7b",
                timeout = timeout,
                monitor = monitor
            )
        }
    }
}
