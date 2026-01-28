package com.vox.android.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.vox.android.core.interfaces.ActionExecutor
import com.vox.android.core.models.Action
import com.vox.android.core.models.ActionResult
import com.vox.android.core.models.FailureReason
import com.vox.android.core.models.ScrollDirection
import com.vox.android.core.models.SwipeDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Implementation of ActionExecutor using Android Accessibility Service.
 */
class AccessibilityActionExecutor(
    private val service: AccessibilityService,
    private val stateProvider: AccessibilityStateProvider
) : ActionExecutor {

    companion object {
        private const val TAG = "Vox"
    }

    override suspend fun execute(action: Action): ActionResult {
        return when (action) {
            is Action.Launch -> {
                // Only accept exact package names (bundle IDs)
                if (launchApp(action.packageName)) {
                    ActionResult.success(action, "Launched ${action.packageName}")
                } else {
                    ActionResult.failure(action, "Could not launch ${action.packageName}", FailureReason.APP_NOT_FOUND)
                }
            }
            is Action.Tap -> {
                if (tapByText(action.text)) {
                    ActionResult.success(action, "Tapped '${action.text}'")
                } else {
                    ActionResult.failure(action, "Could not find/tap '${action.text}'", FailureReason.ELEMENT_NOT_FOUND)
                }
            }
            is Action.LongPress -> {
                if (longPressByText(action.text)) {
                    ActionResult.success(action, "Long pressed '${action.text}'")
                } else {
                    ActionResult.failure(action, "Could not find/long-press '${action.text}'", FailureReason.ELEMENT_NOT_FOUND)
                }
            }
            is Action.TapAt -> {
                if (tapAtCoordinates(action.x, action.y)) {
                    ActionResult.success(action, "Tapped at (${action.x}, ${action.y})")
                } else {
                    ActionResult.failure(action, "Could not tap at (${action.x}, ${action.y})", FailureReason.ACTION_FAILED)
                }
            }
            is Action.Swipe -> {
                if (swipe(action.direction)) {
                    ActionResult.success(action, "Swiped ${action.direction.name.lowercase()}")
                } else {
                    ActionResult.failure(action, "Could not swipe ${action.direction.name.lowercase()}", FailureReason.ACTION_FAILED)
                }
            }
            is Action.Type -> {
                val fieldDesc = action.field ?: "focused field"
                if (typeText(action.field, action.text)) {
                    ActionResult.success(action, "Typed '${action.text}' into '$fieldDesc'")
                } else {
                    ActionResult.failure(action, "Could not type into '$fieldDesc'", FailureReason.ELEMENT_NOT_FOUND)
                }
            }
            is Action.Append -> {
                val fieldDesc = action.field ?: "focused field"
                if (appendText(action.field, action.text)) {
                    ActionResult.success(action, "Appended '${action.text}' to '$fieldDesc'")
                } else {
                    ActionResult.failure(action, "Could not append to '$fieldDesc'", FailureReason.ELEMENT_NOT_FOUND)
                }
            }
            is Action.Scroll -> {
                val forward = action.direction == ScrollDirection.DOWN
                if (scroll(forward)) {
                    ActionResult.success(action, "Scrolled ${if (forward) "down" else "up"}")
                } else {
                    ActionResult.failure(action, "Could not scroll", FailureReason.NOT_SCROLLABLE)
                }
            }
            Action.Back -> {
                if (pressBack()) {
                    ActionResult.success(action, "Pressed back")
                } else {
                    ActionResult.failure(action, "Back action failed", FailureReason.ACTION_FAILED)
                }
            }
            Action.Home -> {
                if (pressHome()) {
                    ActionResult.success(action, "Pressed home")
                } else {
                    ActionResult.failure(action, "Home action failed", FailureReason.ACTION_FAILED)
                }
            }
            Action.Enter -> {
                if (pressEnter()) {
                    ActionResult.success(action, "Pressed enter")
                } else {
                    ActionResult.failure(action, "Enter action failed", FailureReason.ACTION_FAILED)
                }
            }
            Action.Done -> {
                ActionResult.success(action, "Task complete")
            }
        }
    }

    override fun launchApp(packageName: String): Boolean {
        return try {
            val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(intent)
                Log.d(TAG, "Launched app: $packageName")
                true
            } else {
                Log.e(TAG, "No launch intent for package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app $packageName", e)
            false
        }
    }

    override fun tapByText(text: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        // Find all matching nodes
        val allMatches = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesByTextRecursive(rootNode, text, allMatches)

        if (allMatches.isEmpty()) {
            Log.w(TAG, "Cannot tap: node with text '$text' not found")
            rootNode.recycle()
            return false
        }

        // Prefer non-EditText clickable elements over EditText fields
        val sortedMatches = allMatches.sortedBy { node ->
            val isEditText = node.className?.toString()?.contains("EditText", ignoreCase = true) == true
            val isClickable = node.isClickable || hasClickableParent(node)
            when {
                !isEditText && isClickable -> 0  // Best: non-EditText that's clickable
                !isEditText -> 1                  // Good: non-EditText
                isClickable -> 2                  // OK: EditText that's clickable
                else -> 3                         // Last resort: non-clickable EditText
            }
        }

        // Try to tap the best match
        var success = false
        for (node in sortedMatches) {
            if (tapNode(node)) {
                Log.d(TAG, "Tapped node: ${node.className}, text=${node.text}")
                success = true
                break
            }
        }

        // Clean up
        allMatches.forEach { if (it !== rootNode) it.recycle() }
        rootNode.recycle()
        return success
    }

    private fun hasClickableParent(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                parent.recycle()
                return true
            }
            val next = parent.parent
            parent.recycle()
            parent = next
        }
        return false
    }

    override fun typeText(field: String?, text: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        // If no field specified, find the focused editable field
        if (field == null) {
            val focused = findFocusedEditableNode(rootNode)
            if (focused != null) {
                val success = typeIntoNode(focused, text)
                focused.recycle()
                rootNode.recycle()
                return success
            } else {
                Log.w(TAG, "Cannot type: no focused editable field found")
                rootNode.recycle()
                return false
            }
        }

        // Find the field by text
        val node = findNodeByTextRecursive(rootNode, field)
        if (node == null) {
            Log.w(TAG, "Cannot type: field '$field' not found")
            rootNode.recycle()
            return false
        }

        // Check if editable
        val isEditable = node.isEditable ||
                node.className?.toString()?.contains("EditText", ignoreCase = true) == true

        val success = if (isEditable) {
            typeIntoNode(node, text)
        } else {
            // Try to find focused EditText
            Log.d(TAG, "Found '$field' is not editable, looking for focused input")
            node.recycle()
            val focused = findFocusedEditableNode(rootNode)
            if (focused != null) {
                typeIntoNode(focused, text).also { focused.recycle() }
            } else {
                Log.w(TAG, "No editable node found for '$field'")
                false
            }
        }

        if (node !== rootNode) rootNode.recycle()
        return success
    }

    override fun appendText(field: String?, text: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        // Find the target node
        val targetNode = if (field == null) {
            findFocusedEditableNode(rootNode)
        } else {
            findNodeByTextRecursive(rootNode, field)
        }

        if (targetNode == null) {
            Log.w(TAG, "Cannot append: ${if (field == null) "no focused editable field" else "field '$field' not found"}")
            rootNode.recycle()
            return false
        }

        // Get current text and append
        val currentText = targetNode.text?.toString() ?: ""
        val newText = currentText + text
        val success = typeIntoNode(targetNode, newText)

        if (targetNode !== rootNode) targetNode.recycle()
        rootNode.recycle()
        return success
    }

    override fun scroll(forward: Boolean): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(rootNode)
        val success = if (scrollable != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            scrollable.performAction(action).also {
                Log.d(TAG, "Scrolled ${if (forward) "forward" else "backward"}: $it")
                if (scrollable !== rootNode) scrollable.recycle()
            }
        } else {
            Log.w(TAG, "No scrollable node found")
            false
        }
        rootNode.recycle()
        return success
    }

    override fun pressBack(): Boolean {
        return try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK).also {
                Log.d(TAG, "Pressed back: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing back", e)
            false
        }
    }

    override fun pressHome(): Boolean {
        return try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME).also {
                Log.d(TAG, "Pressed home: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing home", e)
            false
        }
    }

    override fun pressEnter(): Boolean {
        return try {
            // Search all windows for keyboard action buttons
            val allWindows = service.windows
            Log.d(TAG, "Searching ${allWindows.size} windows for keyboard action")

            for (window in allWindows) {
                val windowRoot = window.root ?: continue
                val button = findKeyboardActionButton(windowRoot)
                if (button != null) {
                    val success = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    button.recycle()
                    windowRoot.recycle()
                    if (success) {
                        Log.d(TAG, "Pressed Enter via keyboard button in window: ${window.title}")
                        return true
                    }
                }
                windowRoot.recycle()
            }
            Log.w(TAG, "No keyboard button found in ${allWindows.size} windows")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing enter", e)
            false
        }
    }

    override fun longPressByText(text: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        val node = findNodeByTextRecursive(rootNode, text)
        val success = if (node != null) {
            longPressNode(node).also { node.recycle() }
        } else {
            Log.w(TAG, "Cannot long press: node with text '$text' not found")
            false
        }
        if (node !== rootNode) rootNode.recycle()
        return success
    }

    override fun tapAtCoordinates(x: Int, y: Int): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()

            val latch = CountDownLatch(1)
            var result = false
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result = true
                    Log.d(TAG, "Tap at ($x, $y) completed")
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Tap at ($x, $y) cancelled")
                    latch.countDown()
                }
            }, null)

            // Wait for the callback with timeout
            latch.await(2, TimeUnit.SECONDS)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error tapping at coordinates ($x, $y)", e)
            false
        }
    }

    override fun swipe(direction: SwipeDirection): Boolean {
        return try {
            // Get screen dimensions
            val displayMetrics = service.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f
            val swipeDistance = minOf(screenWidth, screenHeight) / 3f

            val (startX, startY, endX, endY) = when (direction) {
                SwipeDirection.LEFT -> listOf(centerX + swipeDistance, centerY, centerX - swipeDistance, centerY)
                SwipeDirection.RIGHT -> listOf(centerX - swipeDistance, centerY, centerX + swipeDistance, centerY)
                SwipeDirection.UP -> listOf(centerX, centerY + swipeDistance, centerX, centerY - swipeDistance)
                SwipeDirection.DOWN -> listOf(centerX, centerY - swipeDistance, centerX, centerY + swipeDistance)
            }

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()

            val latch = CountDownLatch(1)
            var result = false
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result = true
                    Log.d(TAG, "Swipe ${direction.name.lowercase()} completed")
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Swipe ${direction.name.lowercase()} cancelled")
                    latch.countDown()
                }
            }, null)

            // Wait for the callback with timeout
            latch.await(2, TimeUnit.SECONDS)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error swiping ${direction.name.lowercase()}", e)
            false
        }
    }

    // Helper methods

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextRecursive(child, text)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun findAllNodesByTextRecursive(
        node: AccessibilityNodeInfo,
        text: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllNodesByTextRecursive(child, text, results)
            child.recycle()
        }
    }

    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        return if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK).also {
                Log.d(TAG, "Tapped node: ${node.className}, text=${node.text}")
            }
        } else {
            // Try clickable parent
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Tapped parent: ${parent.className}")
                    parent.recycle()
                    return success
                }
                val next = parent.parent
                parent.recycle()
                parent = next
            }
            Log.w(TAG, "Node and parents not clickable: ${node.className}")
            false
        }
    }

    private fun longPressNode(node: AccessibilityNodeInfo): Boolean {
        return if (node.isLongClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK).also {
                Log.d(TAG, "Long pressed node: ${node.className}, text=${node.text}")
            }
        } else {
            // Try long-clickable parent
            var parent = node.parent
            while (parent != null) {
                if (parent.isLongClickable) {
                    val success = parent.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    Log.d(TAG, "Long pressed parent: ${parent.className}")
                    parent.recycle()
                    return success
                }
                val next = parent.parent
                parent.recycle()
                parent = next
            }
            // Fallback: try using gesture-based long press at node center
            Log.d(TAG, "Node not long-clickable, trying gesture-based long press")
            longPressNodeViaGesture(node)
        }
    }

    private fun longPressNodeViaGesture(node: AccessibilityNodeInfo): Boolean {
        return try {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()

            val path = Path().apply {
                moveTo(x, y)
            }

            // Long press = hold for 500ms+
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
                .build()

            val latch = CountDownLatch(1)
            var result = false
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result = true
                    Log.d(TAG, "Long press via gesture at ($x, $y) completed")
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Long press via gesture at ($x, $y) cancelled")
                    latch.countDown()
                }
            }, null)

            // Wait for the callback with timeout
            latch.await(2, TimeUnit.SECONDS)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error with gesture-based long press", e)
            false
        }
    }

    private fun typeIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args).also {
            Log.d(TAG, "Typed '$text': $it")
        }
    }

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && (focused.isEditable ||
                    focused.className?.toString()?.contains("EditText", ignoreCase = true) == true)) {
            return focused
        }
        focused?.recycle()

        // Fallback: find any EditText
        return findEditableNodeRecursive(root)
    }

    private fun findEditableNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNodeRecursive(child)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun findKeyboardActionButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val actionTexts = listOf("Go", "Search", "Submit", "Send", "Done", "Next")
        for (text in actionTexts) {
            val found = findNodeByTextRecursive(node, text)
            if (found != null && found.isClickable) return found
            found?.recycle()
        }
        return null
    }
}
