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

            // Extract the text content from Claude's response
            val content = json.getJSONArray("content")
            val textBlock = content.getJSONObject(0)
            val text = textBlock.getString("text").trim()

            Log.d(TAG, "Parsed Claude response: $text")

            ParsedResponse(
                action = text,
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
