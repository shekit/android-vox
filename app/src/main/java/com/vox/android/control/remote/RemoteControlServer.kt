package com.vox.android.control.remote

import android.os.Build
import android.util.Log
import com.vox.android.ai.ClaudeDecisionService
import com.vox.android.control.remote.protocol.RemoteCommand
import com.vox.android.control.remote.protocol.RemoteEvent
import com.vox.android.control.remote.protocol.RemoteResponse
import com.vox.android.core.interfaces.ActionExecutor
import com.vox.android.core.interfaces.DeviceStateProvider
import com.vox.android.core.models.Action
import com.vox.android.core.models.Command
import com.vox.android.orchestration.CommandOrchestrator
import com.vox.android.orchestration.ExecutionState
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket server for remote control.
 */
class RemoteControlServer(
    private val stateProvider: DeviceStateProvider,
    private val actionExecutor: ActionExecutor,
    private val config: RemoteServerConfig = RemoteServerConfig()
) {
    companion object {
        private const val TAG = "Vox"
    }

    private var server: ApplicationEngine? = null
    private val clients = ConcurrentHashMap<String, WebSocketSession>()
    private var orchestrator: CommandOrchestrator? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val isRunning: Boolean get() = server != null
    val clientCount: Int get() = clients.size
    val port: Int get() = config.port

    /**
     * Start the server.
     */
    fun start() {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }

        Log.d(TAG, "Starting remote control server on port ${config.port}")

        server = embeddedServer(Netty, port = config.port) {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(30)
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }

            routing {
                webSocket("/control") {
                    handleClient(this)
                }
            }
        }.start(wait = false)

        Log.d(TAG, "Remote control server started on port ${config.port}")
    }

    /**
     * Stop the server.
     */
    fun stop() {
        Log.d(TAG, "Stopping remote control server")
        orchestrator?.cancel()
        serverScope.cancel()
        server?.stop(1000, 2000)
        server = null
        clients.clear()
        Log.d(TAG, "Remote control server stopped")
    }

    private suspend fun handleClient(session: WebSocketSession) {
        val clientId = java.util.UUID.randomUUID().toString()
        Log.d(TAG, "Client connected: $clientId")

        // Authenticate
        if (config.authToken != null) {
            // Wait for auth message
            val authFrame = withTimeoutOrNull(5000) {
                session.incoming.receive()
            }
            if (authFrame == null || authFrame !is Frame.Text) {
                Log.w(TAG, "Auth timeout for client $clientId")
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Auth required"))
                return
            }
            val authJson = authFrame.readText()
            if (!validateAuth(authJson)) {
                Log.w(TAG, "Auth failed for client $clientId")
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth"))
                return
            }
            Log.d(TAG, "Client $clientId authenticated")
        }

        clients[clientId] = session

        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        Log.d(TAG, "Received from $clientId: ${text.take(200)}")
                        handleCommand(clientId, session, text)
                    }
                    is Frame.Close -> {
                        Log.d(TAG, "Client $clientId disconnected")
                        break
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client $clientId", e)
        } finally {
            clients.remove(clientId)
            Log.d(TAG, "Client $clientId removed")
        }
    }

    private fun validateAuth(authJson: String): Boolean {
        return try {
            val json = org.json.JSONObject(authJson)
            val token = json.optString("token")
            token == config.authToken
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun handleCommand(clientId: String, session: WebSocketSession, json: String) {
        val command = RemoteCommand.fromJson(json)
        if (command == null) {
            sendResponse(session, RemoteResponse.Error("unknown", "Invalid command format"))
            return
        }

        when (command) {
            is RemoteCommand.Ping -> {
                sendResponse(session, RemoteResponse.Pong(command.id))
            }

            is RemoteCommand.GetState -> {
                serverScope.launch {
                    val state = stateProvider.getDeviceState(command.includeScreenshot)
                    sendResponse(session, RemoteResponse.State(
                        id = command.id,
                        uiTree = state.uiTree.toJsonString(),
                        screenshot = state.screenshotBase64,
                        foregroundPackage = state.foregroundPackage,
                        timestamp = state.timestamp
                    ))
                }
            }

            is RemoteCommand.GetScreenshot -> {
                serverScope.launch {
                    val screenshot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        stateProvider.captureScreenshot()
                    } else null
                    sendResponse(session, RemoteResponse.Screenshot(
                        id = command.id,
                        data = screenshot,
                        timestamp = System.currentTimeMillis()
                    ))
                }
            }

            is RemoteCommand.GetUITree -> {
                val tree = stateProvider.getUITree()
                sendResponse(session, RemoteResponse.UITreeResponse(
                    id = command.id,
                    tree = tree.toJsonString(),
                    nodeCount = tree.nodeCount(),
                    foregroundPackage = tree.packageName
                ))
            }

            is RemoteCommand.ExecuteAction -> {
                serverScope.launch {
                    val actionCommand = buildActionCommand(command.action, command.parameters)
                    val action = Action.fromCommandString(actionCommand)
                    if (action == null) {
                        sendResponse(session, RemoteResponse.Error(command.id, "Invalid action: ${command.action}"))
                        return@launch
                    }

                    val result = actionExecutor.execute(action)
                    sendResponse(session, RemoteResponse.ActionResult(
                        id = command.id,
                        success = result.isSuccess,
                        action = action.toCommandString(),
                        message = result.message
                    ))
                }
            }

            is RemoteCommand.ExecuteTask -> {
                serverScope.launch {
                    if (orchestrator?.isActive == true) {
                        sendResponse(session, RemoteResponse.Error(command.id, "Another task is already executing"))
                        return@launch
                    }
                    executeTask(command.id, command.task, session)
                }
            }

            is RemoteCommand.Cancel -> {
                orchestrator?.cancel()
                sendResponse(session, RemoteResponse.ExecutionCompleted(
                    id = command.id,
                    steps = 0,
                    message = "Cancelled"
                ))
            }
        }
    }

    private suspend fun executeTask(id: String, task: String, session: WebSocketSession) {
        if (config.apiKey == null) {
            sendResponse(session, RemoteResponse.Error(id, "No API key configured for AI tasks"))
            return
        }

        val decisionService = ClaudeDecisionService(config.apiKey, config.model)
        orchestrator = CommandOrchestrator(stateProvider, actionExecutor, decisionService)

        sendResponse(session, RemoteResponse.ExecutionStarted(id, task))

        val command = Command(text = task, source = "remote:${session.hashCode()}")

        orchestrator!!.execute(command, serverScope).collectLatest { state ->
            when (state) {
                is ExecutionState.Idle -> {}
                is ExecutionState.Starting -> {}
                is ExecutionState.Analyzing -> {
                    sendResponse(session, RemoteResponse.ExecutionProgress(
                        id = id,
                        step = state.stepNumber,
                        action = "Analyzing...",
                        status = "analyzing"
                    ))
                }
                is ExecutionState.Executing -> {
                    sendResponse(session, RemoteResponse.ExecutionProgress(
                        id = id,
                        step = state.stepNumber,
                        action = state.action.toCommandString(),
                        status = "executing"
                    ))
                }
                is ExecutionState.WaitingForUiChange -> {
                    sendResponse(session, RemoteResponse.ExecutionProgress(
                        id = id,
                        step = state.stepNumber,
                        action = state.action.toCommandString(),
                        status = "waiting"
                    ))
                }
                is ExecutionState.Completed -> {
                    sendResponse(session, RemoteResponse.ExecutionCompleted(
                        id = id,
                        steps = state.result.stepCount,
                        message = state.result.message
                    ))
                }
                is ExecutionState.Failed -> {
                    sendResponse(session, RemoteResponse.ExecutionFailed(
                        id = id,
                        error = state.error,
                        steps = state.records.size
                    ))
                }
                is ExecutionState.Cancelled -> {
                    sendResponse(session, RemoteResponse.ExecutionCompleted(
                        id = id,
                        steps = state.records.size,
                        message = "Cancelled"
                    ))
                }
            }
        }
    }

    private suspend fun sendResponse(session: WebSocketSession, response: RemoteResponse) {
        try {
            session.send(response.toJson())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending response", e)
        }
    }

    private fun buildActionCommand(action: String, parameters: Map<String, String>): String {
        if (parameters.isEmpty()) return action

        return when (action.lowercase()) {
            "launch" -> parameters["app"]?.let { "launch $it" } ?: action
            "tap" -> parameters["text"]?.let { "tap $it" } ?: action
            "long_press" -> parameters["text"]?.let { "long_press $it" } ?: action
            "tap_at" -> {
                val x = parameters["x"]
                val y = parameters["y"]
                if (!x.isNullOrEmpty() && !y.isNullOrEmpty()) {
                    "tap_at $x $y"
                } else {
                    action
                }
            }
            "swipe_left" -> "swipe left"
            "swipe_right" -> "swipe right"
            "swipe_up" -> "swipe up"
            "swipe_down" -> "swipe down"
            "type" -> {
                val text = parameters["text"]
                val field = parameters["field"]
                if (!text.isNullOrEmpty() && !field.isNullOrEmpty()) {
                    "type $text into $field"
                } else {
                    action
                }
            }
            "scroll_down" -> "scroll down"
            "scroll_up" -> "scroll up"
            "back" -> "back"
            "home" -> "home"
            "enter" -> "enter"
            "done" -> "done"
            else -> action
        }
    }

    /**
     * Broadcast an event to all connected clients.
     */
    suspend fun broadcast(event: RemoteEvent) {
        val json = event.toJson()
        clients.values.forEach { session ->
            try {
                session.send(json)
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting to client", e)
            }
        }
    }
}

/**
 * Configuration for the remote control server.
 */
data class RemoteServerConfig(
    /** Port to listen on */
    val port: Int = 8080,
    /** Auth token (null = no auth required) */
    val authToken: String? = null,
    /** API key for AI tasks */
    val apiKey: String? = null,
    /** Model for AI tasks */
    val model: String = "anthropic/claude-opus-4.5"
)
