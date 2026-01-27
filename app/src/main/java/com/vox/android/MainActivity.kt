package com.vox.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Vox"
        private const val PREFS_NAME = "VoxPrefs"
        private const val KEY_API_KEY = "claude_api_key"
        private const val KEY_SELECTED_MODEL = "selected_model"
    }

    private lateinit var editApiKey: EditText
    private lateinit var buttonSaveApiKey: Button
    private lateinit var spinnerModel: Spinner
    private lateinit var editCommand: EditText
    private lateinit var buttonAskClaude: Button
    private lateinit var textResponse: TextView

    // Flag to cancel command loop when activity is destroyed
    @Volatile
    private var isLoopActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        setContentView(R.layout.activity_main)

        editApiKey = findViewById(R.id.editApiKey)
        buttonSaveApiKey = findViewById(R.id.buttonSaveApiKey)
        spinnerModel = findViewById(R.id.spinnerModel)
        editCommand = findViewById(R.id.editCommand)
        buttonAskClaude = findViewById(R.id.buttonAskClaude)
        textResponse = findViewById(R.id.textResponse)

        // Set up model selector spinner
        setupModelSpinner()

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
            isLoopActive = true
            runCommandLoop(command, apiKey, service, installedApps = installedApps)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy - cancelling command loop")
        isLoopActive = false
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

    // Model selection functions
    private fun setupModelSpinner() {
        val models = ClaudeApiClient.AVAILABLE_MODELS
        val displayNames = models.map { ClaudeApiClient.MODEL_DISPLAY_NAMES[it] ?: it }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModel.adapter = adapter

        // Restore saved selection
        val savedModel = getSavedModel()
        val savedIndex = models.indexOf(savedModel)
        if (savedIndex >= 0) {
            spinnerModel.setSelection(savedIndex)
        }

        // Save selection when changed
        spinnerModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                saveSelectedModel(models[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun getSelectedModel(): String {
        val position = spinnerModel.selectedItemPosition
        return if (position >= 0 && position < ClaudeApiClient.AVAILABLE_MODELS.size) {
            ClaudeApiClient.AVAILABLE_MODELS[position]
        } else {
            ClaudeApiClient.DEFAULT_MODEL
        }
    }

    private fun saveSelectedModel(model: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_MODEL, model).apply()
    }

    private fun getSavedModel(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_MODEL, ClaudeApiClient.DEFAULT_MODEL) ?: ClaudeApiClient.DEFAULT_MODEL
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

        // Check if loop was cancelled (e.g., activity destroyed)
        if (!isLoopActive) {
            Log.d(TAG, "Command loop cancelled - activity no longer active")
            return
        }

        // Safety: limit maximum steps to prevent runaway loops
        val maxSteps = 20
        if (stepNumber > maxSteps) {
            isLoopActive = false
            textResponse.text = statusLog + "\nStopped: Reached maximum of $maxSteps steps"
            Log.w(TAG, "Command loop stopped: exceeded max steps")
            return
        }

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
            isLoopActive = false
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
        val selectedModel = getSelectedModel()
        val client = ClaudeApiClient(apiKey, selectedModel)

        // Capture screenshot on step 2+ (after we've navigated to target app)
        val sendApiRequest: (String?) -> Unit = { screenshotBase64 ->
            client.sendRequest(userCommand, uiTree, historyWithFeedback, installedApps, screenshotBase64) { success, response, error ->
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
                            isLoopActive = false
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
                            // Check if loop was cancelled before executing
                            if (!isLoopActive) {
                                Log.d(TAG, "Command loop cancelled before action execution")
                                return@postDelayed
                            }

                            // P7.4: Error handling for action execution
                            try {
                                val result = executeAction(action, service)
                                Log.d(TAG, "Step $stepNumber - Execution result: $result")

                                // Check if action failed - pass failure as feedback to Claude
                                val actionFailed = result.contains("failed", ignoreCase = true) ||
                                    result.contains("could not", ignoreCase = true) ||
                                    result.contains("error", ignoreCase = true)

                                if (actionFailed) {
                                    Log.w(TAG, "Action failed: $result - passing feedback to Claude")
                                }

                                // Wait for UI change event (or timeout), then continue loop
                                VoxAccessibilityService.waitForUiChange(3000) {
                                    runOnUiThread {
                                        // Check if loop was cancelled before continuing
                                        if (!isLoopActive) {
                                            Log.d(TAG, "Command loop cancelled before next iteration")
                                            return@runOnUiThread
                                        }

                                        // Add current action to history with execution result
                                        val resultLabel = if (actionFailed) "FAILED: $result" else "SUCCESS"
                                        val actionWithResult = "Step $stepNumber: $action → $resultLabel"
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
                                isLoopActive = false
                                val errorMsg = updatedStatus + "ERROR: ${e.message}"
                                textResponse.text = errorMsg
                                Log.e(TAG, "Action execution exception", e)
                            }
                        }, 500) // Small delay before executing action

                    } else {
                        // P7.4: Error handling for parse failure
                        isLoopActive = false
                        val errorMsg = status + "ERROR: Failed to parse Claude response - ${parsed.rawText}"
                        textResponse.text = errorMsg
                        Log.e(TAG, "Parse error: ${parsed.rawText}")
                    }
                } else {
                    // P7.4: Error handling for API failure
                    isLoopActive = false
                    val errorMsg = status + "ERROR: API request failed - $error"
                    textResponse.text = errorMsg
                    Log.e(TAG, "API error: $error")
                }
            }
        }
        }

        // Capture screenshot on step 2+ (Android 11+), or send without screenshot
        if (stepNumber > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.captureScreenshot { screenshot ->
                sendApiRequest(screenshot)
            }
        } else {
            sendApiRequest(null)
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
