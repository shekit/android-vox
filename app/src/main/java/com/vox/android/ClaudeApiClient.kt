package com.vox.android

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ClaudeApiClient(private val apiKey: String) {

    companion object {
        private const val TAG = "ClaudeApiClient"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-sonnet-4-20250514"
        private const val API_VERSION = "2023-06-01"
    }

    private val client = OkHttpClient()
    private val mediaType = "application/json".toMediaType()

    // P6.3: Send request to Claude API
    fun sendRequest(
        userCommand: String,
        uiTree: String,
        callback: (success: Boolean, response: String?, error: String?) -> Unit
    ) {
        Log.d(TAG, "Sending request to Claude API")
        Log.d(TAG, "Command: $userCommand")
        Log.d(TAG, "UI tree length: ${uiTree.length} chars")

        val requestBody = buildRequestBody(userCommand, uiTree)
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(requestBody.toRequestBody(mediaType))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "API request failed", e)
                callback(false, null, "Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        Log.e(TAG, "API request unsuccessful: ${response.code} - $errorBody")
                        callback(false, null, "API error ${response.code}: $errorBody")
                        return
                    }

                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        Log.d(TAG, "API response received (${responseBody.length} chars)")
                        callback(true, responseBody, null)
                    } else {
                        Log.e(TAG, "Empty response body")
                        callback(false, null, "Empty response from API")
                    }
                }
            }
        })
    }

    private fun buildRequestBody(userCommand: String, uiTree: String): String {
        val systemPrompt = """
You are an Android accessibility assistant. You receive UI trees from Android apps and user commands.
Your job is to determine what action to take to fulfill the user's command.

Available actions:
- tap <text>: Tap on an element with the given text
- type <text> into <field>: Type text into a field
- scroll down: Scroll down
- scroll up: Scroll up
- back: Press back button
- home: Press home button
- launch <app>: Launch an app (use package names like com.android.settings)

Respond with ONLY the action command to execute, nothing else. If the task is complete or impossible, respond with "done".

UI Tree (JSON):
$uiTree
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 1024)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "System: $systemPrompt\n\nUser command: $userCommand")
                        })
                    })
                })
            })
        }

        return jsonBody.toString()
    }
}
