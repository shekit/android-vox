package com.vox.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VoxMain"
        private const val PREFS_NAME = "VoxPrefs"
        private const val KEY_API_KEY = "claude_api_key"
    }

    private lateinit var editApiKey: EditText
    private lateinit var buttonSaveApiKey: Button
    private lateinit var editCommand: EditText
    private lateinit var buttonAskClaude: Button
    private lateinit var textResponse: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        setContentView(R.layout.activity_main)

        editApiKey = findViewById(R.id.editApiKey)
        buttonSaveApiKey = findViewById(R.id.buttonSaveApiKey)
        editCommand = findViewById(R.id.editCommand)
        buttonAskClaude = findViewById(R.id.buttonAskClaude)
        textResponse = findViewById(R.id.textResponse)

        // P6.2: Load saved API key
        loadApiKey()

        // P6.2: Save API key button
        buttonSaveApiKey.setOnClickListener {
            val apiKey = editApiKey.text.toString().trim()
            if (apiKey.isNotEmpty()) {
                saveApiKey(apiKey)
                textResponse.text = "API key saved successfully"
                Log.d(TAG, "API key saved")
            } else {
                textResponse.text = "Please enter an API key"
            }
        }

        // P7.2, P7.3, P7.4: Ask Claude button - runs autonomous command loop
        buttonAskClaude.setOnClickListener {
            val command = editCommand.text.toString().trim()
            Log.d(TAG, "Ask Claude button clicked with command: $command")

            if (command.isEmpty()) {
                textResponse.text = "Please enter a command"
                return@setOnClickListener
            }

            val apiKey = getApiKey()
            if (apiKey.isNullOrEmpty()) {
                textResponse.text = "Please save your API key first"
                return@setOnClickListener
            }

            val service = VoxAccessibilityService.getInstance()
            if (service == null) {
                textResponse.text = "Accessibility service not running. Please enable it in Settings."
                return@setOnClickListener
            }

            // Get current UI tree
            val uiTree = VoxAccessibilityService.getLatestTree()
            if (uiTree.isEmpty()) {
                textResponse.text = "No UI tree available. Open another app first."
                return@setOnClickListener
            }

            editCommand.text.clear()

            // Get installed apps list for Claude
            val installedApps = service.getInstalledApps()
            Log.d(TAG, "Found ${installedApps.size} installed apps")

            // P7.2: Start the command loop
            runCommandLoop(command, apiKey, service, installedApps = installedApps)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
    }

    // P6.2: API key storage functions
    private fun saveApiKey(apiKey: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
        Log.d(TAG, "API key saved to SharedPreferences")
    }

    private fun loadApiKey() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_API_KEY, "")
        if (!savedKey.isNullOrEmpty()) {
            editApiKey.setText(savedKey)
            Log.d(TAG, "API key loaded from SharedPreferences")
        }
    }

    fun getApiKey(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, null)
    }

    // P7.2, P7.3, P7.4: Run autonomous command loop
    private fun runCommandLoop(
        userCommand: String,
        apiKey: String,
        service: VoxAccessibilityService,
        stepNumber: Int = 1,
        statusLog: String = "",
        actionHistory: String = "",
        previousUiTreeHash: Int = 0,
        installedApps: Map<String, String> = emptyMap()
    ) {
        Log.d(TAG, "Command loop step $stepNumber")

        // P7.3: Update status
        val status = if (statusLog.isEmpty()) {
            "Starting: $userCommand\n"
        } else {
            statusLog
        }
        textResponse.text = status + "Step $stepNumber: Analyzing..."

        // Get current UI tree
        val uiTree = VoxAccessibilityService.getLatestTree()
        if (uiTree.isEmpty()) {
            // P7.4: Error handling
            val errorMsg = status + "ERROR: No UI tree available"
            textResponse.text = errorMsg
            Log.e(TAG, "Command loop failed: no UI tree")
            return
        }

        // Compare UI tree with previous to detect changes
        val currentUiTreeHash = uiTree.hashCode()
        val uiChanged = previousUiTreeHash == 0 || currentUiTreeHash != previousUiTreeHash
        val uiChangeInfo = if (stepNumber > 1) {
            if (uiChanged) {
                "\n[UI_FEEDBACK: The screen changed after the last action]"
            } else {
                "\n[UI_FEEDBACK: The screen did NOT change after the last action - the action may have completed silently or had no visible effect]"
            }
        } else ""
        Log.d(TAG, "UI tree hash: $currentUiTreeHash, previous: $previousUiTreeHash, changed: $uiChanged")

        // Send request to Claude with UI change feedback
        val historyWithFeedback = actionHistory + uiChangeInfo
        val client = ClaudeApiClient(apiKey)
        client.sendRequest(userCommand, uiTree, historyWithFeedback, installedApps) { success, response, error ->
            runOnUiThread {
                if (success && response != null) {
                    // Parse Claude response
                    val parsed = ClaudeResponseParser.parseResponse(response)
                    if (parsed.success) {
                        val action = parsed.action.trim()
                        Log.d(TAG, "Step $stepNumber - Claude says: $action")

                        // P7.3: Update status with action
                        val updatedStatus = status + "Step $stepNumber: $action\n"
                        textResponse.text = updatedStatus

                        // P7.2: Check if done
                        if (action.equals("done", ignoreCase = true)) {
                            textResponse.text = updatedStatus + "\nTask complete!"
                            Log.d(TAG, "Command loop finished - task complete")

                            // Return to android-vox to show completion message
                            android.os.Handler(mainLooper).postDelayed({
                                val intent = Intent(this, MainActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                startActivity(intent)
                            }, 1000)
                            return@runOnUiThread
                        }

                        // P7.1: Execute the action
                        android.os.Handler(mainLooper).postDelayed({
                            // P7.4: Error handling for action execution
                            try {
                                val result = executeAction(action, service)
                                Log.d(TAG, "Step $stepNumber - Execution result: $result")

                                // Check if action failed
                                if (result.contains("failed", ignoreCase = true) ||
                                    result.contains("could not", ignoreCase = true) ||
                                    result.contains("error", ignoreCase = true)) {
                                    textResponse.text = updatedStatus + "Result: $result\n\nERROR: Action failed"
                                    Log.e(TAG, "Action failed: $result")
                                    return@postDelayed
                                }

                                // Wait for UI change event (or timeout), then continue loop
                                VoxAccessibilityService.waitForUiChange(3000) {
                                    runOnUiThread {
                                        // Add current action to history with execution result
                                        val actionWithResult = "Step $stepNumber: $action → SUCCESS"
                                        val updatedHistory = if (actionHistory.isEmpty()) {
                                            actionWithResult
                                        } else {
                                            "$actionHistory\n$actionWithResult"
                                        }
                                        // Pass current UI tree hash for comparison in next iteration
                                        runCommandLoop(userCommand, apiKey, service, stepNumber + 1, updatedStatus, updatedHistory, currentUiTreeHash, installedApps)
                                    }
                                }

                            } catch (e: Exception) {
                                // P7.4: Error handling
                                val errorMsg = updatedStatus + "ERROR: ${e.message}"
                                textResponse.text = errorMsg
                                Log.e(TAG, "Action execution exception", e)
                            }
                        }, 500) // Small delay before executing action

                    } else {
                        // P7.4: Error handling for parse failure
                        val errorMsg = status + "ERROR: Failed to parse Claude response - ${parsed.rawText}"
                        textResponse.text = errorMsg
                        Log.e(TAG, "Parse error: ${parsed.rawText}")
                    }
                } else {
                    // P7.4: Error handling for API failure
                    val errorMsg = status + "ERROR: API request failed - $error"
                    textResponse.text = errorMsg
                    Log.e(TAG, "API error: $error")
                }
            }
        }
    }

    // P7.1: Execute action from Claude's response
    private fun executeAction(action: String, service: VoxAccessibilityService): String {
        Log.d(TAG, "Executing action: $action")

        return when {
            action.equals("done", ignoreCase = true) -> {
                "Task complete"
            }
            action.equals("back", ignoreCase = true) -> {
                val success = service.pressBack()
                if (success) "Pressed back button" else "Back action failed"
            }
            action.equals("home", ignoreCase = true) -> {
                val success = service.pressHome()
                if (success) "Pressed home button" else "Home action failed"
            }
            action.startsWith("tap ", ignoreCase = true) -> {
                val text = action.substring(4).trim()
                val success = service.tapByText(text)
                if (success) "Tapped '$text'" else "Could not find/tap '$text'"
            }
            action.startsWith("type ", ignoreCase = true) -> {
                val parts = action.substring(5).split(" into ", limit = 2)
                if (parts.size == 2) {
                    val textToType = parts[0].trim()
                    val nodeText = parts[1].trim()
                    val success = service.typeTextByText(nodeText, textToType)
                    if (success) "Typed '$textToType' into '$nodeText'" else "Could not type into '$nodeText'"
                } else {
                    "Invalid type command format. Expected: type <text> into <field>"
                }
            }
            action.startsWith("launch ", ignoreCase = true) -> {
                val appOrPackage = action.substring(7).trim()
                // If it looks like a package name (contains dots), use directly; otherwise try to resolve by name
                val packageName = if (appOrPackage.contains(".")) {
                    appOrPackage
                } else {
                    service.findAppByName(appOrPackage) ?: appOrPackage
                }
                val success = service.launchApp(packageName)
                if (success) "Launched $packageName" else "Could not launch $packageName"
            }
            action.equals("scroll down", ignoreCase = true) || action.equals("scroll", ignoreCase = true) -> {
                val success = service.scrollForwardInActiveWindow()
                if (success) "Scrolled down" else "Could not scroll"
            }
            action.equals("scroll up", ignoreCase = true) -> {
                val success = service.scrollBackwardInActiveWindow()
                if (success) "Scrolled up" else "Could not scroll up"
            }
            action.equals("enter", ignoreCase = true) -> {
                val success = service.pressEnter()
                if (success) "Pressed Enter key" else "Enter action failed"
            }
            else -> {
                "Unknown action: $action"
            }
        }
    }
}
