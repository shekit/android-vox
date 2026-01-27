package com.vox.android.ai

import android.util.Log
import com.vox.android.core.interfaces.ActionDecisionResult
import com.vox.android.core.interfaces.ActionDecisionService
import com.vox.android.core.models.Action
import com.vox.android.core.models.CommandRecord
import com.vox.android.core.models.DeviceState
import com.vox.android.core.models.ScrollDirection
import org.json.JSONObject

/**
 * Implementation of ActionDecisionService using Claude via OpenRouter.
 */
class ClaudeDecisionService(
    private val apiKey: String,
    private val model: String = OpenRouterClient.DEFAULT_MODEL
) : ActionDecisionService {

    private val client = OpenRouterClient(apiKey, model)
    private val promptBuilder = PromptBuilder()

    override suspend fun decideAction(
        command: String,
        state: DeviceState,
        history: List<CommandRecord>,
        uiChanged: Boolean
    ): ActionDecisionResult {
        return try {
            val systemPrompt = promptBuilder.buildSystemPrompt(state, history, uiChanged)
            val responseSchema = promptBuilder.buildResponseSchema()

            Log.d(TAG, "Requesting action decision for: $command")

            val response = client.sendChatRequest(
                systemPrompt = systemPrompt,
                userMessage = command,
                screenshotBase64 = state.screenshotBase64,
                responseSchema = responseSchema
            )

            Log.d(TAG, "Raw response: $response")

            val action = parseActionFromResponse(response)
            if (action != null) {
                Log.d(TAG, "Decided action: ${action.toCommandString()}")
                ActionDecisionResult.Success(action)
            } else {
                ActionDecisionResult.Failure("Failed to parse action from response")
            }
        } catch (e: ApiException) {
            Log.e(TAG, "API error during decision", e)
            ActionDecisionResult.Failure("API error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during decision", e)
            ActionDecisionResult.Failure("Error: ${e.message}")
        }
    }

    private fun parseActionFromResponse(response: String): Action? {
        return try {
            val json = JSONObject(response)
            val actionType = json.getString("action")
            val parameters = json.optJSONObject("parameters")

            when (actionType) {
                "launch" -> {
                    val app = parameters?.optString("app")
                    if (!app.isNullOrEmpty()) Action.Launch(app) else null
                }
                "tap" -> {
                    val text = parameters?.optString("text")
                    if (!text.isNullOrEmpty()) Action.Tap(text) else null
                }
                "type" -> {
                    val text = parameters?.optString("text")
                    val field = parameters?.optString("field")
                    if (!text.isNullOrEmpty() && !field.isNullOrEmpty()) {
                        Action.Type(text, field)
                    } else null
                }
                "scroll_down" -> Action.Scroll(ScrollDirection.DOWN)
                "scroll_up" -> Action.Scroll(ScrollDirection.UP)
                "back" -> Action.Back
                "home" -> Action.Home
                "enter" -> Action.Enter
                "done" -> Action.Done
                else -> {
                    Log.w(TAG, "Unknown action type: $actionType")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing action response", e)
            null
        }
    }

    companion object {
        private const val TAG = "Vox"

        /**
         * Create a decision service with the given configuration.
         */
        fun create(apiKey: String, model: String = OpenRouterClient.DEFAULT_MODEL): ClaudeDecisionService {
            return ClaudeDecisionService(apiKey, model)
        }
    }
}
