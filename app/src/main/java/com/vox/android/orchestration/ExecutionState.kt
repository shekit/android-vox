package com.vox.android.orchestration

import com.vox.android.core.models.Action
import com.vox.android.core.models.Command
import com.vox.android.core.models.CommandRecord
import com.vox.android.core.models.CommandResult

/**
 * Represents the current state of command execution.
 * Emitted via Flow to observers (UI, remote clients).
 */
sealed class ExecutionState {
    /** No command is currently executing */
    data object Idle : ExecutionState()

    /** Starting command execution */
    data class Starting(val command: Command) : ExecutionState()

    /** Analyzing the UI to decide next action */
    data class Analyzing(
        val command: Command,
        val stepNumber: Int
    ) : ExecutionState()

    /** Executing an action */
    data class Executing(
        val command: Command,
        val stepNumber: Int,
        val action: Action
    ) : ExecutionState()

    /** Waiting for UI to change after action */
    data class WaitingForUiChange(
        val command: Command,
        val stepNumber: Int,
        val action: Action
    ) : ExecutionState()

    /** Command completed successfully */
    data class Completed(
        val command: Command,
        val result: CommandResult
    ) : ExecutionState()

    /** Command failed */
    data class Failed(
        val command: Command,
        val error: String,
        val records: List<CommandRecord>
    ) : ExecutionState()

    /** Command was cancelled */
    data class Cancelled(
        val command: Command,
        val records: List<CommandRecord>
    ) : ExecutionState()

    /** For UI display */
    fun toStatusString(): String = when (this) {
        Idle -> "Ready"
        is Starting -> "Starting: ${command.text}"
        is Analyzing -> "Step $stepNumber: Analyzing..."
        is Executing -> "Step $stepNumber: ${action.toCommandString()}"
        is WaitingForUiChange -> "Step $stepNumber: Waiting for UI..."
        is Completed -> "Completed (${result.stepCount} steps)"
        is Failed -> "Failed: $error"
        is Cancelled -> "Cancelled"
    }
}
