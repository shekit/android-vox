package com.vox.android

import android.util.Log
import org.json.JSONObject

// P6.4: Parse Claude response
object ClaudeResponseParser {
    private const val TAG = "ClaudeResponseParser"

    data class ParsedResponse(
        val action: String,
        val rawText: String,
        val success: Boolean
    )

    fun parseResponse(responseJson: String): ParsedResponse {
        return try {
            val json = JSONObject(responseJson)

            // Extract the text content from OpenRouter/OpenAI response format
            // Format: { "choices": [{ "message": { "content": "..." } }] }
            val choices = json.getJSONArray("choices")
            val message = choices.getJSONObject(0).getJSONObject("message")
            val text = message.getString("content").trim()

            Log.d(TAG, "Raw LLM response: $text")

            // Parse the structured JSON action
            val actionJson = JSONObject(text)
            val actionType = actionJson.getString("action")
            val parameters = actionJson.optJSONObject("parameters")

            // Convert structured JSON to action command string
            val actionCommand = when (actionType) {
                "launch" -> {
                    val app = parameters?.optString("app") ?: ""
                    "launch $app"
                }
                "tap" -> {
                    val tapText = parameters?.optString("text") ?: ""
                    "tap $tapText"
                }
                "type" -> {
                    val typeText = parameters?.optString("text") ?: ""
                    val field = parameters?.optString("field") ?: ""
                    "type $typeText into $field"
                }
                "scroll_down" -> "scroll down"
                "scroll_up" -> "scroll up"
                "back" -> "back"
                "home" -> "home"
                "enter" -> "enter"
                "done" -> "done"
                else -> {
                    Log.w(TAG, "Unknown action type: $actionType")
                    "done"
                }
            }

            Log.d(TAG, "Parsed action command: $actionCommand")

            ParsedResponse(
                action = actionCommand,
                rawText = text,
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Claude response", e)
            ParsedResponse(
                action = "",
                rawText = "Parse error: ${e.message}",
                success = false
            )
        }
    }
}
