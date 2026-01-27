package com.vox.android.core.models

/**
 * Records a single action taken during command execution.
 * Used for building action history context for the AI.
 */
data class CommandRecord(
    /** The step number in the execution sequence */
    val stepNumber: Int,
    /** The action that was executed */
    val action: Action,
    /** The result of the action */
    val result: ActionResult,
    /** Whether the UI changed after this action */
    val uiChanged: Boolean,
    /** UI tree hash before the action */
    val uiHashBefore: Int,
    /** UI tree hash after the action */
    val uiHashAfter: Int,
    /** Timestamp when the action was executed */
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Format as history string for AI prompt */
    fun toHistoryString(): String {
        val resultLabel = if (result.isSuccess) "SUCCESS" else "FAILED: ${result.message}"
        return "Step $stepNumber: ${action.toCommandString()} → $resultLabel"
    }
}

/**
 * Represents the user's original command/task.
 */
data class Command(
    /** The original user command text */
    val text: String,
    /** Optional source identifier (local, remote client ID, etc.) */
    val source: String = "local",
    /** Timestamp when the command was received */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Result of a complete command execution (may include multiple steps).
 */
data class CommandResult(
    /** The original command */
    val command: Command,
    /** All action records from execution */
    val records: List<CommandRecord>,
    /** Final status */
    val status: CommandStatus,
    /** Human-readable status message */
    val message: String,
    /** Total execution time in milliseconds */
    val executionTimeMs: Long
) {
    val stepCount: Int get() = records.size
    val isSuccess: Boolean get() = status == CommandStatus.COMPLETED

    companion object {
        fun completed(command: Command, records: List<CommandRecord>, executionTimeMs: Long) =
            CommandResult(command, records, CommandStatus.COMPLETED, "Task completed", executionTimeMs)

        fun failed(command: Command, records: List<CommandRecord>, message: String, executionTimeMs: Long) =
            CommandResult(command, records, CommandStatus.FAILED, message, executionTimeMs)

        fun cancelled(command: Command, records: List<CommandRecord>, executionTimeMs: Long) =
            CommandResult(command, records, CommandStatus.CANCELLED, "Task cancelled", executionTimeMs)

        fun maxStepsReached(command: Command, records: List<CommandRecord>, executionTimeMs: Long) =
            CommandResult(command, records, CommandStatus.MAX_STEPS_REACHED, "Maximum steps reached", executionTimeMs)
    }
}

enum class CommandStatus {
    /** Task completed successfully (AI said "done") */
    COMPLETED,
    /** Task failed (error during execution) */
    FAILED,
    /** Task was cancelled by user */
    CANCELLED,
    /** Reached maximum step limit */
    MAX_STEPS_REACHED
}
