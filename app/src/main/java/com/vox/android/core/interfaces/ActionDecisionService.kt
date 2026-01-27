package com.vox.android.core.interfaces

import com.vox.android.core.models.Action
import com.vox.android.core.models.CommandRecord
import com.vox.android.core.models.DeviceState

/**
 * Interface for AI-based action decision making.
 * Implementations use LLMs to decide what action to take.
 */
interface ActionDecisionService {
    /**
     * Decide what action to take given the current state and command.
     *
     * @param command The user's original command/task
     * @param state Current device state (UI tree, screenshot, etc.)
     * @param history Previous actions taken in this command execution
     * @param uiChanged Whether the UI changed since the last action
     * @return The action to execute, or null if decision failed
     */
    suspend fun decideAction(
        command: String,
        state: DeviceState,
        history: List<CommandRecord>,
        uiChanged: Boolean
    ): ActionDecisionResult
}

/**
 * Result of an action decision.
 */
sealed class ActionDecisionResult {
    /** Successfully decided on an action */
    data class Success(val action: Action) : ActionDecisionResult()

    /** Failed to decide (API error, parse error, etc.) */
    data class Failure(val error: String) : ActionDecisionResult()

    val isSuccess: Boolean get() = this is Success
    val actionOrNull: Action? get() = (this as? Success)?.action
}
