package com.vox.android.device

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.vox.android.core.interfaces.ActionExecutor
import com.vox.android.core.models.Action
import com.vox.android.core.models.ActionResult
import com.vox.android.core.models.FailureReason
import com.vox.android.core.models.ScrollDirection

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
                val resolved = if (stateProvider.isPackageInstalled(action.packageName)) {
                    action.packageName
                } else {
                    stateProvider.findAppByName(action.packageName) ?: action.packageName
                }
                if (launchApp(resolved)) {
                    ActionResult.success(action, "Launched $resolved")
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
            is Action.Type -> {
                if (typeText(action.field, action.text)) {
                    ActionResult.success(action, "Typed '${action.text}' into '${action.field}'")
                } else {
                    ActionResult.failure(action, "Could not type into '${action.field}'", FailureReason.ELEMENT_NOT_FOUND)
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
        val node = findNodeByTextRecursive(rootNode, text)
        val success = if (node != null) {
            tapNode(node).also { node.recycle() }
        } else {
            Log.w(TAG, "Cannot tap: node with text '$text' not found")
            false
        }
        if (node !== rootNode) rootNode.recycle()
        return success
    }

    override fun typeText(field: String, text: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        // Find the field
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
