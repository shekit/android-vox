package com.vox.android.core.interfaces

import com.vox.android.core.models.Action
import com.vox.android.core.models.ActionResult
import com.vox.android.core.models.SwipeDirection

/**
 * Interface for executing actions on the device.
 * Implementations provide the acting capabilities.
 */
interface ActionExecutor {
    /**
     * Execute an action and return the result.
     */
    suspend fun execute(action: Action): ActionResult

    /**
     * Launch an app by package name.
     */
    fun launchApp(packageName: String): Boolean

    /**
     * Tap on an element by text.
     */
    fun tapByText(text: String): Boolean

    /**
     * Long press on an element by text.
     */
    fun longPressByText(text: String): Boolean

    /**
     * Tap at specific screen coordinates.
     */
    fun tapAtCoordinates(x: Int, y: Int): Boolean

    /**
     * Swipe in a direction.
     */
    fun swipe(direction: SwipeDirection): Boolean

    /**
     * Type text into a field.
     */
    fun typeText(field: String, text: String): Boolean

    /**
     * Scroll in the active window.
     */
    fun scroll(forward: Boolean): Boolean

    /**
     * Press the back button.
     */
    fun pressBack(): Boolean

    /**
     * Press the home button.
     */
    fun pressHome(): Boolean

    /**
     * Press the enter/submit key.
     */
    fun pressEnter(): Boolean
}
