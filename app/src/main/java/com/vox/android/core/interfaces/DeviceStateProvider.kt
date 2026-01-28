package com.vox.android.core.interfaces

import com.vox.android.core.models.AppInfo
import com.vox.android.core.models.DeviceState
import com.vox.android.core.models.UITree
import com.vox.android.core.models.Viewport
import kotlinx.coroutines.flow.Flow

/**
 * Interface for capturing device state (UI tree, screenshots, etc.).
 * Implementations provide the sensing capabilities.
 */
interface DeviceStateProvider {
    /**
     * Get the current UI tree of the active window.
     */
    fun getUITree(): UITree

    /**
     * Capture a screenshot of the current screen.
     * Returns base64-encoded JPEG or null if capture fails.
     */
    suspend fun captureScreenshot(): String?

    /**
     * Get list of installed launchable apps.
     */
    fun getInstalledApps(): List<AppInfo>

    /**
     * Get complete device state (UI tree + screenshot + apps).
     */
    suspend fun getDeviceState(includeScreenshot: Boolean = false): DeviceState

    /**
     * Flow of UI change events. Emits when the UI tree changes.
     */
    fun uiChangeEvents(): Flow<UITree>

    /**
     * Check if a package is installed.
     */
    fun isPackageInstalled(packageName: String): Boolean

    /**
     * Find app by name (partial match).
     * Returns the package name or null if not found.
     */
    fun findAppByName(name: String): String?

    /**
     * Get the current viewport (screen dimensions).
     * Used for filtering UI tree to visible elements only.
     */
    fun getViewport(): Viewport
}
