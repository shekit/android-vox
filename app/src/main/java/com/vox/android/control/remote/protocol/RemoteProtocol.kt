package com.vox.android.control.remote.protocol

import com.vox.android.core.models.*
import com.vox.android.orchestration.ExecutionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * Protocol for remote control communication.
 */

// ========== Commands (Client -> Server) ==========

@Serializable
sealed class RemoteCommand {
    abstract val id: String

    /** Execute a high-level task (uses AI to decide actions) */
    @Serializable
    data class ExecuteTask(
        override val id: String,
        val task: String
    ) : RemoteCommand()

    /** Execute a specific action directly */
    @Serializable
    data class ExecuteAction(
        override val id: String,
        val action: String,  // Action command string like "tap Settings"
        val parameters: Map<String, String> = emptyMap()
    ) : RemoteCommand()

    /** Get current device state */
    @Serializable
    data class GetState(
        override val id: String,
        val includeScreenshot: Boolean = false
    ) : RemoteCommand()

    /** Get screenshot only */
    @Serializable
    data class GetScreenshot(
        override val id: String
    ) : RemoteCommand()

    /** Get UI tree only */
    @Serializable
    data class GetUITree(
        override val id: String
    ) : RemoteCommand()

    /** Cancel current execution */
    @Serializable
    data class Cancel(
        override val id: String
    ) : RemoteCommand()

    /** Ping for health check */
    @Serializable
    data class Ping(
        override val id: String
    ) : RemoteCommand()

    companion object {
        fun fromJson(json: String): RemoteCommand? {
            return try {
                val obj = JSONObject(json)
                val id = obj.getString("id")
                val type = obj.getString("type")

                when (type) {
                    "execute_task" -> ExecuteTask(id, obj.getString("task"))
                    "execute_action" -> {
                        val action = obj.getString("action")
                        val params = obj.optJSONObject("parameters")?.let { p ->
                            p.keys().asSequence().associateWith { p.getString(it) }
                        } ?: emptyMap()
                        ExecuteAction(id, action, params)
                    }
                    "get_state" -> GetState(id, obj.optBoolean("include_screenshot", false))
                    "get_screenshot" -> GetScreenshot(id)
                    "get_ui_tree" -> GetUITree(id)
                    "cancel" -> Cancel(id)
                    "ping" -> Ping(id)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

// ========== Responses (Server -> Client) ==========

sealed class RemoteResponse {
    abstract val id: String
    abstract val success: Boolean

    /** Task/action execution started */
    data class ExecutionStarted(
        override val id: String,
        val task: String
    ) : RemoteResponse() {
        override val success = true
    }

    /** Progress update during execution */
    data class ExecutionProgress(
        override val id: String,
        val step: Int,
        val action: String,
        val status: String
    ) : RemoteResponse() {
        override val success = true
    }

    /** Execution completed */
    data class ExecutionCompleted(
        override val id: String,
        val steps: Int,
        val message: String
    ) : RemoteResponse() {
        override val success = true
    }

    /** Execution failed */
    data class ExecutionFailed(
        override val id: String,
        val error: String,
        val steps: Int
    ) : RemoteResponse() {
        override val success = false
    }

    /** Action executed directly */
    data class ActionResult(
        override val id: String,
        override val success: Boolean,
        val action: String,
        val message: String
    ) : RemoteResponse()

    /** Device state response */
    data class State(
        override val id: String,
        val uiTree: String,
        val screenshot: String?,
        val foregroundPackage: String,
        val timestamp: Long
    ) : RemoteResponse() {
        override val success = true
    }

    /** Screenshot response */
    data class Screenshot(
        override val id: String,
        val data: String?,  // base64
        val timestamp: Long
    ) : RemoteResponse() {
        override val success = data != null
    }

    /** UI tree response */
    data class UITreeResponse(
        override val id: String,
        val tree: String,
        val nodeCount: Int,
        val foregroundPackage: String
    ) : RemoteResponse() {
        override val success = true
    }

    /** Pong response */
    data class Pong(
        override val id: String
    ) : RemoteResponse() {
        override val success = true
    }

    /** Error response */
    data class Error(
        override val id: String,
        val error: String
    ) : RemoteResponse() {
        override val success = false
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("success", success)

        when (this) {
            is ExecutionStarted -> {
                json.put("type", "execution_started")
                json.put("task", task)
            }
            is ExecutionProgress -> {
                json.put("type", "execution_progress")
                json.put("step", step)
                json.put("action", action)
                json.put("status", status)
            }
            is ExecutionCompleted -> {
                json.put("type", "execution_completed")
                json.put("steps", steps)
                json.put("message", message)
            }
            is ExecutionFailed -> {
                json.put("type", "execution_failed")
                json.put("error", error)
                json.put("steps", steps)
            }
            is ActionResult -> {
                json.put("type", "action_result")
                json.put("action", action)
                json.put("message", message)
            }
            is State -> {
                json.put("type", "state")
                json.put("ui_tree", uiTree)
                json.put("screenshot", screenshot)
                json.put("foreground_package", foregroundPackage)
                json.put("timestamp", timestamp)
            }
            is Screenshot -> {
                json.put("type", "screenshot")
                json.put("data", data)
                json.put("timestamp", timestamp)
            }
            is UITreeResponse -> {
                json.put("type", "ui_tree")
                json.put("tree", tree)
                json.put("node_count", nodeCount)
                json.put("foreground_package", foregroundPackage)
            }
            is Pong -> {
                json.put("type", "pong")
            }
            is Error -> {
                json.put("type", "error")
                json.put("error", error)
            }
        }

        return json.toString()
    }
}

// ========== Events (Server -> Client, unsolicited) ==========

sealed class RemoteEvent {
    /** UI changed */
    data class UIChanged(
        val uiTree: String,
        val foregroundPackage: String,
        val timestamp: Long
    ) : RemoteEvent()

    /** Execution state changed */
    data class StateChanged(
        val state: String,  // ExecutionState as string
        val details: String
    ) : RemoteEvent()

    fun toJson(): String {
        val json = JSONObject()
        json.put("event", true)

        when (this) {
            is UIChanged -> {
                json.put("type", "ui_changed")
                json.put("ui_tree", uiTree)
                json.put("foreground_package", foregroundPackage)
                json.put("timestamp", timestamp)
            }
            is StateChanged -> {
                json.put("type", "state_changed")
                json.put("state", state)
                json.put("details", details)
            }
        }

        return json.toString()
    }
}
