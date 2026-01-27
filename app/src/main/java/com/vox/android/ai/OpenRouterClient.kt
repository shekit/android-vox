package com.vox.android.ai

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Generic HTTP client for OpenRouter API.
 * Handles authentication, request building, and response parsing.
 */
class OpenRouterClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL
) {
    companion object {
        private const val TAG = "Vox"
        private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"

        const val DEFAULT_MODEL = "anthropic/claude-opus-4.5"

        val AVAILABLE_MODELS = listOf(
            "anthropic/claude-opus-4.5",
            "anthropic/claude-sonnet-4.5",
            "openai/gpt-4o",
            "google/gemini-2.5-flash"
        )

        val MODEL_DISPLAY_NAMES = mapOf(
            "anthropic/claude-opus-4.5" to "Claude Opus 4.5",
            "anthropic/claude-sonnet-4.5" to "Claude Sonnet 4.5",
            "openai/gpt-4o" to "GPT-4o",
            "google/gemini-2.5-flash" to "Gemini 2.5 Flash"
        )
    }

    private val client = OkHttpClient()
    private val mediaType = "application/json".toMediaType()

    /**
     * Send a chat completion request.
     *
     * @param systemPrompt The system prompt
     * @param userMessage The user message
     * @param screenshotBase64 Optional base64-encoded screenshot
     * @param responseSchema Optional JSON schema for structured output
     * @return The response content string, or throws an exception on failure
     */
    suspend fun sendChatRequest(
        systemPrompt: String,
        userMessage: String,
        screenshotBase64: String? = null,
        responseSchema: Map<String, Any>? = null
    ): String = suspendCancellableCoroutine { continuation ->
        Log.d(TAG, "Sending request to OpenRouter API")
        Log.d(TAG, "Model: $model")
        Log.d(TAG, "Screenshot: ${if (screenshotBase64 != null) "${screenshotBase64.length} chars" else "none"}")

        val requestBody = buildRequestBody(systemPrompt, userMessage, screenshotBase64, responseSchema)
        val requestBuilder = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/anthropics/android-vox")
            .addHeader("X-Title", "android-vox")

        // Add Anthropic beta header for Claude models (required for structured outputs)
        if (model.startsWith("anthropic/")) {
            requestBuilder.addHeader("x-anthropic-beta", "structured-outputs-2025-11-13")
        }

        val request = requestBuilder
            .post(requestBody.toRequestBody(mediaType))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "API request failed", e)
                if (continuation.isActive) {
                    continuation.resumeWithException(ApiException("Network error: ${e.message}", e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        Log.e(TAG, "API request unsuccessful: ${response.code} - $errorBody")
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                ApiException("API error ${response.code}: $errorBody")
                            )
                        }
                        return
                    }

                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        Log.d(TAG, "API response received (${responseBody.length} chars)")
                        try {
                            val content = extractContent(responseBody)
                            if (continuation.isActive) {
                                continuation.resume(content)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error extracting content from response", e)
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    ApiException("Failed to parse response: ${e.message}", e)
                                )
                            }
                        }
                    } else {
                        Log.e(TAG, "Empty response body")
                        if (continuation.isActive) {
                            continuation.resumeWithException(ApiException("Empty response from API"))
                        }
                    }
                }
            }
        })
    }

    private fun buildRequestBody(
        systemPrompt: String,
        userMessage: String,
        screenshotBase64: String?,
        responseSchema: Map<String, Any>?
    ): String {
        val jsonBody = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1024)
            put("messages", JSONArray().apply {
                // System message
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                // User message with optional image
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        // Add screenshot image if provided
                        if (screenshotBase64 != null) {
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$screenshotBase64")
                                })
                            })
                        }
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", userMessage)
                        })
                    })
                })
            })

            // Add response_format for structured output if schema provided
            if (responseSchema != null) {
                put("response_format", JSONObject().apply {
                    put("type", "json_schema")
                    put("json_schema", JSONObject().apply {
                        put("name", "action_response")
                        put("strict", true)
                        put("schema", mapToJsonObject(responseSchema))
                    })
                })
            }
        }

        return jsonBody.toString()
    }

    private fun mapToJsonObject(map: Map<*, *>): JSONObject {
        val json = JSONObject()
        for ((key, value) in map) {
            when (value) {
                is Map<*, *> -> json.put(key.toString(), mapToJsonObject(value))
                is List<*> -> json.put(key.toString(), JSONArray(value))
                else -> json.put(key.toString(), value)
            }
        }
        return json
    }

    private fun extractContent(responseJson: String): String {
        val json = JSONObject(responseJson)
        val choices = json.getJSONArray("choices")
        val message = choices.getJSONObject(0).getJSONObject("message")
        return message.getString("content").trim()
    }
}

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
