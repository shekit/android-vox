package com.vox.android

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vox.android.ai.ClaudeDecisionService
import com.vox.android.ai.OpenRouterClient
import com.vox.android.control.remote.RemoteControlService
import com.vox.android.core.models.Command
import com.vox.android.orchestration.CommandOrchestrator
import com.vox.android.orchestration.ExecutionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main activity - thin UI layer that delegates to CommandOrchestrator.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Vox"
        private const val PREFS_NAME = "VoxPrefs"
        private const val KEY_API_KEY = "claude_api_key"
        private const val KEY_SELECTED_MODEL = "selected_model"
    }

    // MCP mode views
    private lateinit var buttonRemoteControl: Button
    private lateinit var textStatus: TextView
    private lateinit var buttonLocalMode: Button

    // Local mode views
    private lateinit var localModeSection: LinearLayout
    private lateinit var editApiKey: EditText
    private lateinit var buttonSaveApiKey: Button
    private lateinit var spinnerModel: Spinner
    private lateinit var editCommand: EditText
    private lateinit var buttonAskClaude: Button
    private lateinit var textResponse: TextView

    private var orchestrator: CommandOrchestrator? = null
    private var statusLog = StringBuilder()
    private var isLocalMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        setContentView(R.layout.activity_main)

        // Bind MCP mode views
        buttonRemoteControl = findViewById(R.id.buttonRemoteControl)
        textStatus = findViewById(R.id.textStatus)
        buttonLocalMode = findViewById(R.id.buttonLocalMode)

        // Bind local mode views
        localModeSection = findViewById(R.id.localModeSection)
        editApiKey = findViewById(R.id.editApiKey)
        buttonSaveApiKey = findViewById(R.id.buttonSaveApiKey)
        spinnerModel = findViewById(R.id.spinnerModel)
        editCommand = findViewById(R.id.editCommand)
        buttonAskClaude = findViewById(R.id.buttonAskClaude)
        textResponse = findViewById(R.id.textResponse)

        setupModelSpinner()
        loadApiKey()
        updateRemoteControlButton()

        buttonRemoteControl.setOnClickListener {
            toggleRemoteControl()
        }

        buttonLocalMode.setOnClickListener {
            toggleLocalMode()
        }

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

        buttonAskClaude.setOnClickListener {
            executeCommand()
        }

        // Observe execution state
        lifecycleScope.launch {
            VoxAccessibilityService.uiChangeEvents.collectLatest {
                // UI change events can be used for more responsive feedback
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
        updateRemoteControlButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy")
        orchestrator?.cancel()
    }

    // ========== Mode Switching ==========

    private fun toggleLocalMode() {
        isLocalMode = !isLocalMode
        if (isLocalMode) {
            localModeSection.visibility = View.VISIBLE
            buttonLocalMode.text = getString(R.string.button_switch_mcp)
        } else {
            localModeSection.visibility = View.GONE
            buttonLocalMode.text = getString(R.string.button_switch_local)
        }
    }

    // ========== Local Mode: Command Execution ==========

    private fun executeCommand() {
        val command = editCommand.text.toString().trim()
        Log.d(TAG, "Execute command: $command")

        if (command.isEmpty()) {
            textResponse.text = "Please enter a command"
            return
        }

        val apiKey = getApiKey()
        if (apiKey.isNullOrEmpty()) {
            textResponse.text = "Please save your API key first"
            return
        }

        val service = VoxAccessibilityService.getInstance()
        if (service == null) {
            textResponse.text = "Accessibility service not running. Please enable it in Settings."
            return
        }

        if (orchestrator?.isActive == true) {
            textResponse.text = "A command is already running. Please wait."
            return
        }

        // Clear input
        editCommand.text.clear()
        statusLog = StringBuilder("Starting: $command\n")
        textResponse.text = statusLog.toString()
        buttonAskClaude.isEnabled = false

        // Create orchestrator with the new architecture
        val stateProvider = service.getStateProvider()
        val actionExecutor = service.getActionExecutor()
        val selectedModel = getSelectedModel()
        val decisionService = ClaudeDecisionService(apiKey, selectedModel)

        orchestrator = CommandOrchestrator(stateProvider, actionExecutor, decisionService)

        // Execute and observe
        lifecycleScope.launch {
            val commandObj = Command(text = command, source = "local")

            orchestrator!!.execute(commandObj, this).collectLatest { state ->
                updateUIForState(state)
            }
        }
    }

    private fun updateUIForState(state: ExecutionState) {
        when (state) {
            is ExecutionState.Idle -> {
                buttonAskClaude.isEnabled = true
            }
            is ExecutionState.Starting -> {
                statusLog = StringBuilder("Starting: ${state.command.text}\n")
                textResponse.text = statusLog.toString()
            }
            is ExecutionState.Analyzing -> {
                textResponse.text = statusLog.toString() + "Step ${state.stepNumber}: Analyzing..."
            }
            is ExecutionState.Executing -> {
                val line = "Step ${state.stepNumber}: ${state.action.toCommandString()}\n"
                statusLog.append(line)
                textResponse.text = statusLog.toString()
            }
            is ExecutionState.WaitingForUiChange -> {
                textResponse.text = statusLog.toString() + "Waiting for UI..."
            }
            is ExecutionState.Completed -> {
                statusLog.append("\nTask complete!")
                textResponse.text = statusLog.toString()
                buttonAskClaude.isEnabled = true
                // Return to app after completion
                android.os.Handler(mainLooper).postDelayed({
                    bringToFront()
                }, 1000)
            }
            is ExecutionState.Failed -> {
                statusLog.append("\nERROR: ${state.error}")
                textResponse.text = statusLog.toString()
                buttonAskClaude.isEnabled = true
            }
            is ExecutionState.Cancelled -> {
                statusLog.append("\nCancelled")
                textResponse.text = statusLog.toString()
                buttonAskClaude.isEnabled = true
            }
        }
    }

    private fun bringToFront() {
        val intent = android.content.Intent(this, MainActivity::class.java)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(intent)
    }

    // ========== Preferences ==========

    private fun saveApiKey(apiKey: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    private fun loadApiKey() {
        val savedKey = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "")
        if (!savedKey.isNullOrEmpty()) {
            editApiKey.setText(savedKey)
            Log.d(TAG, "API key loaded")
        }
    }

    private fun getApiKey(): String? {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, null)
    }

    private fun setupModelSpinner() {
        val models = OpenRouterClient.AVAILABLE_MODELS
        val displayNames = models.map { OpenRouterClient.MODEL_DISPLAY_NAMES[it] ?: it }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModel.adapter = adapter

        // Restore saved selection
        val savedModel = getSavedModel()
        val savedIndex = models.indexOf(savedModel)
        if (savedIndex >= 0) {
            spinnerModel.setSelection(savedIndex)
        }

        spinnerModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                saveSelectedModel(models[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun getSelectedModel(): String {
        val position = spinnerModel.selectedItemPosition
        return if (position >= 0 && position < OpenRouterClient.AVAILABLE_MODELS.size) {
            OpenRouterClient.AVAILABLE_MODELS[position]
        } else {
            OpenRouterClient.DEFAULT_MODEL
        }
    }

    private fun saveSelectedModel(model: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_MODEL, model)
            .apply()
    }

    private fun getSavedModel(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_MODEL, OpenRouterClient.DEFAULT_MODEL)
            ?: OpenRouterClient.DEFAULT_MODEL
    }

    // ========== Remote Control ==========

    private fun toggleRemoteControl() {
        if (RemoteControlService.isRunning()) {
            RemoteControlService.stop(this)
            textStatus.text = "Server stopped"
        } else {
            val apiKey = getApiKey()
            val model = getSelectedModel()
            RemoteControlService.start(
                context = this,
                port = 8080,
                authToken = null,
                apiKey = apiKey,
                model = model
            )
            textStatus.text = "Server starting on port 8080…"
        }
        // Update button after a short delay to let service state change
        android.os.Handler(mainLooper).postDelayed({
            updateRemoteControlButton()
        }, 500)
    }

    private fun updateRemoteControlButton() {
        if (RemoteControlService.isRunning()) {
            buttonRemoteControl.text = getString(R.string.button_stop_server)
            textStatus.text = "Server running on port 8080"
        } else {
            buttonRemoteControl.text = getString(R.string.button_start_server)
            textStatus.text = ""
        }
    }
}
