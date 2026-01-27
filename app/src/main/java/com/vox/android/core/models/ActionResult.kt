package com.vox.android.core.models

/**
 * Sealed class representing the result of executing an action.
 * Pure Kotlin - no Android dependencies.
 */
sealed class ActionResult {
    abstract val action: Action
    abstract val message: String

    /** Action executed successfully */
    data class Success(
        override val action: Action,
        override val message: String = "Success"
    ) : ActionResult()

    /** Action failed to execute */
    data class Failure(
        override val action: Action,
        override val message: String,
        val reason: FailureReason = FailureReason.UNKNOWN
    ) : ActionResult()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    /** Convert to a log-friendly string */
    fun toLogString(): String = when (this) {
        is Success -> "SUCCESS: ${action.toCommandString()} - $message"
        is Failure -> "FAILED: ${action.toCommandString()} - $message"
    }

    companion object {
        fun success(action: Action, message: String = "Success") = Success(action, message)
        fun failure(action: Action, message: String, reason: FailureReason = FailureReason.UNKNOWN) =
            Failure(action, message, reason)
    }
}

enum class FailureReason {
    /** Element not found in UI tree */
    ELEMENT_NOT_FOUND,
    /** Element found but action failed */
    ACTION_FAILED,
    /** App not installed or cannot be launched */
    APP_NOT_FOUND,
    /** Scroll failed (no scrollable container) */
    NOT_SCROLLABLE,
    /** Timeout waiting for result */
    TIMEOUT,
    /** Unknown or unspecified reason */
    UNKNOWN
}
