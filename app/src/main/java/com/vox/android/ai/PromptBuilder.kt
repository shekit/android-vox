package com.vox.android.ai

import com.vox.android.core.models.AppInfo
import com.vox.android.core.models.CommandRecord
import com.vox.android.core.models.DeviceState

/**
 * Builds prompts for the AI decision service.
 * Separated for testability and reuse.
 */
class PromptBuilder {

    companion object {
        private const val MAX_APPS_IN_PROMPT = 100
    }

    /**
     * Build the system prompt for the AI.
     */
    fun buildSystemPrompt(
        state: DeviceState,
        history: List<CommandRecord>,
        uiChanged: Boolean
    ): String {
        val actionHistory = buildActionHistory(history, uiChanged)
        val appListText = buildAppList(state.installedApps)

        return """
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
${state.uiTree.toJsonString()}
        """.trimIndent()
    }

    private fun buildActionHistory(history: List<CommandRecord>, uiChanged: Boolean): String {
        if (history.isEmpty()) return ""

        val historyLines = history.joinToString("\n") { it.toHistoryString() }

        val uiFeedback = if (history.isNotEmpty()) {
            if (uiChanged) {
                "\n[UI_FEEDBACK: The screen changed after the last action]"
            } else {
                "\n[UI_FEEDBACK: The screen did NOT change after the last action - the action may have completed silently or had no visible effect]"
            }
        } else ""

        return "\n\nPrevious actions taken:\n$historyLines$uiFeedback"
    }

    private fun buildAppList(installedApps: Map<String, String>): String {
        if (installedApps.isEmpty()) return ""

        val appLines = installedApps.entries
            .sortedBy { it.key.lowercase() }
            .take(MAX_APPS_IN_PROMPT)
            .joinToString("\n") { (name, pkg) -> "- $name: $pkg" }

        return "\n\nINSTALLED APPS (name: package):\n$appLines"
    }

    /**
     * Build the JSON schema for structured responses.
     */
    fun buildResponseSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "enum" to listOf("launch", "tap", "type", "scroll_down", "scroll_up", "back", "home", "enter", "done"),
                    "description" to "The action to perform"
                ),
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "app" to mapOf(
                            "type" to listOf("string", "null"),
                            "description" to "Package name (bundle ID) from the INSTALLED APPS list. Must be exact match like com.google.android.GoogleCamera"
                        ),
                        "text" to mapOf(
                            "type" to listOf("string", "null"),
                            "description" to "Text to tap on or type"
                        ),
                        "field" to mapOf(
                            "type" to listOf("string", "null"),
                            "description" to "Field to type into (for type action)"
                        )
                    ),
                    "required" to listOf("app", "text", "field"),
                    "additionalProperties" to false,
                    "description" to "Parameters for the action"
                )
            ),
            "required" to listOf("action", "parameters"),
            "additionalProperties" to false
        )
    }
}
