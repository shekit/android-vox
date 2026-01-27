package com.vox.android.core.models

/**
 * Sealed class hierarchy representing all possible actions that can be executed on the device.
 * Pure Kotlin - no Android dependencies.
 */
sealed class Action {
    /** Launch an app by package name */
    data class Launch(val packageName: String) : Action()

    /** Tap on an element identified by text or content description */
    data class Tap(val text: String) : Action()

    /** Long press on an element identified by text or content description */
    data class LongPress(val text: String) : Action()

    /** Tap at specific screen coordinates */
    data class TapAt(val x: Int, val y: Int) : Action()

    /** Swipe in a direction */
    data class Swipe(val direction: SwipeDirection) : Action()

    /** Type text into a field identified by its current text/hint */
    data class Type(val text: String, val field: String) : Action()

    /** Scroll in a direction */
    data class Scroll(val direction: ScrollDirection) : Action()

    /** Press the back button */
    data object Back : Action()

    /** Press the home button */
    data object Home : Action()

    /** Press the enter/submit key */
    data object Enter : Action()

    /** Task is complete */
    data object Done : Action()

    companion object {
        /**
         * Parse an action from a command string (for backward compatibility).
         * Returns null if the command cannot be parsed.
         */
        fun fromCommandString(command: String): Action? {
            val trimmed = command.trim()
            return when {
                trimmed.equals("done", ignoreCase = true) -> Done
                trimmed.equals("back", ignoreCase = true) -> Back
                trimmed.equals("home", ignoreCase = true) -> Home
                trimmed.equals("enter", ignoreCase = true) -> Enter
                trimmed.equals("scroll down", ignoreCase = true) -> Scroll(ScrollDirection.DOWN)
                trimmed.equals("scroll up", ignoreCase = true) -> Scroll(ScrollDirection.UP)
                trimmed.equals("scroll", ignoreCase = true) -> Scroll(ScrollDirection.DOWN)
                trimmed.equals("swipe left", ignoreCase = true) || trimmed.equals("swipe_left", ignoreCase = true) -> Swipe(SwipeDirection.LEFT)
                trimmed.equals("swipe right", ignoreCase = true) || trimmed.equals("swipe_right", ignoreCase = true) -> Swipe(SwipeDirection.RIGHT)
                trimmed.equals("swipe up", ignoreCase = true) || trimmed.equals("swipe_up", ignoreCase = true) -> Swipe(SwipeDirection.UP)
                trimmed.equals("swipe down", ignoreCase = true) || trimmed.equals("swipe_down", ignoreCase = true) -> Swipe(SwipeDirection.DOWN)
                trimmed.startsWith("tap ", ignoreCase = true) -> {
                    Tap(trimmed.substring(4).trim())
                }
                trimmed.startsWith("long_press ", ignoreCase = true) -> {
                    LongPress(trimmed.substring(11).trim())
                }
                trimmed.startsWith("tap_at ", ignoreCase = true) -> {
                    val coords = trimmed.substring(7).trim().split(" ", limit = 2)
                    if (coords.size == 2) {
                        val x = coords[0].toIntOrNull()
                        val y = coords[1].toIntOrNull()
                        if (x != null && y != null) TapAt(x, y) else null
                    } else null
                }
                trimmed.startsWith("type ", ignoreCase = true) -> {
                    val parts = trimmed.substring(5).split(" into ", limit = 2)
                    if (parts.size == 2) {
                        Type(parts[0].trim(), parts[1].trim())
                    } else null
                }
                trimmed.startsWith("launch ", ignoreCase = true) -> {
                    Launch(trimmed.substring(7).trim())
                }
                else -> null
            }
        }
    }

    /**
     * Convert action back to command string (for logging/display).
     */
    fun toCommandString(): String = when (this) {
        is Launch -> "launch $packageName"
        is Tap -> "tap $text"
        is LongPress -> "long_press $text"
        is TapAt -> "tap_at $x $y"
        is Swipe -> when (direction) {
            SwipeDirection.LEFT -> "swipe left"
            SwipeDirection.RIGHT -> "swipe right"
            SwipeDirection.UP -> "swipe up"
            SwipeDirection.DOWN -> "swipe down"
        }
        is Type -> "type $text into $field"
        is Scroll -> when (direction) {
            ScrollDirection.DOWN -> "scroll down"
            ScrollDirection.UP -> "scroll up"
        }
        Back -> "back"
        Home -> "home"
        Enter -> "enter"
        Done -> "done"
    }
}

enum class ScrollDirection {
    UP, DOWN
}

enum class SwipeDirection {
    LEFT, RIGHT, UP, DOWN
}
