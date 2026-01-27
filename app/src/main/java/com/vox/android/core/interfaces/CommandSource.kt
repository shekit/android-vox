package com.vox.android.core.interfaces

import com.vox.android.core.models.Command
import com.vox.android.core.models.CommandResult
import kotlinx.coroutines.flow.Flow

/**
 * Interface for sources of commands.
 * Both local UI and remote WebSocket implement this interface.
 */
interface CommandSource {
    /** Unique identifier for this source */
    val sourceId: String

    /**
     * Flow of incoming commands.
     * Commands arrive from the source (UI button, WebSocket message, etc.)
     */
    fun commands(): Flow<Command>

    /**
     * Send a result back to the source.
     * For local UI, this updates the display.
     * For remote, this sends the result over WebSocket.
     */
    suspend fun sendResult(result: CommandResult)

    /**
     * Send a progress update (step N of execution).
     */
    suspend fun sendProgress(stepNumber: Int, action: String, status: String)

    /**
     * Check if the source is still active/connected.
     */
    fun isActive(): Boolean

    /**
     * Close the source.
     */
    fun close()
}
