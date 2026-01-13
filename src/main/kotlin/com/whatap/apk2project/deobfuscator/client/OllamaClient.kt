package com.whatap.apk2project.deobfuscator.client

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.whatap.apk2project.deobfuscator.model.MethodNode
import com.whatap.apk2project.deobfuscator.monitor.ProgressMonitor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Ollama 로컬 LLM 클라이언트
 *
 * DeepSeek Coder, CodeLlama 등의 코드 특화 모델 사용
 * 완전 무료, 로컬 실행, 토큰 제한 없음
 */
class OllamaClient(
    private val baseUrl: String = "http://localhost:11434",
    private val modelName: String = "deepseek-coder:6.7b",
    private val timeout: Long = 1_200_000,  // 20분 (CPU 모드 고려)
    private val monitor: ProgressMonitor? = null,
    private val maxRetries: Int = 3,  // 최대 재시도 횟수
    private val retryDelayMs: Long = 1000  // 재시도 간 대기 시간
) : AiClient {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val gson = Gson()

    /**
     * 제네릭 재시도 함수
     *
     * @param targetName 분석 대상 이름 (메서드/클래스/패키지)
     * @param requestType 요청 타입 ("analysis", "class_analysis", "package_analysis")
     * @param block 실행할 블록 (prompt를 받아 response를 반환)
     * @return 성공 시 결과, 실패 시 null
     */
    private fun <T> executeWithRetry(
        targetName: String,
        requestType: String,
        block: (String) -> T?
    ): T? {
        repeat(maxRetries) { attempt ->
            try {
                val result = block("")

                // 성공하면 결과 반환
                if (result != null) {
                    return result
                }
            } catch (e: Exception) {
                logger.error("Attempt ${attempt + 1}: Exception during $requestType for $targetName: ${e.message}")
            }

            // 재시도 전 대기
            if (attempt < maxRetries - 1) {
                Thread.sleep(retryDelayMs)
            }
        }

        // 모든 재시도 실패
        logger.error("Failed to $requestType $targetName after $maxRetries attempts")
        return null
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(timeout, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 단일 메소드 분석 (retry 포함)
     */
    override fun analyzeMethod(
        method: MethodNode,
        sourceCode: String,
        iteration: Int = 1,
        classSourceCode: String = ""
    ): MethodAnalysisResult? {
        // ITERATE 1: 기본 프롬프트
        // RETRY (iteration >= 2): 클래스 컨텍스트 포함
        val prompt = if (iteration >= 2 && classSourceCode.isNotEmpty()) {
            buildEnhancedPromptWithClassContext(method, sourceCode, classSourceCode)
        } else {
            buildCompactPrompt(method, sourceCode)
        }

        return executeWithRetry(
            targetName = method.methodName,
            requestType = "analysis"
        ) { _ ->
            val startTime = System.currentTimeMillis()
            val response = callOllama(prompt)
            val duration = System.currentTimeMillis() - startTime

            val result = parseMethodAnalysis(response, method)

            // 성공 조건: result가 null이 아니고 suggestedName이 원본과 다름
            val success = result != null && result.suggestedName != method.methodName

            // Always log the request (both success and failure)
            monitor?.addLlmRequest(
                methodName = method.methodName,
                requestType = "analysis",
                model = modelName,
                promptPreview = prompt.take(200),
                response = response.take(500),
                durationMs = duration,
                success = success
            )

            if (success) {
                result
            } else {
                when {
                    result == null -> logger.warn("Attempt failed: Parse failed for ${method.methodName}, retrying...")
                    result.suggestedName == method.methodName -> logger.warn("Attempt failed: Method name unchanged (${method.methodName}), retrying...")
                }
                null
            }
        }
    }

    /**
     * 배치 분석 (Ollama는 한 번에 여러 개 처리)
     */
    override fun analyzeMethods(methods: List<Pair<MethodNode, String>>): List<MethodAnalysisResult> {
        if (methods.isEmpty()) return emptyList()

        // Ollama는 배치를 한 번에 처리하는 게 효율적
        val prompt = buildBatchPrompt(methods)
        val response = callOllama(prompt)
        return parseBatchAnalysis(response, methods.map { it.first })
    }

    /**
     * 클래스명 추론 (retry 포함)
     */
    override fun analyzeClass(className: String, methodNames: List<String>): ClassAnalysisResult? {
        val prompt = buildClassAnalysisPrompt(className, methodNames)

        return executeWithRetry(
            targetName = className,
            requestType = "class_analysis"
        ) { _ ->
            val startTime = System.currentTimeMillis()
            val response = callOllama(prompt)
            val duration = System.currentTimeMillis() - startTime

            val result = parseClassAnalysis(response, className)

            // 성공 조건: result가 null이 아니고 suggestedName이 원본과 다름
            if (result != null && result.suggestedName != className) {
                monitor?.addLlmRequest(
                    methodName = className,
                    requestType = "class_analysis",
                    model = modelName,
                    promptPreview = prompt.take(200),
                    response = response.take(500),
                    durationMs = duration,
                    success = true
                )
                result
            } else {
                logger.warn("Attempt failed: Class name unchanged or parse failed ($className), retrying...")
                null
            }
        }
    }

    /**
     * 패키지명 추론 (retry 포함)
     */
    override fun analyzePackage(
        packageName: String,
        classNames: List<String>,
        description: String
    ): PackageAnalysisResult? {
        val prompt = buildPackageAnalysisPrompt(packageName, classNames, description)

        return executeWithRetry(
            targetName = packageName,
            requestType = "package_analysis"
        ) { _ ->
            val startTime = System.currentTimeMillis()
            val response = callOllama(prompt)
            val duration = System.currentTimeMillis() - startTime

            val result = parsePackageAnalysis(response, packageName)

            // 성공 조건: result가 null이 아니고 suggestedPackageName이 원본과 다름
            if (result != null && result.suggestedPackageName != packageName) {
                monitor?.addLlmRequest(
                    methodName = packageName,
                    requestType = "package_analysis",
                    model = modelName,
                    promptPreview = prompt.take(200),
                    response = response.take(500),
                    durationMs = duration,
                    success = true
                )
                result
            } else {
                logger.warn("Attempt failed: Package name unchanged or parse failed ($packageName), retrying...")
                null
            }
        }
    }

    /**
     * 최적화된 프롬프트 (영어) - 로컬 변수 포함
     */
    @Suppress("UNUSED_PARAMETER")
    private fun buildCompactPrompt(method: MethodNode, sourceCode: String): String {
        return """Analyze this obfuscated Java method. Suggest meaningful names for the method and local variables.

```java
$sourceCode
```

Respond with JSON only:
{
  "name":"methodName",
  "desc":"what it does",
  "reasoning":"why this name",
  "vars":{"oldVarName":"newVarName"}
}

Rules:
- Analyze the CODE BEHAVIOR, not the method name itself
- Do NOT simply translate non-English method names to English
- If method name looks generic (like "meaningfulMethod", "deobfuscatedMethodName"), analyze the actual code logic instead
- ALWAYS suggest a descriptive name based on BEHAVIOR, never return the original obfuscated name
- For setter methods: use "setXxx()" pattern (e.g., "setListenerList", "setByteBuffer")
- For getter methods: use "getXxx()" pattern

IMPORTANT - vars rules:
- ONLY rename LOCAL VARIABLE DECLARATIONS (e.g., "int i", "String str", "ArrayList arrayList")
- Variables to rename: single-char names (i, j, k), numbered names (i2, str3), or generic names (obj, temp)
- DO NOT include: type names (ArrayList, String), field accesses (this.xxx), method calls
- If no variables need renaming, use empty object: "vars":{}
- Example: in "ArrayList list = new ArrayList()", the variable is "list", NOT "ArrayList"

Example: {"name":"calculateTotal","desc":"sums values","reasoning":"indicates calculation","vars":{"i":"counter","str":"result"}}"""
    }

    /**
     * Enhanced 프롬프트 (클래스 컨텍스트 포함) - ITERATE 2+에서 사용
     */
    @Suppress("UNUSED_PARAMETER")
    private fun buildEnhancedPromptWithClassContext(
        method: MethodNode,
        sourceCode: String,
        classSourceCode: String
    ): String {
        return """Analyze this obfuscated Java method with additional class context. The class source code is provided to help understand the field usage and method relationships.

Target Method:
```java
$sourceCode
```

Class Context (all fields and related methods):
```java
${classSourceCode.take(2000)}
```

Respond with JSON only:
{
  "name":"methodName",
  "desc":"what it does",
  "reasoning":"why this name",
  "vars":{"oldVarName":"newVarName"}
}

Rules:
- Use the CLASS CONTEXT to understand what fields like f17840 are used for
- Look at other methods to see how these fields are used
- ALWAYS suggest a descriptive name based on BEHAVIOR, never return the original obfuscated name
- For setter methods: use "setXxx()" pattern based on field purpose (e.g., "setByteBuffer" if f17840 is a byte buffer)
- For getter methods: use "getXxx()" pattern

Example: {"name":"setByteBuffer","desc":"sets the byte buffer field","reasoning":"f17840 is used as buffer in processBytes()","vars":{"list":"buffer"}}"""
    }

    /**
     * 배치 프롬프트
     */
    private fun buildBatchPrompt(methods: List<Pair<MethodNode, String>>): String {
        val methodsText = methods.mapIndexed { i, (m, code) ->
            "[$i] ${m.methodName}${m.signature}\n```\n${code.take(200)}\n```"
        }.joinToString("\n\n")

        return """Analyze these methods, return JSON array:
$methodsText

Return: [{"i":0,"name":"methodName","desc":"..."},{"i":1,"name":"...","desc":"..."}]"""
    }

    /**
     * 클래스 분석 프롬프트
     */
    private fun buildClassAnalysisPrompt(className: String, methodNames: List<String>): String {
        val methodsList = methodNames.joinToString("\n") { "- $it" }

        return """Analyze this obfuscated Java class. Suggest a meaningful class name based on its methods.

Class: $className

Methods:
$methodsList

Respond with JSON only:
{
  "name":"SuggestedClassName",
  "desc":"what this class does",
  "reasoning":"why this name fits",
  "confidence":0.85
}

Rules:
- Analyze the METHODS to understand class purpose
- Use standard Java naming (PascalCase)
- Keep names concise and descriptive
- confidence: 0.0-1.0 (higher = more certain)

Example: {"name":"UserAuthManager","desc":"handles user authentication","reasoning":"multiple auth-related methods","confidence":0.9}"""
    }

    /**
     * 패키지 분석 프롬프트
     */
    private fun buildPackageAnalysisPrompt(
        packageName: String,
        classNames: List<String>,
        description: String
    ): String {
        val classList = classNames.joinToString("\n") { "- $it" }

        return """Analyze this obfuscated Java package. Suggest a meaningful package name based on its classes.

Package: $packageName

Classes in this package:
$classList

Summary: $description

Respond with JSON only:
{
  "name":"com.suggested.packagename",
  "desc":"what this package contains",
  "reasoning":"why this package name fits",
  "confidence":0.85
}

Rules:
- Analyze the CLASSES to understand package purpose
- Use standard Java package naming (lowercase, dot-separated)
- Common package patterns: com.company.feature, com.company.module.submodule
- Examples: com.example.api, com.example.user, com.app.network
- Keep names concise and descriptive
- confidence: 0.0-1.0 (higher = more certain)

Example: {"name":"com.example.api","desc":"API layer for network operations","reasoning":"contains network request classes and REST API interfaces","confidence":0.9}"""
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
                    "temperature" to 0.1,  // 낮은 온도로 일관성 유지
                    "num_predict" to 2048,  // DeepSeek-R1 reasoning을 위한 충분한 토큰
                    "top_p" to 0.9
                    // stop 제거: JSON 파싱은 extractJson이 처리
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
     * 응답 파싱 - 로컬 변수 포함
     */
    private fun parseMethodAnalysis(response: String, method: MethodNode): MethodAnalysisResult? {
        return try {
            if (response.isBlank()) {
                logger.warn("Empty response from Ollama for method ${method.methodName}")
                return null
            }

            val jsonStr = extractJson(response)
            if (jsonStr == null) {
                logger.warn("No JSON found in response for ${method.methodName}. Response: ${response.take(200)}")
                return null
            }

            val json = JsonParser.parseString(jsonStr).asJsonObject

            // 로컬 변수 파싱
            val localVars = mutableMapOf<String, VariableRename>()
            json.get("vars")?.asJsonObject?.let { varsObj ->
                varsObj.entrySet().forEach { (oldName, newNameElement) ->
                    val newName = newNameElement.asString
                    if (oldName != newName) {  // 실제로 변경되는 경우만
                        localVars[oldName] = VariableRename(
                            originalName = oldName,
                            suggestedName = newName,
                            description = "Renamed from $oldName to $newName"
                        )
                    }
                }
            }

            MethodAnalysisResult(
                methodId = method.id,
                suggestedName = json.get("name")?.asString ?: method.methodName,
                description = json.get("desc")?.asString ?: "",
                reasoning = json.get("reasoning")?.asString ?: "",
                localVariables = localVars
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse Ollama response for ${method.methodName}: ${e.message}. Response: ${response.take(200)}")
            null
        }
    }

    /**
     * 배치 응답 파싱
     */
    private fun parseBatchAnalysis(response: String, methods: List<MethodNode>): List<MethodAnalysisResult> {
        return try {
            val jsonStr = extractJsonArray(response) ?: return emptyList()
            val jsonArray = JsonParser.parseString(jsonStr).asJsonArray

            jsonArray.mapIndexedNotNull { index, element ->
                try {
                    val json = element.asJsonObject
                    val methodIndex = json.get("i")?.asInt ?: index
                    val method = methods.getOrNull(methodIndex) ?: return@mapIndexedNotNull null

                    MethodAnalysisResult(
                        methodId = method.id,
                        suggestedName = json.get("name")?.asString ?: method.methodName,
                        description = json.get("desc")?.asString ?: "",
                        reasoning = ""
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: ${e.message}")
            emptyList()
        }
    }

    /**
     * 클래스 분석 응답 파싱
     */
    private fun parseClassAnalysis(response: String, originalClassName: String): ClassAnalysisResult? {
        return try {
            if (response.isBlank()) {
                logger.warn("Empty response from Ollama for class $originalClassName")
                return null
            }

            val jsonStr = extractJson(response)
            if (jsonStr == null) {
                logger.warn("No JSON found in response for class $originalClassName. Response: ${response.take(200)}")
                return null
            }

            val json = JsonParser.parseString(jsonStr).asJsonObject

            ClassAnalysisResult(
                originalClassName = originalClassName,
                suggestedName = json.get("name")?.asString ?: originalClassName,
                description = json.get("desc")?.asString ?: "",
                reasoning = json.get("reasoning")?.asString ?: "",
                confidence = json.get("confidence")?.asFloat ?: 0.0f
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse Ollama response for class $originalClassName: ${e.message}. Response: ${response.take(200)}")
            null
        }
    }

    /**
     * 패키지 분석 응답 파싱
     */
    private fun parsePackageAnalysis(response: String, originalPackageName: String): PackageAnalysisResult? {
        return try {
            if (response.isBlank()) {
                logger.warn("Empty response from Ollama for package $originalPackageName")
                return null
            }

            val jsonStr = extractJson(response)
            if (jsonStr == null) {
                logger.warn("No JSON found in response for package $originalPackageName. Response: ${response.take(200)}")
                return null
            }

            val json = JsonParser.parseString(jsonStr).asJsonObject

            PackageAnalysisResult(
                originalPackageName = originalPackageName,
                suggestedPackageName = json.get("name")?.asString ?: originalPackageName,
                description = json.get("desc")?.asString ?: "",
                reasoning = json.get("reasoning")?.asString ?: "",
                confidence = json.get("confidence")?.asFloat ?: 0.0f
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse Ollama response for package $originalPackageName: ${e.message}. Response: ${response.take(200)}")
            null
        }
    }

    /**
     * JSON 추출
     * DeepSeek-R1의 <think> 태그 제거 포함
     */
    private fun extractJson(text: String): String? {
        // DeepSeek-R1의 <think>...</think> 태그 제거
        val cleanedText = text
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .replace(Regex("<think>[\\s\\S]*"), "")  // 닫히지 않은 <think> 태그도 제거
            .trim()

        val start = cleanedText.indexOf("{")
        if (start == -1) return null

        var depth = 0
        for (i in start until cleanedText.length) {
            when (cleanedText[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return cleanedText.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * JSON 배열 추출
     */
    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf("[")
        if (start == -1) return null

        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Ollama 사용 가능 여부 확인
     */
    override fun isAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false

                // 설치된 모델 목록 확인
                val body = response.body?.string() ?: return false
                val json = JsonParser.parseString(body).asJsonObject
                val models = json.getAsJsonArray("models")

                // 지정된 모델이 설치되어 있는지 확인
                val hasModel = models.any { model ->
                    model.asJsonObject.get("name")?.asString?.contains(modelName.substringBefore(":")) == true
                }

                if (!hasModel) {
                    logger.warn("Model '$modelName' not found. Run: ollama pull $modelName")
                }

                hasModel
            }
        } catch (e: Exception) {
            logger.warn("Ollama not available: ${e.message}")
            false
        }
    }
}
