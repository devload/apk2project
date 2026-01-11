package com.whatap.apk2project.deobfuscator.ai

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.whatap.apk2project.utils.Logger
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Claude API client for deobfuscation requests
 */
class ClaudeClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-20250514",
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1000
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // Approximate token costs (per 1M tokens)
        private const val INPUT_COST_PER_MILLION = 3.0   // $3 per 1M input tokens
        private const val OUTPUT_COST_PER_MILLION = 15.0 // $15 per 1M output tokens
    }

    /**
     * Send a deobfuscation request to Claude
     */
    suspend fun analyze(prompt: String, maxTokens: Int = 1024): ClaudeResponse {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                return sendRequest(prompt, maxTokens)
            } catch (e: RateLimitException) {
                Logger.warn("Rate limited, waiting ${e.retryAfterMs}ms (attempt $attempt/$maxRetries)")
                delay(e.retryAfterMs)
                lastException = e
            } catch (e: ServerException) {
                val delayMs = baseDelayMs * (1 shl (attempt - 1)) // Exponential backoff
                Logger.warn("Server error: ${e.message}, retrying in ${delayMs}ms (attempt $attempt/$maxRetries)")
                delay(delayMs)
                lastException = e
            } catch (e: Exception) {
                lastException = e
                break
            }
        }

        throw lastException ?: IOException("Unknown error")
    }

    private suspend fun sendRequest(prompt: String, maxTokens: Int): ClaudeResponse {
        val requestBody = ClaudeRequest(
            model = model,
            maxTokens = maxTokens,
            messages = listOf(
                Message(role = "user", content = prompt)
            )
        )

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(gson.toJson(requestBody).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return suspendCoroutine { continuation ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body?.string()

                        when {
                            resp.isSuccessful && body != null -> {
                                try {
                                    val apiResponse = gson.fromJson(body, ClaudeApiResponse::class.java)
                                    val content = apiResponse.content.firstOrNull()?.text ?: ""
                                    val inputTokens = apiResponse.usage.inputTokens
                                    val outputTokens = apiResponse.usage.outputTokens

                                    continuation.resume(ClaudeResponse(
                                        content = content,
                                        inputTokens = inputTokens,
                                        outputTokens = outputTokens,
                                        estimatedCost = calculateCost(inputTokens, outputTokens)
                                    ))
                                } catch (e: Exception) {
                                    continuation.resumeWithException(
                                        ParseException("Failed to parse response: ${e.message}")
                                    )
                                }
                            }
                            resp.code == 429 -> {
                                val retryAfter = resp.header("retry-after")?.toLongOrNull()?.times(1000)
                                    ?: 5000L
                                continuation.resumeWithException(RateLimitException(retryAfter))
                            }
                            resp.code in 500..599 -> {
                                continuation.resumeWithException(
                                    ServerException("Server error: ${resp.code} - $body")
                                )
                            }
                            else -> {
                                continuation.resumeWithException(
                                    ApiException("API error: ${resp.code} - $body")
                                )
                            }
                        }
                    }
                }
            })
        }
    }

    private fun calculateCost(inputTokens: Int, outputTokens: Int): Double {
        return (inputTokens * INPUT_COST_PER_MILLION / 1_000_000) +
               (outputTokens * OUTPUT_COST_PER_MILLION / 1_000_000)
    }

    /**
     * Estimate cost for a prompt before sending
     */
    fun estimateCost(promptLength: Int, expectedOutputTokens: Int = 500): Double {
        // Rough estimate: 4 characters per token
        val estimatedInputTokens = promptLength / 4
        return calculateCost(estimatedInputTokens, expectedOutputTokens)
    }
}

/**
 * Response from Claude API
 */
data class ClaudeResponse(
    val content: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCost: Double
)

// API request/response models
private data class ClaudeRequest(
    val model: String,
    @SerializedName("max_tokens") val maxTokens: Int,
    val messages: List<Message>
)

private data class Message(
    val role: String,
    val content: String
)

private data class ClaudeApiResponse(
    val content: List<ContentBlock>,
    val usage: Usage
)

private data class ContentBlock(
    val type: String,
    val text: String
)

private data class Usage(
    @SerializedName("input_tokens") val inputTokens: Int,
    @SerializedName("output_tokens") val outputTokens: Int
)

// Exceptions
class RateLimitException(val retryAfterMs: Long) : Exception("Rate limited")
class ServerException(message: String) : Exception(message)
class ApiException(message: String) : Exception(message)
class ParseException(message: String) : Exception(message)
