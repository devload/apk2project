package com.whatap.apk2project.deobfuscator.client

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.whatap.apk2project.deobfuscator.monitor.ProgressMonitor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * 한글 번역 클라이언트 (EEVE-Korean)
 *
 * DeepSeek의 영어 분석 결과를 자연스러운 한글로 번역
 */
class TranslationClient(
    private val baseUrl: String = "http://localhost:11434",
    private val modelName: String = "qwen2.5:7b",
    private val timeout: Long = 1_200_000,  // 20분 (CPU 모드 고려)
    private val monitor: ProgressMonitor? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(timeout, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 영어 분석 결과를 한글로 번역 (retry 포함)
     */
    fun translate(result: MethodAnalysisResult): MethodAnalysisResult {
        // 번역할 텍스트
        val toTranslate = """
            Method Name: ${result.suggestedName}
            Description: ${result.description}
            Reasoning: ${result.reasoning}
        """.trimIndent()

        val prompt = buildTranslationPrompt(toTranslate)
        var lastResponse = ""
        var lastDuration = 0L

        repeat(2) { attempt ->
            val startTime = System.currentTimeMillis()
            val response = callOllama(prompt)
            val duration = System.currentTimeMillis() - startTime

            lastResponse = response
            lastDuration = duration

            val translated = parseTranslation(response)

            if (translated != null) {
                // 성공: LLM 요청 기록
                monitor?.addLlmRequest(
                    methodName = result.methodId,
                    requestType = "translation",
                    model = modelName,
                    promptPreview = prompt.take(200),
                    response = response.take(500),
                    durationMs = duration,
                    success = true
                )

                return result.copy(
                    description = translated.description,
                    reasoning = translated.reasoning
                )
            } else {
                logger.warn("Attempt ${attempt + 1}: Translation failed for ${result.methodId}, retrying...")
            }

            // 재시도 전 짧은 대기
            if (attempt < 1) {
                Thread.sleep(1000)
            }
        }

        // 모든 재시도 실패: 원본 영어 결과 사용
        logger.warn("Translation failed after 2 attempts, using original English for ${result.methodId}")
        monitor?.addLlmRequest(
            methodName = result.methodId,
            requestType = "translation",
            model = modelName,
            promptPreview = prompt.take(200),
            response = lastResponse.take(500),
            durationMs = lastDuration,
            success = false
        )

        return result
    }

    /**
     * 번역 프롬프트 생성
     */
    private fun buildTranslationPrompt(englishText: String): String {
        return """IMPORTANT: You must respond ONLY in Korean (한국어). Do NOT use Chinese (中文) at all.
절대로 중국어를 사용하지 마세요. 오직 한국어만 사용하세요.

다음 영어 텍스트를 자연스러운 한국어로 번역해주세요.
프로그래밍 용어는 가능한 한 원어를 유지하되, 설명은 반드시 한국어로만 작성하세요.

영어 텍스트:
$englishText

JSON 형식으로 응답해주세요:
{"description":"한글 설명","reasoning":"한글 추론"}

올바른 예시 (한국어만):
{"description":"두 숫자를 더하는 메서드","reasoning":"메서드 이름이 더하기 동작을 명확히 나타냅니다"}
{"description":"현재 상태를 반환하는 메서드","reasoning":"상태를 조회하는 목적이 분명합니다"}

잘못된 예시 (중국어 포함 - 절대 사용 금지):
{"description":"更具描述性的名称","reasoning":"会使代码更容易理解"} ❌ NO!"""
    }

    /**
     * Ollama API 호출
     */
    private fun callOllama(prompt: String): String {
        try {
            val requestBody = mapOf(
                "model" to modelName,
                "prompt" to prompt,
                "stream" to false,
                "options" to mapOf(
                    "temperature" to 0.2,  // 낮은 온도로 일관성 유지
                    "num_predict" to 300,  // 한글은 더 많은 토큰 필요
                    "top_p" to 0.9
                )
            )

            val json = gson.toJson(requestBody)
            val body = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/api/generate")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn("Ollama request failed: ${response.code}")
                    return ""
                }

                val responseBody = response.body?.string() ?: ""
                val jsonResponse = JsonParser.parseString(responseBody).asJsonObject
                return jsonResponse.get("response")?.asString ?: ""
            }
        } catch (e: Exception) {
            logger.error("Ollama API call failed: ${e.message}", e)
            return ""
        }
    }

    /**
     * 번역 결과 파싱
     */
    private fun parseTranslation(response: String): TranslatedText? {
        return try {
            val jsonStr = extractJson(response) ?: return null
            val json = JsonParser.parseString(jsonStr).asJsonObject

            TranslatedText(
                description = json.get("description")?.asString ?: "",
                reasoning = json.get("reasoning")?.asString ?: ""
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse translation: ${e.message}")
            null
        }
    }

    /**
     * JSON 추출
     */
    private fun extractJson(text: String): String? {
        val start = text.indexOf("{")
        if (start == -1) return null

        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * EEVE 모델 사용 가능 여부 확인
     */
    fun isAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false

                val body = response.body?.string() ?: return false
                val json = JsonParser.parseString(body).asJsonObject
                val models = json.getAsJsonArray("models")

                val hasModel = models.any { model ->
                    model.asJsonObject.get("name")?.asString?.contains("qwen") == true
                }

                if (!hasModel) {
                    logger.warn("Qwen model not found. Run: ollama pull $modelName")
                }

                hasModel
            }
        } catch (e: Exception) {
            logger.warn("EEVE model not available: ${e.message}")
            false
        }
    }
}

/**
 * 번역된 텍스트
 */
private data class TranslatedText(
    val description: String,
    val reasoning: String
)
