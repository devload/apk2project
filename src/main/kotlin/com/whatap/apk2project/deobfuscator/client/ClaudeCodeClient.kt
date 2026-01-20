package com.whatap.apk2project.deobfuscator.client

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.whatap.apk2project.deobfuscator.model.MethodNode
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Claude Code CLI를 -p 옵션으로 직접 호출하는 클라이언트
 *
 * tmux 방식 대신 직접 프로세스 실행으로 동기적 응답 처리
 */
class ClaudeCodeClient(
    private val claudePath: String = "claude",
    private val workingDir: File? = null,
    private val timeout: Long = 120_000  // 2분
) : AiClient {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val gson = Gson()

    /**
     * 메소드 분석 요청
     */
    override fun analyzeMethod(
        method: MethodNode,
        sourceCode: String,
        iteration: Int,
        classSourceCode: String
    ): MethodAnalysisResult? {
        val prompt = buildMethodPrompt(method, sourceCode)
        val response = executeClaudePrompt(prompt)
        return parseMethodAnalysis(response, method)
    }

    /**
     * 여러 메소드 일괄 분석 요청
     */
    override fun analyzeMethods(methods: List<Pair<MethodNode, String>>): List<MethodAnalysisResult> {
        if (methods.isEmpty()) return emptyList()

        val prompt = buildBatchMethodPrompt(methods)
        val response = executeClaudePrompt(prompt)
        return parseBatchMethodAnalysis(response, methods.map { it.first })
    }

    /**
     * 클래스명 추론 (미구현 - 반환 null)
     */
    override fun analyzeClass(className: String, methodNames: List<String>): ClassAnalysisResult? {
        logger.warn("analyzeClass not implemented in ClaudeCodeClient")
        return null
    }

    /**
     * 패키지명 추론 (미구현 - 반환 null)
     */
    override fun analyzePackage(
        packageName: String,
        classNames: List<String>,
        description: String
    ): PackageAnalysisResult? {
        logger.warn("analyzePackage not implemented in ClaudeCodeClient")
        return null
    }

    /**
     * 메소드 분석 프롬프트 생성
     */
    private fun buildMethodPrompt(method: MethodNode, sourceCode: String): String {
        return """
다음 Java 메소드를 분석하고 JSON으로 응답해줘. 코드 블록 없이 순수 JSON만 출력해.

클래스: ${method.className}
메소드: ${method.methodName}${method.signature}

```java
$sourceCode
```

응답 형식:
{"suggestedName":"의미있는메소드명","description":"이 메소드의 역할 설명","reasoning":"이 이름을 선택한 이유","returnDescription":"반환값 설명","params":[{"name":"파라미터명","description":"설명"}]}
""".trim()
    }

    /**
     * 일괄 메소드 분석 프롬프트
     */
    private fun buildBatchMethodPrompt(methods: List<Pair<MethodNode, String>>): String {
        val methodsJson = methods.mapIndexed { index, (method, source) ->
            """
=== 메소드 $index ===
클래스: ${method.className}
메소드: ${method.methodName}${method.signature}
```java
$source
```
""".trim()
        }.joinToString("\n\n")

        return """
다음 Java 메소드들을 분석하고 JSON 배열로 응답해줘. 코드 블록 없이 순수 JSON만 출력해.

$methodsJson

응답 형식 (JSON 배열):
[{"index":0,"suggestedName":"메소드명","description":"설명"},{"index":1,"suggestedName":"메소드명","description":"설명"}]
""".trim()
    }

    /**
     * Claude Code CLI를 실행하여 프롬프트 처리
     * stdin으로 프롬프트를 전달하여 긴 프롬프트도 처리 가능
     */
    private fun executeClaudePrompt(prompt: String): String {
        logger.debug("Executing claude with prompt length: ${prompt.length}")

        // stdin으로 프롬프트 전달 (--print 플래그로 결과만 출력)
        val command = listOf(
            claudePath,
            "--print"  // 결과만 stdout으로 출력
        )

        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(true)

        workingDir?.let { processBuilder.directory(it) }

        return try {
            val process = processBuilder.start()

            // stdin으로 프롬프트 전송
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(prompt)
            }

            // 비동기로 출력 읽기
            val outputFuture = java.util.concurrent.Executors.newSingleThreadExecutor().submit<String> {
                process.inputStream.bufferedReader().readText()
            }

            // 타임아웃과 함께 프로세스 완료 대기
            val completed = process.waitFor(timeout, TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroyForcibly()
                logger.warn("Claude process timed out after ${timeout}ms")
                return ""
            }

            val response = try {
                outputFuture.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                ""
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                logger.warn("Claude exited with code $exitCode")
            }

            logger.debug("Claude response length: ${response.length}")
            response

        } catch (e: Exception) {
            logger.error("Failed to execute claude: ${e.message}", e)
            ""
        }
    }

    /**
     * 응답에서 메소드 분석 결과 파싱
     */
    private fun parseMethodAnalysis(response: String, method: MethodNode): MethodAnalysisResult? {
        return try {
            val jsonStr = extractJson(response) ?: return null
            val json = JsonParser.parseString(jsonStr).asJsonObject

            MethodAnalysisResult(
                methodId = method.id,
                suggestedName = json.get("suggestedName")?.asString ?: method.methodName,
                description = json.get("description")?.asString ?: "",
                reasoning = json.get("reasoning")?.asString ?: "",
                returnDescription = json.get("returnDescription")?.asString,
                parameters = parseParameters(json)
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse method analysis: ${e.message}")
            null
        }
    }

    /**
     * 일괄 분석 결과 파싱
     */
    private fun parseBatchMethodAnalysis(
        response: String,
        methods: List<MethodNode>
    ): List<MethodAnalysisResult> {
        return try {
            val jsonStr = extractJsonArray(response) ?: return emptyList()
            val jsonArray = JsonParser.parseString(jsonStr).asJsonArray

            jsonArray.mapIndexedNotNull { index, element ->
                val json = element.asJsonObject
                val methodIndex = json.get("index")?.asInt ?: index
                val method = methods.getOrNull(methodIndex) ?: return@mapIndexedNotNull null

                MethodAnalysisResult(
                    methodId = method.id,
                    suggestedName = json.get("suggestedName")?.asString ?: method.methodName,
                    description = json.get("description")?.asString ?: "",
                    reasoning = json.get("reasoning")?.asString ?: "",
                    returnDescription = json.get("returnDescription")?.asString,
                    parameters = parseParameters(json)
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse batch analysis: ${e.message}")
            emptyList()
        }
    }

    /**
     * 파라미터 정보 파싱
     */
    private fun parseParameters(json: JsonObject): List<ParameterInfo> {
        return try {
            json.getAsJsonArray("params")?.map { param ->
                val p = param.asJsonObject
                ParameterInfo(
                    name = p.get("name")?.asString ?: "",
                    description = p.get("description")?.asString ?: ""
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 응답에서 JSON 객체 추출
     * 코드 블록에서도 추출 가능
     */
    private fun extractJson(text: String): String? {
        // 먼저 코드 블록 내용 추출 시도
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val codeBlockMatch = codeBlockPattern.find(text)
        val searchText = codeBlockMatch?.groupValues?.get(1)?.trim() ?: text

        // JSON 객체 찾기
        val start = searchText.indexOf("{\"")
        if (start == -1) {
            // { 로 시작하는 것도 허용
            val altStart = searchText.indexOf("{")
            if (altStart == -1) return null
            return extractJsonFromPosition(searchText, altStart)
        }

        return extractJsonFromPosition(searchText, start)
    }

    private fun extractJsonFromPosition(text: String, start: Int): String? {
        var braceCount = 0
        var end = start

        for (i in start until text.length) {
            when (text[i]) {
                '{' -> braceCount++
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        end = i + 1
                        break
                    }
                }
            }
        }

        return if (end > start) text.substring(start, end) else null
    }

    /**
     * 응답에서 JSON 배열 추출
     * 코드 블록에서도 추출 가능
     */
    private fun extractJsonArray(text: String): String? {
        // 먼저 코드 블록 내용 추출 시도
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val codeBlockMatch = codeBlockPattern.find(text)
        val searchText = codeBlockMatch?.groupValues?.get(1)?.trim() ?: text

        val start = searchText.indexOf("[{")
        if (start == -1) {
            val altStart = searchText.indexOf("[")
            if (altStart == -1) return null
            return extractJsonArrayFromPosition(searchText, altStart)
        }

        return extractJsonArrayFromPosition(searchText, start)
    }

    private fun extractJsonArrayFromPosition(text: String, start: Int): String? {
        var bracketCount = 0
        var end = start

        for (i in start until text.length) {
            when (text[i]) {
                '[' -> bracketCount++
                ']' -> {
                    bracketCount--
                    if (bracketCount == 0) {
                        end = i + 1
                        break
                    }
                }
            }
        }

        return if (end > start) text.substring(start, end) else null
    }

    /**
     * Claude CLI 사용 가능 여부 확인
     */
    override fun isAvailable(): Boolean {
        return try {
            val process = ProcessBuilder(claudePath, "--version")
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(5, TimeUnit.SECONDS)
            completed && process.exitValue() == 0
        } catch (e: Exception) {
            logger.warn("Claude CLI not available: ${e.message}")
            false
        }
    }

    /**
     * 세션 활성화 여부 (호환성 - 항상 true)
     */
    fun isSessionActive(): Boolean = isAvailable()

    /**
     * 세션 시작 (호환성 - no-op)
     */
    fun startSession() {
        if (!isAvailable()) {
            logger.error("Claude CLI is not available. Make sure 'claude' is in PATH")
            throw RuntimeException("Claude CLI not available")
        }
        logger.info("Claude CLI is ready for use")
    }
}
