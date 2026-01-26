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
    private lateinit var buttonSend: Button
    private lateinit var buttonAskClaude: Button
    private lateinit var buttonCapture: Button
    private lateinit var buttonLaunchApp: Button
    private lateinit var textResponse: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        setContentView(R.layout.activity_main)

        editApiKey = findViewById(R.id.editApiKey)
        buttonSaveApiKey = findViewById(R.id.buttonSaveApiKey)
        editCommand = findViewById(R.id.editCommand)
        buttonSend = findViewById(R.id.buttonSend)
        buttonAskClaude = findViewById(R.id.buttonAskClaude)
        buttonCapture = findViewById(R.id.buttonCapture)
        buttonLaunchApp = findViewById(R.id.buttonLaunchApp)
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

            // P7.2: Start the command loop
            runCommandLoop(command, apiKey, service)
        }

        // P5.7: Action input in test UI
        buttonSend.setOnClickListener {
            val command = editCommand.text.toString().trim()
            Log.d(TAG, "Send button clicked with command: $command")

            val service = VoxAccessibilityService.getInstance()
            if (service == null) {
                textResponse.text = "Accessibility service not running. Please enable it in Settings."
                editCommand.text.clear()
                return@setOnClickListener
            }

            // Parse and execute commands
            val result = when {
                command.equals("back", ignoreCase = true) -> {
                    val success = service.pressBack()
                    if (success) "Pressed back button" else "Back action failed"
                }
                command.equals("home", ignoreCase = true) -> {
                    val success = service.pressHome()
                    if (success) "Pressed home button" else "Home action failed"
                }
                command.startsWith("tap ", ignoreCase = true) -> {
                    val text = command.substring(4).trim()
                    val success = service.tapByText(text)
                    if (success) "Tapped '$text'" else "Could not find/tap '$text'"
                }
                command.startsWith("type ", ignoreCase = true) -> {
                    val parts = command.substring(5).split(" into ", limit = 2)
                    if (parts.size == 2) {
                        val textToType = parts[0].trim()
                        val nodeText = parts[1].trim()
                        val success = service.typeTextByText(nodeText, textToType)
                        if (success) "Typed '$textToType' into '$nodeText'" else "Could not type into '$nodeText'"
                    } else {
                        "Usage: type <text> into <field>"
                    }
                }
                command.startsWith("launch ", ignoreCase = true) -> {
                    val appName = command.substring(7).trim()
                    val packageName = when (appName.lowercase()) {
                        "settings" -> "com.android.settings"
                        "messages" -> "com.google.android.apps.messaging"
                        "chrome" -> "com.android.chrome"
                        else -> appName // Assume it's a package name
                    }
                    val success = service.launchApp(packageName)
                    if (success) "Launched $appName" else "Could not launch $appName"
                }
                command.equals("scroll down", ignoreCase = true) || command.equals("scroll", ignoreCase = true) -> {
                    val success = service.scrollForwardInActiveWindow()
                    if (success) "Scrolled down" else "Could not scroll"
                }
                else -> {
                    "Unknown command. Try: tap <text>, launch <app>, back, home, scroll down"
                }
            }

            textResponse.text = result
            editCommand.text.clear()
        }

        // P4.6: Capture button triggers tree fetch
        buttonCapture.setOnClickListener {
            Log.d(TAG, "Capture button clicked")
            val treeJson = VoxAccessibilityService.getLatestTree()
            if (treeJson.isNotEmpty()) {
                // P4.5: Display tree in UI
                textResponse.text = treeJson
                Log.d(TAG, "Displayed tree JSON (${treeJson.length} chars)")
            } else {
                textResponse.text = "No UI tree available yet. Open another app and come back."
                Log.w(TAG, "No tree data available")
            }
        }

        // P5.1: Launch app by package name
        buttonLaunchApp.setOnClickListener {
            Log.d(TAG, "Launch app button clicked")
            val service = VoxAccessibilityService.getInstance()
            if (service != null) {
                val packageName = "com.android.settings"
                val success = service.launchApp(packageName)
                textResponse.text = if (success) {
                    "Launched Settings app (package: $packageName)"
                } else {
                    "Failed to launch Settings app"
                }

                // P5.2, P5.3, P5.5: Test finding, tapping, and scrolling after launching Settings
                if (success) {
                    android.os.Handler(mainLooper).postDelayed({
                        // P5.3: Tap Wi-Fi
                        val tapSuccess = service.tapByText("Wi")
                        if (tapSuccess) {
                            textResponse.text = "Tapped 'Wi' (Wi-Fi) setting"

                            // P5.5: Wait then scroll
                            android.os.Handler(mainLooper).postDelayed({
                                val scrollSuccess = service.scrollForwardInActiveWindow()
                                textResponse.text = if (scrollSuccess) {
                                    "Tapped Wi-Fi, then scrolled down"
                                } else {
                                    "Tapped Wi-Fi but couldn't scroll"
                                }
                            }, 1500)
                        } else {
                            textResponse.text = "Launched Settings but couldn't find/tap 'Wi' node"
                        }
                    }, 1000) // Wait 1 second for Settings to load
                }
            } else {
                textResponse.text = "Accessibility service not running. Please enable it in Settings."
                Log.e(TAG, "Accessibility service not available")
            }
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
        statusLog: String = ""
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

        // Send request to Claude
        val client = ClaudeApiClient(apiKey)
        client.sendRequest(userCommand, uiTree) { success, response, error ->
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

                                // Wait for UI to update, then continue loop
                                android.os.Handler(mainLooper).postDelayed({
                                    runCommandLoop(userCommand, apiKey, service, stepNumber + 1, updatedStatus)
                                }, 1500) // Wait 1.5s for UI to update

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
                val appName = action.substring(7).trim()
                val packageName = when (appName.lowercase()) {
                    "settings" -> "com.android.settings"
                    "messages" -> "com.google.android.apps.messaging"
                    "chrome" -> "com.android.chrome"
                    else -> appName // Assume it's a package name
                }
                val success = service.launchApp(packageName)
                if (success) "Launched $appName" else "Could not launch $appName"
            }
            action.equals("scroll down", ignoreCase = true) || action.equals("scroll", ignoreCase = true) -> {
                val success = service.scrollForwardInActiveWindow()
                if (success) "Scrolled down" else "Could not scroll"
            }
            action.equals("scroll up", ignoreCase = true) -> {
                // Note: scroll up is not implemented in VoxAccessibilityService yet
                "Scroll up not implemented yet"
            }
            else -> {
                "Unknown action: $action"
            }
        }
    }
}
