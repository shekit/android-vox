package com.vox.android

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ClaudeApiClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL
) {

    companion object {
        private const val TAG = "ClaudeApiClient"
        private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"

        // Default model - can be changed via constructor
        const val DEFAULT_MODEL = "anthropic/claude-opus-4.5"

        // Available models for easy switching
        // To add/remove models: just edit this list
        val AVAILABLE_MODELS = listOf(
            "anthropic/claude-opus-4.5",
            "anthropic/claude-sonnet-4.5",
            "openai/gpt-4o",
            "google/gemini-2.5-flash"
        )

        // Display names for UI
        val MODEL_DISPLAY_NAMES = mapOf(
            "anthropic/claude-opus-4.5" to "Claude Opus 4.5",
            "anthropic/claude-sonnet-4.5" to "Claude Sonnet 4.5",
            "openai/gpt-4o" to "GPT-4o",
            "google/gemini-2.5-flash" to "Gemini 2.5 Flash"
        )
    }

    private val client = OkHttpClient()
    private val mediaType = "application/json".toMediaType()

    // P6.3: Send request to Claude API
    fun sendRequest(
        userCommand: String,
        uiTree: String,
        previousActions: String = "",
        installedApps: Map<String, String> = emptyMap(),
        screenshotBase64: String? = null,
        callback: (success: Boolean, response: String?, error: String?) -> Unit
    ) {
        Log.d(TAG, "Sending request to Claude API")
        Log.d(TAG, "Command: $userCommand")
        Log.d(TAG, "UI tree length: ${uiTree.length} chars")
        Log.d(TAG, "Screenshot: ${if (screenshotBase64 != null) "${screenshotBase64.length} chars" else "none"}")

        val requestBody = buildRequestBody(userCommand, uiTree, previousActions, installedApps, screenshotBase64)
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

    private fun buildRequestBody(userCommand: String, uiTree: String, previousActions: String, installedApps: Map<String, String>, screenshotBase64: String?): String {
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
- Each action in history shows its result: "→ SUCCESS" or "→ FAILED: <reason>"
- After each action, you also see UI feedback about whether the screen changed
- [UI_FEEDBACK: The screen changed...] means your action had a visible effect
- [UI_FEEDBACK: The screen did NOT change...] means either:
  (a) The action completed silently (e.g., taking a photo, sending a message) - if SUCCESS
  (b) The action had no effect - consider trying a different approach
- Use both signals together: SUCCESS + no UI change often means task completed silently
- If an action FAILED, the element was not found - look at the CURRENT UI tree for what's available now
- Do NOT repeat the exact same action - if it succeeded, it already worked; if it failed, try something different

WIDGET TYPE → ACTION RULES (based on className in UI tree):
- EditText, AutoCompleteTextView, SearchView → use "type" to enter/replace text
- Button, ImageButton, MaterialButton → use "tap"
- CheckBox, Switch, RadioButton, ToggleButton → use "tap" to toggle
- TextView, ImageView → use "tap" if clickable, otherwise display-only
- ScrollView, RecyclerView, ListView (isScrollable=true) → use "scroll_down"/"scroll_up"

SCREENSHOT:
- A screenshot of the current screen may be attached above
- Use the screenshot to see visual elements, search results, suggestions, and UI layout
- The UI tree provides text/element info; the screenshot shows what the user sees
- If you see search results or suggestions in the screenshot, tap on the appropriate result

Available actions with JSON format:

1. launch - Launch an app. Use the EXACT package name from the INSTALLED APPS list below.
   {"action": "launch", "parameters": {"app": "com.google.android.GoogleCamera"}}

2. tap - Tap on an element. The "text" MUST be an exact match from the UI tree (text or contentDescription).
   {"action": "tap", "parameters": {"text": "Search"}}

3. type - Type text into an EditText/SearchView (REPLACES current content).
   The "field" MUST be the exact text/contentDescription currently shown in the field.
   {"action": "type", "parameters": {"text": "hello", "field": "Message"}}

4. scroll_down - Scroll down
   {"action": "scroll_down"}

5. scroll_up - Scroll up
   {"action": "scroll_up"}

6. back - Press back button (use sparingly - see BACK BUTTON rules below)
   {"action": "back"}

7. home - Press home button
   {"action": "home"}

8. enter - Press Enter key (to submit forms, URLs)
   {"action": "enter"}

9. done - Task complete or impossible (see TASK COMPLETION rules below)
   {"action": "done"}

BACK BUTTON RULES:
- Do NOT press "back" immediately after important actions (confirmations, bookings, sends)
- After tapping "Confirm", "Book", "Send", "Submit", etc., WAIT to see the result
- Only use "back" to navigate backwards in a flow (e.g., wrong screen), NOT to exit after completing an action
- If pressing "back" accidentally returns you to android-vox, you went too far back

TASK COMPLETION:
- For booking tasks (Uber, flights, etc.): Task is done when you see confirmation of the booking (driver assigned, booking confirmed), NOT just after tapping "Confirm"
- For messaging tasks: Task is done when the message is sent/delivered
- For camera tasks: Task is done when the photo/video is captured
- If you end up back at android-vox's UI unexpectedly, the task may NOT be complete - consider if more steps are needed
- Say "done" only when you have EVIDENCE the task succeeded (confirmation screen, success message, etc.)

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
                            put("type", JSONArray().apply { put("string"); put("null") })
                            put("description", "Package name (bundle ID) from the INSTALLED APPS list. Must be exact match like com.google.android.GoogleCamera")
                        })
                        put("text", JSONObject().apply {
                            put("type", JSONArray().apply { put("string"); put("null") })
                            put("description", "Text to tap on or type")
                        })
                        put("field", JSONObject().apply {
                            put("type", JSONArray().apply { put("string"); put("null") })
                            put("description", "Field to type into (for type action)")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("app")
                        put("text")
                        put("field")
                    })
                    put("additionalProperties", false)
                    put("description", "Parameters for the action")
                })
            })
            put("required", JSONArray().apply {
                put("action")
                put("parameters")
            })
            put("additionalProperties", false)
        }

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
                        // Add screenshot image if provided (OpenAI format)
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
                            put("text", userCommand)
                        })
                    })
                })
            })
            // Add response_format for structured output (OpenRouter format)
            put("response_format", JSONObject().apply {
                put("type", "json_schema")
                put("json_schema", JSONObject().apply {
                    put("name", "action_response")
                    put("strict", true)
                    put("schema", responseSchema)
                })
            })
        }

        return jsonBody.toString()
    }
}
