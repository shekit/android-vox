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
        private const val MODEL = "claude-opus-4-5-20251101"
        private const val API_VERSION = "2023-06-01"
        private const val BETA_HEADER = "structured-outputs-2025-11-13"
    }

    private val client = OkHttpClient()
    private val mediaType = "application/json".toMediaType()

    // P6.3: Send request to Claude API
    fun sendRequest(
        userCommand: String,
        uiTree: String,
        previousActions: String = "",
        installedApps: Map<String, String> = emptyMap(),
        callback: (success: Boolean, response: String?, error: String?) -> Unit
    ) {
        Log.d(TAG, "Sending request to Claude API")
        Log.d(TAG, "Command: $userCommand")
        Log.d(TAG, "UI tree length: ${uiTree.length} chars")

        val requestBody = buildRequestBody(userCommand, uiTree, previousActions, installedApps)
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("anthropic-beta", BETA_HEADER)
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

    private fun buildRequestBody(userCommand: String, uiTree: String, previousActions: String, installedApps: Map<String, String>): String {
        val actionHistory = if (previousActions.isNotEmpty()) {
            "\n\nPrevious actions taken:\n$previousActions"
        } else {
            ""
        }

        // Format installed apps list for the prompt
        val appListText = if (installedApps.isNotEmpty()) {
            val appLines = installedApps.entries.take(100).joinToString("\n") { (name, pkg) ->
                "- $name: $pkg"
            }
            "\n\nINSTALLED APPS (name: package):\n$appLines"
        } else {
            ""
        }

        val systemPrompt = """
You are an Android accessibility assistant running inside the "android-vox" app. You receive UI trees and user commands.
Your job is to determine what action to take to fulfill the user's command.

IMPORTANT:
- You are currently running INSIDE the android-vox app (package: com.vox.android)
- Do NOT tap buttons or interact with android-vox's own UI (buttons like "Ask Claude", "Save", etc.)
- If the UI tree shows android-vox's interface, IGNORE it and launch the app needed for the user's task
- Focus on executing the user's request on OTHER apps, not on android-vox itself

ACTION FEEDBACK:
- Each action in history shows its result: "→ SUCCESS" means the element was found and clicked
- After each action, you also see UI feedback about whether the screen changed
- [UI_FEEDBACK: The screen changed...] means your action had a visible effect
- [UI_FEEDBACK: The screen did NOT change...] means either:
  (a) The action completed silently (e.g., taking a photo, sending a message) - if SUCCESS
  (b) The action had no effect - consider trying a different approach
- Use both signals together: SUCCESS + no UI change often means task completed silently
- Do NOT repeat the exact same action - if it succeeded once, it already worked

Available actions with JSON format:

1. launch - Launch an app. Use the EXACT package name from the INSTALLED APPS list below.
   {"action": "launch", "parameters": {"app": "com.google.android.GoogleCamera"}}

2. tap - Tap on an element. The "text" MUST be an exact match from the UI tree (text or contentDescription).
   {"action": "tap", "parameters": {"text": "Search"}}

3. type - Type text into a field. The "field" MUST be an exact match from the UI tree (text or contentDescription).
   IMPORTANT: Do NOT guess field names. Only use text that actually appears in the UI tree below.
   {"action": "type", "parameters": {"text": "hello", "field": "Message"}}

4. scroll_down - Scroll down
   {"action": "scroll_down"}

5. scroll_up - Scroll up
   {"action": "scroll_up"}

6. back - Press back button
   {"action": "back"}

7. home - Press home button
   {"action": "home"}

8. enter - Press Enter key (to submit forms, URLs)
   {"action": "enter"}

9. done - Task complete or impossible
   {"action": "done"}

You MUST respond with a valid JSON object matching the schema.$actionHistory$appListText

UI Tree (JSON):
$uiTree
        """.trimIndent()

        // Define JSON schema for structured output
        val responseSchema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray().apply {
                        put("launch")
                        put("tap")
                        put("type")
                        put("scroll_down")
                        put("scroll_up")
                        put("back")
                        put("home")
                        put("enter")
                        put("done")
                    })
                    put("description", "The action to perform")
                })
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("app", JSONObject().apply {
                            put("type", "string")
                            put("description", "Package name (bundle ID) from the INSTALLED APPS list. Must be exact match like com.google.android.GoogleCamera")
                        })
                        put("text", JSONObject().apply {
                            put("type", "string")
                            put("description", "Text to tap on or type")
                        })
                        put("field", JSONObject().apply {
                            put("type", "string")
                            put("description", "Field to type into (for type action)")
                        })
                    })
                    put("additionalProperties", false)
                    put("description", "Parameters for the action")
                })
            })
            put("required", JSONArray().apply {
                put("action")
            })
            put("additionalProperties", false)
        }

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
            // Add output_format for structured output
            put("output_format", JSONObject().apply {
                put("type", "json_schema")
                put("schema", responseSchema)
            })
        }

        return jsonBody.toString()
    }
}
