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
    private val timeout: Long = 600_000,  // 10분 (DeepSeek-R1 reasoning 고려)
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
     *
     * 전략:
     * 1. iteration=1: 기본 프롬프트 (단일 메서드만)
     * 2. iteration=2: 클래스 컨텍스트 포함 (즉시 RETRY)
     * 3. iteration=3+: 이전 방식으로 큐에 넣고 나중에 재시도
     */
    override fun analyzeMethod(
        method: MethodNode,
        sourceCode: String,
        iteration: Int,
        classSourceCode: String
    ): MethodAnalysisResult? {
        return executeWithRetry(
            targetName = method.methodName,
            requestType = "analysis",
            initialIteration = iteration,
            method = method,
            sourceCode = sourceCode,
            classSourceCode = classSourceCode
        )
    }

    /**
     * 제네릭 재시도 함수 (iteration 증가 지원)
     *
     * @param targetName 분석 대상 이름 (메서드/클래스/패키지)
     * @param requestType 요청 타입 ("analysis", "class_analysis", "package_analysis")
     * @param initialIteration 초기 ITERATION 번호
     * @param method 메서드 노드
     * @param sourceCode 메서드 소스 코드
     * @param classSourceCode 클래스 컨텍스트 소스
     * @return 성공 시 결과, 실패 시 null
     */
    private fun executeWithRetry(
        targetName: String,
        requestType: String,
        initialIteration: Int = 1,
        method: MethodNode? = null,
        sourceCode: String? = null,
        classSourceCode: String = ""
    ): MethodAnalysisResult? {
        logger.info("[OllamaClient] Starting $requestType for $targetName | Iteration: $initialIteration | MaxRetries: $maxRetries")

        var result: MethodAnalysisResult? = null
        var currentIteration = initialIteration
        var classSource = classSourceCode  // 초기 클래스 소스

        repeat(maxRetries) { attempt ->
            logger.debug("[OllamaClient] Attempt ${attempt + 1}/$maxRetries | Current Iteration: $currentIteration | Target: $targetName")

            try {
                // ITERATION에 따른 프롬프트 선택
                // iteration=2이면 자동으로 클래스 소스 로드 시도
                val prompt = if (method != null && sourceCode != null) {
                    when {
                        currentIteration >= 2 && classSource.isEmpty() && method.file != null -> {
                            // 클래스 소스를 아직 읽지 않았음 → 읽기 시도
                            logger.info("[OllamaClient] Iteration $currentIteration: Loading class context for $targetName...")
                            classSource = method.file.readText().take(3000)  // 최대 3000자
                            logger.debug("[OllamaClient] Class context loaded: ${classSource.length} chars")
                            buildEnhancedPromptWithClassContext(method, sourceCode, classSource)
                        }
                        currentIteration >= 2 && classSource.isNotEmpty() -> {
                            logger.debug("[OllamaClient] Using enhanced prompt with class context for $targetName")
                            buildEnhancedPromptWithClassContext(method, sourceCode, classSource)
                        }
                        else -> {
                            logger.debug("[OllamaClient] Using compact prompt for $targetName")
                            buildCompactPrompt(method, sourceCode)
                        }
                    }
                } else {
                    // 클래스/패키지 분석용 프롬프트
                    when (requestType) {
                        "class_analysis" -> buildClassAnalysisPrompt(targetName, emptyList()) // TODO: 실제 메서드名 전달
                        "package_analysis" -> buildPackageAnalysisPrompt(targetName, emptyList(), "")
                        else -> ""
                    }
                }

                logger.debug("[OllamaClient] Prompt size: ${prompt.length} chars | Preview: ${prompt.take(100)}...")

                val startTime = System.currentTimeMillis()
                logger.info("[OllamaClient] Calling Ollama API | Model: $modelName | Target: $targetName")

                val response = callOllama(prompt)

                val duration = System.currentTimeMillis() - startTime
                logger.info("[OllamaClient] Ollama response received | Duration: ${duration}ms | Size: ${response.length} chars")

                result = if (method != null) {
                    logger.debug("[OllamaClient] Parsing method analysis response...")
                    parseMethodAnalysis(response, method)
                } else {
                    null // TODO: 클래스/패키지 분석
                }

                // 성공 조건: result가 null이 아니고 suggestedName이 원본과 다름
                val parsedResult = result  // Smart cast를 위한 로컬 복사
                val success = parsedResult != null && (method?.methodName == null || parsedResult.suggestedName != method.methodName)

                if (success) {
                    logger.info("[OllamaClient] ✓ SUCCESS | Target: $targetName | Old: ${method?.methodName} | New: ${parsedResult?.suggestedName} | Confidence: ${parsedResult?.confidence} | Duration: ${duration}ms")
                } else {
                    when {
                        parsedResult == null -> logger.warn("[OllamaClient] ✗ PARSE FAILED | Target: $targetName | Attempt: ${attempt + 1}/$maxRetries")
                        method != null && parsedResult.suggestedName == method.methodName -> {
                            logger.warn("[OllamaClient] ✗ NAME UNCHANGED | Target: $targetName | Suggested: ${parsedResult.suggestedName} | Attempt: ${attempt + 1}/$maxRetries")
                        }
                    }
                }

                // Always log the request (both success and failure)
                val confidence = if (success) parsedResult?.confidence ?: 0.0f else 0.0f
                monitor?.addLlmRequest(
                    methodName = targetName,
                    requestType = requestType,
                    model = modelName,
                    promptPreview = prompt.take(200),
                    response = response.take(500),
                    durationMs = duration,
                    success = success,
                    confidence = confidence,
                    iteration = currentIteration
                )

                if (success) {
                    return result  // 성공 시 결과 반환 (중요: result가 null이 아니라는 것이 보장됨)
                } else {
                    when {
                        parsedResult == null -> logger.warn("Attempt ${attempt + 1}/$maxRetries (iter=$currentIteration): Parse failed for $targetName")
                        method != null && parsedResult.suggestedName == method.methodName ->
                            logger.warn("Attempt ${attempt + 1}/$maxRetries (iter=$currentIteration): Method name unchanged ($targetName)")
                    }
                    currentIteration++  // 다음 시도를 위해 iteration 증가
                }
            } catch (e: Exception) {
                logger.error("[OllamaClient] ✗ EXCEPTION | Attempt: ${attempt + 1}/$maxRetries | Target: $targetName | Error: ${e.message} | Type: ${e.javaClass.simpleName}")
                logger.debug("[OllamaClient] Stack trace:", e)
                currentIteration++
            }

            // 마지막 시도가 아니면 대기
            if (attempt < maxRetries - 1) {
                logger.debug("[OllamaClient] Waiting ${retryDelayMs}ms before retry...")
                Thread.sleep(retryDelayMs)
            }
        }

        // 모든 재시도 실패
        logger.error("[OllamaClient] ✗ ALL RETRIES FAILED | Target: $targetName | Attempts: $maxRetries | Final Iteration: $currentIteration")
        return null
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

Current method name: ${method.methodName}

```java
$sourceCode
```

IMPORTANT INSTRUCTIONS FOR DEEPSEEK-R1:
1. You MAY think through this problem step-by-step in your thinking process
2. However, your FINAL ANSWER in the 'response' field MUST be valid JSON only
3. Do NOT include your reasoning in the final response - only the JSON
4. The format below is the ONLY acceptable format for your final answer

Your final answer must be EXACTLY this JSON format (nothing else):
{
  "name":"NEW_MEANINGFUL_NAME",
  "desc":"what it does",
  "reasoning":"why this name",
  "confidence":0.85,
  "vars":{"oldVarName":"newVarName"}
}

Rules:
- Current name is "${method.methodName}" - you MUST suggest a DIFFERENT, descriptive name
- Analyze the CODE BEHAVIOR, not the method name itself
- Do NOT simply return the original name "${method.methodName}"
- Do NOT simply translate non-English method names to English
- If method name looks generic (like "meaningfulMethod", "deobfuscatedMethodName"), analyze the actual code logic instead
- ALWAYS suggest a descriptive name based on BEHAVIOR
- For setter methods: use "setXxx()" pattern (e.g., "setListenerList", "setByteBuffer")
- For getter methods: use "getXxx()" pattern
- confidence: 0.0-1.0 (how confident are you? 1.0 = very certain, 0.5 = uncertain, 0.3 = guessing)

CRITICAL - DO NOT use obfuscated type names:
- If you see patterns like "AbstractC1234", "C5678", "ClassXYZ", "f9999", these are OBFUSCATED names
- DO NOT suggest names like "getAbstractC1234()", "getC5678()", "setFieldF9999()"
- Instead, infer meaning from BEHAVIOR and CONTEXT:
  - "AbstractC1234" → "secureElement", "cryptoContext", "keyStore", "paymentData" etc.
  - "f17900" → "buffer", "config", "state", "instance" etc.
- Use domain-specific terms: "secure", "crypto", "encryption", "auth", "token", "session", "transaction", "payment"
- Example: If method returns "AbstractC6886" in a payment processing class, name it "getPaymentData()" NOT "getAbstractC6886()"

IMPORTANT - vars rules:
- ONLY rename LOCAL VARIABLE DECLARATIONS (e.g., "int i", "String str", "ArrayList arrayList")
- Variables to rename: single-char names (i, j, k), numbered names (i2, str3), or generic names (obj, temp)
- DO NOT include: type names (ArrayList, String), field accesses (this.xxx), method calls
- If no variables need renaming, use empty object: "vars":{}
- Example: in "ArrayList list = new ArrayList()", the variable is "list", NOT "ArrayList"

Good examples:
- BAD: "getAbstractC6886()", "getC1234Field()"
- GOOD: "getSecureElement()", "getCryptoContext()", "getKeyStore()"

Example: {"name":"calculateTotal","desc":"sums values","reasoning":"indicates calculation","confidence":0.9,"vars":{"i":"counter","str":"result"}}"""
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

Current method name: ${method.methodName} - you MUST suggest a DIFFERENT name

Target Method:
```java
$sourceCode
```

Class Context (all fields and related methods):
```java
${classSourceCode.take(2000)}
```

IMPORTANT INSTRUCTIONS FOR DEEPSEEK-R1:
1. You MAY think through this problem step-by-step in your thinking process
2. However, your FINAL ANSWER in the 'response' field MUST be valid JSON only
3. Do NOT include your reasoning in the final response - only the JSON
4. The format below is the ONLY acceptable format for your final answer

Your final answer must be EXACTLY this JSON format (nothing else):
{
  "name":"NEW_MEANINGFUL_NAME",
  "desc":"what it does",
  "reasoning":"why this name",
  "confidence":0.85,
  "vars":{"oldVarName":"newVarName"}
}

Rules:
- Current name is "${method.methodName}" - you MUST suggest a DIFFERENT, descriptive name
- Do NOT return the original name "${method.methodName}"
- Use the CLASS CONTEXT to understand what fields like f17840 are used for
- Look at other methods to see how these fields are used
- ALWAYS suggest a descriptive name based on BEHAVIOR
- For setter methods: use "setXxx()" pattern based on field purpose (e.g., "setByteBuffer" if f17840 is a byte buffer)
- For getter methods: use "getXxx()" pattern

CRITICAL - DO NOT use obfuscated type/field names:
- If you see patterns like "AbstractC1234", "C5678", "f9999", these are OBFUSCATED names
- DO NOT suggest names like "getAbstractC1234()", "setF9999()"
- Use class context to infer meaning: what is f17840 used for? What does AbstractC6886 represent?
- Use domain-specific terms: "secure", "crypto", "encryption", "auth", "token", "session", "transaction", "payment", "buffer", "config", "state"
- Example: If method returns "AbstractC6886" and class shows it's used in payment processing, name it "getPaymentData()" NOT "getAbstractC6886()"

Example: {"name":"setSecureElement","desc":"sets the security element field","reasoning":"f17840 stores encryption keys based on initializeKey() method","vars":{"list":"buffer"}}"""
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
        logger.info("[TRACE] callOllama() ENTER - prompt length: ${prompt.length}, model: $modelName")
        try {
            val requestBody = mapOf(
                "model" to modelName,
                "prompt" to prompt,
                "stream" to false,
                "options" to mapOf(
                    "temperature" to 0.1,  // 낮은 온도로 일관성 유지
                    "num_predict" to -1,  // 모델이 자체 최대 토큰까지 사용 (JSON 잘림 방지)
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

            logger.info("[TRACE] Sending HTTP request to Ollama...")
            httpClient.newCall(request).execute().use { response ->
                logger.info("[TRACE] HTTP response received: code=${response.code}, successful=${response.isSuccessful}")

                if (!response.isSuccessful) {
                    logger.error("[ERROR] Ollama request failed: HTTP ${response.code}")
                    return ""
                }

                val responseBody = response.body?.string() ?: ""
                logger.info("[TRACE] Response body length: ${responseBody.length}, isBlank=${responseBody.isBlank()}")

                // Debug: 응답이 비어있는 경우 원인 출력
                if (responseBody.isBlank()) {
                    logger.error("[ERROR] Ollama returned empty response body")
                    return ""
                }

                try {
                    val jsonResponse = JsonParser.parseString(responseBody).asJsonObject

                    // DeepSeek-R1: response 필드가 비어있으면 thinking 필드 확인
                    var ollamaResponse = jsonResponse.get("response")?.asString

                    if (ollamaResponse.isNullOrBlank()) {
                        val thinking = jsonResponse.get("thinking")?.asString
                        if (!thinking.isNullOrBlank()) {
                            logger.info("[TRACE] 'response' field is empty, using 'thinking' field instead (length: ${thinking.length})")
                            ollamaResponse = thinking
                        }
                    }

                    logger.info("[TRACE] Extracted response: isNull=${ollamaResponse == null}, isBlank=${ollamaResponse.isNullOrBlank()}")

                    if (ollamaResponse.isNullOrBlank()) {
                        logger.error("[ERROR] Ollama response and thinking fields are both null/blank. Full response: ${responseBody.take(500)}")
                        return ""
                    }

                    logger.info("[TRACE] callOllama() SUCCESS - response length: ${ollamaResponse.length}, preview: ${ollamaResponse.take(100)}")
                    return ollamaResponse
                } catch (e: Exception) {
                    logger.error("[ERROR] Failed to parse Ollama response: ${e.message}. Response: ${responseBody.take(500)}")
                    return ""
                }
            }
        } catch (e: Exception) {
            logger.error("[ERROR] Ollama API call failed with exception: ${e.message}", e)
            logger.error("[ERROR] Exception type: ${e.javaClass.name}")
            return ""
        }
    }

    /**
     * 응답 파싱 - 로컬 변수 포함
     */
    private fun parseMethodAnalysis(response: String, method: MethodNode): MethodAnalysisResult? {
        logger.info("[TRACE] parseMethodAnalysis() ENTER - method: ${method.methodName}, response length: ${response.length}, isBlank: ${response.isBlank()}")
        return try {
            if (response.isBlank()) {
                logger.error("[ERROR] Empty response from Ollama for method ${method.methodName}")
                return null
            }

            val jsonStr = extractJson(response)
            if (jsonStr == null) {
                logger.error("[ERROR] No JSON found in response for ${method.methodName}. Response: ${response.take(200)}")
                return null
            }

            val json = JsonParser.parseString(jsonStr).asJsonObject

            // 이름 필드 필수 체크
            val suggestedName = json.get("name")?.asString
            logger.info("[TRACE] Extracted suggestedName: $suggestedName")
            if (suggestedName.isNullOrBlank()) {
                logger.error("[ERROR] Missing or empty 'name' field in response for ${method.methodName}. Response: ${response.take(200)}")
                return null
            }

            // 제안된 이름이 원본 이름과 같으면 실패 처리 (LLM이 제대로 분석하지 못함)
            if (suggestedName == method.methodName) {
                logger.warn("[WARN] LLM returned the same obfuscated name '${method.methodName}' for ${method.methodName}. Response: ${response.take(200)}")
                return null
            }

            // 로컬 변수 파싱
            val localVars = mutableMapOf<String, VariableRename>()
            json.get("vars")?.asJsonObject?.let { varsObj ->
                logger.info("[TRACE] Found 'vars' field with ${varsObj.size()} entries")
                varsObj.entrySet().forEach { (oldName, newNameElement) ->
                    val newName = newNameElement.asString
                    logger.info("[TRACE]   Variable: $oldName → $newName")
                    if (oldName != newName) {  // 실제로 변경되는 경우만
                        localVars[oldName] = VariableRename(
                            originalName = oldName,
                            suggestedName = newName,
                            description = "Renamed from $oldName to $newName"
                        )
                    }
                }
            } ?: logger.info("[TRACE] No 'vars' field in response")

            logger.info("[TRACE] parseMethodAnalysis() SUCCESS - suggestedName: $suggestedName, localVars: ${localVars.size}")
            MethodAnalysisResult(
                methodId = method.id,
                suggestedName = suggestedName,  // null이 아니라는 것이 보장됨
                description = json.get("desc")?.asString ?: "",
                reasoning = json.get("reasoning")?.asString ?: "",
                confidence = json.get("confidence")?.asFloat ?: 0.5f,  // 기본값 0.5
                localVariables = localVars
            )
        } catch (e: Exception) {
            logger.error("[ERROR] Failed to parse Ollama response for ${method.methodName}: ${e.message}. Response: ${response.take(200)}")
            logger.error("[ERROR] Exception type: ${e.javaClass.name}")
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
        // DeepSeek-R1의 <think>...</think> 태그 제거 (다양한 형태 지원)
        var cleanedText = text

        // 완전한 태그 제거: <think>...</think> 또는 <｜begin▁of▁thinking｜>...</think>
        cleanedText = cleanedText.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        cleanedText = cleanedText.replace(Regex("<\\|.*?\\|>[\\s\\S]*?</think>", RegexOption.DOT_MATCHES_ALL), "")

        // 잘린 태그 제거: <｜begi 또는 <think... (중간에 잘린 경우)
        cleanedText = cleanedText.replace(Regex("<\\|[^|\\s]*"), "")  // <｜... 또는 <｜begi 제거
        cleanedText = cleanedText.replace(Regex("<think[^>]*"), "")  // <think... 시작 부분 제거
        cleanedText = cleanedText.replace(Regex("▁of▁thinking[^\\s|]*"), "")  // ▁of▁thinking... 제거

        cleanedText = cleanedText.trim()

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
