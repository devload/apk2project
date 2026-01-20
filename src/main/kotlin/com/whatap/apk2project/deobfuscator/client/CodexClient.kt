package com.whatap.apk2project.deobfuscator.client

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.whatap.apk2project.deobfuscator.model.MethodNode
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Codex CLI를 사용하는 클라이언트
 * Claude보다 빠른 응답 속도
 */
class CodexClient(
    private val codexPath: String = "codex",
    private val workingDir: File? = null,
    private val timeout: Long = 60_000  // 1분 (Codex는 빠름)
) : AiClient {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val gson = Gson()

    override fun analyzeMethod(
        method: MethodNode,
        sourceCode: String,
        iteration: Int,
        classSourceCode: String
    ): MethodAnalysisResult? {
        val prompt = buildMethodPrompt(method, sourceCode)
        val response = executeCodexPrompt(prompt)
        return parseMethodAnalysis(response, method)
    }

    override fun analyzeMethods(methods: List<Pair<MethodNode, String>>): List<MethodAnalysisResult> {
        if (methods.isEmpty()) return emptyList()

        val prompt = buildBatchMethodPrompt(methods)
        val response = executeCodexPrompt(prompt)
        return parseBatchMethodAnalysis(response, methods.map { it.first })
    }

    /**
     * 클래스명 추론 (미구현 - 반환 null)
     */
    override fun analyzeClass(className: String, methodNames: List<String>): ClassAnalysisResult? {
        logger.warn("analyzeClass not implemented in CodexClient")
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
        logger.warn("analyzePackage not implemented in CodexClient")
        return null
    }

    override fun isAvailable(): Boolean {
        return try {
            val process = ProcessBuilder(codexPath, "--version")
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(5, TimeUnit.SECONDS)
            completed && process.exitValue() == 0
        } catch (e: Exception) {
            logger.warn("Codex CLI not available: ${e.message}")
            false
        }
    }

    private fun buildMethodPrompt(method: MethodNode, sourceCode: String): String {
        return """
Analyze this Java method and respond with ONLY a JSON object (no markdown, no code blocks):

Class: ${method.className}
Method: ${method.methodName}${method.signature}

```java
$sourceCode
```

Response format (pure JSON only):
{"suggestedName":"meaningfulMethodName","description":"what this method does","returnDescription":"return value description","params":[{"name":"paramName","description":"description"}]}
""".trim()
    }

    private fun buildBatchMethodPrompt(methods: List<Pair<MethodNode, String>>): String {
        val methodsJson = methods.mapIndexed { index, (method, source) ->
            """
=== Method $index ===
Class: ${method.className}
Method: ${method.methodName}${method.signature}
```java
$source
```
""".trim()
        }.joinToString("\n\n")

        return """
Analyze these Java methods and respond with ONLY a JSON array (no markdown, no code blocks):

$methodsJson

Response format (pure JSON array only):
[{"index":0,"suggestedName":"methodName","description":"description"},{"index":1,"suggestedName":"methodName","description":"description"}]
""".trim()
    }

    /**
     * Codex CLI exec 명령으로 프롬프트 실행
     * 임시 파일을 통해 긴 프롬프트 전달
     */
    private fun executeCodexPrompt(prompt: String): String {
        logger.debug("Executing codex exec with prompt length: ${prompt.length}")

        // 임시 파일에 프롬프트 저장
        val tempFile = File.createTempFile("codex_prompt_", ".txt")
        try {
            tempFile.writeText(prompt)

            // Windows에서 cmd /c를 사용하여 파일 내용을 인자로 전달
            val command = listOf(
                "cmd", "/c",
                "$codexPath exec \"${tempFile.absolutePath.replace("\\", "/")}\" --json"
            )

            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(true)

            workingDir?.let { processBuilder.directory(it) }

            val process = processBuilder.start()

            val outputFuture = java.util.concurrent.Executors.newSingleThreadExecutor().submit<String> {
                process.inputStream.bufferedReader().readText()
            }

            val completed = process.waitFor(timeout, TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroyForcibly()
                logger.warn("Codex process timed out after ${timeout}ms")
                return ""
            }

            val rawOutput = try {
                outputFuture.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                ""
            }

            logger.debug("Codex raw output: ${rawOutput.take(300)}")

            // JSONL에서 agent_message 추출
            return extractAgentMessage(rawOutput)

        } catch (e: Exception) {
            logger.error("Failed to execute codex: ${e.message}", e)
            return ""
        } finally {
            tempFile.delete()
        }
    }

    /**
     * JSONL 출력에서 agent_message 추출
     */
    private fun extractAgentMessage(jsonlOutput: String): String {
        val messages = mutableListOf<String>()

        jsonlOutput.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            try {
                val json = JsonParser.parseString(line).asJsonObject
                if (json.get("type")?.asString == "item.completed") {
                    val item = json.getAsJsonObject("item")
                    if (item?.get("type")?.asString == "agent_message") {
                        item.get("text")?.asString?.let { messages.add(it) }
                    }
                }
            } catch (e: Exception) {
                // Skip invalid lines
            }
        }

        return messages.lastOrNull() ?: ""
    }

    private fun parseMethodAnalysis(response: String, method: MethodNode): MethodAnalysisResult? {
        return try {
            val jsonStr = extractJson(response) ?: return null
            val json = JsonParser.parseString(jsonStr).asJsonObject

            MethodAnalysisResult(
                methodId = method.id,
                suggestedName = json.get("suggestedName")?.asString ?: method.methodName,
                description = json.get("description")?.asString ?: "",
                returnDescription = json.get("returnDescription")?.asString,
                parameters = parseParameters(json)
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse method analysis: ${e.message}")
            null
        }
    }

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
                    returnDescription = json.get("returnDescription")?.asString,
                    parameters = parseParameters(json)
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse batch analysis: ${e.message}")
            emptyList()
        }
    }

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

    private fun extractJson(text: String): String? {
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val codeBlockMatch = codeBlockPattern.find(text)
        val searchText = codeBlockMatch?.groupValues?.get(1)?.trim() ?: text

        val start = searchText.indexOf("{")
        if (start == -1) return null

        var braceCount = 0
        var end = start

        for (i in start until searchText.length) {
            when (searchText[i]) {
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

        return if (end > start) searchText.substring(start, end) else null
    }

    private fun extractJsonArray(text: String): String? {
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val codeBlockMatch = codeBlockPattern.find(text)
        val searchText = codeBlockMatch?.groupValues?.get(1)?.trim() ?: text

        val start = searchText.indexOf("[")
        if (start == -1) return null

        var bracketCount = 0
        var end = start

        for (i in start until searchText.length) {
            when (searchText[i]) {
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

        return if (end > start) searchText.substring(start, end) else null
    }
}
