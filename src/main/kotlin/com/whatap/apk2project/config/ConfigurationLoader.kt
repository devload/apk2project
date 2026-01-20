package com.whatap.apk2project.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Properties

/**
 * 설정 파일 로더
 *
 * 지원 형식:
 * - .properties (Java Properties)
 * - .json (JSON)
 * - .yaml/.yml (YAML - 간단한 파서)
 *
 * 설정 우선순위:
 * 1. 환경 변수
 * 2. 커맨드라인 인자
 * 3. 설정 파일
 * 4. 기본값
 */
class ConfigurationLoader {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * 설정 파일 로드
     *
     * @param configFile 설정 파일 경로 (null이면 기본 위치에서 찾음)
     * @return 로드된 설정
     */
    fun load(configFile: File? = null): AppConfiguration {
        val file = configFile ?: findDefaultConfigFile()

        return if (file != null && file.exists()) {
            logger.info("Loading configuration from: ${file.absolutePath}")
            loadFromFile(file)
        } else {
            logger.info("No configuration file found, using defaults")
            AppConfiguration()
        }
    }

    /**
     * 기본 설정 파일 위치 탐색
     */
    private fun findDefaultConfigFile(): File? {
        val searchPaths = listOf(
            File("apk2project.properties"),
            File("apk2project.json"),
            File("apk2project.yaml"),
            File("apk2project.yml"),
            File(".apk2project/config.properties"),
            File(".apk2project/config.json"),
            File(System.getProperty("user.home"), ".apk2project/config.properties"),
            File(System.getProperty("user.home"), ".apk2project/config.json")
        )

        return searchPaths.find { it.exists() }
    }

    /**
     * 파일에서 설정 로드
     */
    private fun loadFromFile(file: File): AppConfiguration {
        return when (file.extension.lowercase()) {
            "properties" -> loadFromProperties(file)
            "json" -> loadFromJson(file)
            "yaml", "yml" -> loadFromYaml(file)
            else -> {
                logger.warn("Unknown config file format: ${file.extension}, trying as properties")
                loadFromProperties(file)
            }
        }
    }

    /**
     * Properties 파일에서 로드
     */
    private fun loadFromProperties(file: File): AppConfiguration {
        val props = Properties()
        file.inputStream().use { props.load(it) }

        return AppConfiguration(
            // AI 설정
            ai = AiConfiguration(
                clientType = props.getProperty("ai.client", "ollama"),
                modelName = props.getProperty("ai.model", "deepseek-coder:6.7b"),
                ollamaBaseUrl = props.getProperty("ai.ollama.url", "http://localhost:11434"),
                timeout = props.getProperty("ai.timeout", "600000").toLongOrNull() ?: 600_000L,
                maxRetries = props.getProperty("ai.maxRetries", "3").toIntOrNull() ?: 3
            ),
            // 파이프라인 설정
            pipeline = PipelineConfiguration(
                batchSize = props.getProperty("pipeline.batchSize", "2").toIntOrNull() ?: 2,
                aiBatchSize = props.getProperty("pipeline.aiBatchSize", "5").toIntOrNull() ?: 5,
                requestDelay = props.getProperty("pipeline.requestDelay", "1000").toLongOrNull() ?: 1000L,
                useCache = props.getProperty("pipeline.useCache", "true").toBoolean(),
                enableKorean = props.getProperty("pipeline.enableKorean", "false").toBoolean(),
                translationModel = props.getProperty("pipeline.translationModel", "qwen2.5:7b")
            ),
            // 로깅 설정
            logging = LoggingConfiguration(
                level = props.getProperty("logging.level", "INFO"),
                fileEnabled = props.getProperty("logging.file.enabled", "false").toBoolean(),
                filePath = props.getProperty("logging.file.path", "logs/apk2project.log"),
                metricsEnabled = props.getProperty("logging.metrics.enabled", "true").toBoolean()
            ),
            // 캐시 설정
            cache = CacheConfiguration(
                maxMemorySize = props.getProperty("cache.maxMemorySize", "100").toIntOrNull() ?: 100,
                classContextMaxSize = props.getProperty("cache.classContext.maxSize", "500").toIntOrNull() ?: 500,
                enableDiskCache = props.getProperty("cache.disk.enabled", "true").toBoolean()
            )
        )
    }

    /**
     * JSON 파일에서 로드
     */
    private fun loadFromJson(file: File): AppConfiguration {
        return try {
            gson.fromJson(file.readText(), AppConfiguration::class.java)
        } catch (e: Exception) {
            logger.error("Failed to parse JSON config: ${e.message}")
            AppConfiguration()
        }
    }

    /**
     * YAML 파일에서 로드 (간단한 파서)
     */
    private fun loadFromYaml(file: File): AppConfiguration {
        val props = Properties()
        val lines = file.readLines()

        var currentSection = ""
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

            when {
                // 섹션 헤더 (indent 없음)
                !line.startsWith(" ") && !line.startsWith("\t") && trimmed.endsWith(":") && !trimmed.contains(": ") -> {
                    currentSection = trimmed.removeSuffix(":")
                }
                // 키-값 쌍
                trimmed.contains(":") -> {
                    val parts = trimmed.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                        val fullKey = if (currentSection.isNotEmpty()) "$currentSection.$key" else key
                        props.setProperty(fullKey, value)
                    }
                }
            }
        }

        // Properties로 변환 후 로드
        val tempFile = File.createTempFile("yaml_converted", ".properties")
        tempFile.deleteOnExit()
        tempFile.outputStream().use { props.store(it, "Converted from YAML") }
        return loadFromProperties(tempFile)
    }

    /**
     * 설정 저장 (JSON 형식)
     */
    fun save(config: AppConfiguration, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(config))
        logger.info("Configuration saved to: ${file.absolutePath}")
    }

    /**
     * 샘플 설정 파일 생성
     */
    fun createSampleConfig(file: File) {
        val sample = AppConfiguration()
        save(sample, file)
        logger.info("Sample configuration created: ${file.absolutePath}")
    }
}

/**
 * 전체 앱 설정
 */
data class AppConfiguration(
    val ai: AiConfiguration = AiConfiguration(),
    val pipeline: PipelineConfiguration = PipelineConfiguration(),
    val logging: LoggingConfiguration = LoggingConfiguration(),
    val cache: CacheConfiguration = CacheConfiguration()
)

/**
 * AI 관련 설정
 */
data class AiConfiguration(
    val clientType: String = "ollama",           // ollama, claude, codex
    val modelName: String = "deepseek-coder:6.7b",
    val ollamaBaseUrl: String = "http://localhost:11434",
    val timeout: Long = 600_000,                 // 10분
    val maxRetries: Int = 3
)

/**
 * 파이프라인 설정
 */
data class PipelineConfiguration(
    val batchSize: Int = 2,                      // 병렬 워커 수
    val aiBatchSize: Int = 5,                    // AI 배치 단위
    val requestDelay: Long = 1000,               // 요청 간 지연
    val useCache: Boolean = true,
    val enableKorean: Boolean = false,
    val translationModel: String = "qwen2.5:7b"
)

/**
 * 로깅 설정
 */
data class LoggingConfiguration(
    val level: String = "INFO",
    val fileEnabled: Boolean = false,
    val filePath: String = "logs/apk2project.log",
    val metricsEnabled: Boolean = true
)

/**
 * 캐시 설정
 */
data class CacheConfiguration(
    val maxMemorySize: Int = 100,
    val classContextMaxSize: Int = 500,
    val enableDiskCache: Boolean = true
)
