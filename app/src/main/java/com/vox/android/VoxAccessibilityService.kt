package com.vox.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class VoxAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoxAccessibility"

        // Shared storage for the latest UI tree (P4.5)
        @Volatile
        var latestTreeJson: String = ""
            private set

        fun getLatestTree(): String = latestTreeJson

        // Shared reference to the service instance (P5.1)
        @Volatile
        private var instance: VoxAccessibilityService? = null

        fun getInstance(): VoxAccessibilityService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 4: UI Tree Capture
        Log.d(TAG, "Accessibility event: ${event?.eventType} from ${event?.packageName}")

        // P4.2: Get root window
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            Log.d(TAG, "Got root window: package=${rootNode.packageName}, " +
                    "childCount=${rootNode.childCount}, className=${rootNode.className}")

            // P4.3: Traverse tree
            try {
                val nodeCount = traverseTree(rootNode, 0)
                Log.d(TAG, "Traversed $nodeCount nodes in the tree")

                // P4.4: Serialize tree to JSON
                val jsonTree = serializeTreeToJson(rootNode)
                val jsonString = jsonTree.toString()
                latestTreeJson = jsonString
                Log.d(TAG, "JSON tree: ${jsonString.take(500)}...") // Log first 500 chars
            } catch (e: Exception) {
                Log.e(TAG, "Error processing tree", e)
            }

            rootNode.recycle()
        } else {
            Log.w(TAG, "Root window is null")
        }
    }

    private fun traverseTree(node: AccessibilityNodeInfo, depth: Int): Int {
        var count = 1 // Count this node

        // Log node info (limit logging to prevent spam)
        if (depth < 3) {
            Log.v(TAG, "${"  ".repeat(depth)}Node: ${node.className}, " +
                    "text=${node.text}, contentDesc=${node.contentDescription}, " +
                    "clickable=${node.isClickable}, children=${node.childCount}")
        }

        // Traverse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                count += traverseTree(child, depth + 1)
                child.recycle()
            }
        }

        return count
    }

    private fun serializeTreeToJson(node: AccessibilityNodeInfo): JSONObject {
        val json = JSONObject()

        // Basic properties
        json.put("className", node.className?.toString() ?: "")
        json.put("packageName", node.packageName?.toString() ?: "")
        json.put("text", node.text?.toString() ?: "")
        json.put("contentDescription", node.contentDescription?.toString() ?: "")
        json.put("viewIdResourceName", node.viewIdResourceName ?: "")

        // State properties
        json.put("isClickable", node.isClickable)
        json.put("isLongClickable", node.isLongClickable)
        json.put("isFocusable", node.isFocusable)
        json.put("isEnabled", node.isEnabled)
        json.put("isPassword", node.isPassword)
        json.put("isScrollable", node.isScrollable)
        json.put("isChecked", node.isChecked)
        json.put("isCheckable", node.isCheckable)

        // Bounds
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val boundsJson = JSONObject()
        boundsJson.put("left", bounds.left)
        boundsJson.put("top", bounds.top)
        boundsJson.put("right", bounds.right)
        boundsJson.put("bottom", bounds.bottom)
        json.put("bounds", boundsJson)

        // Children
        val childrenArray = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                childrenArray.put(serializeTreeToJson(child))
                child.recycle()
            }
        }
        json.put("children", childrenArray)

        return json
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    // P5.1: Launch app by package name
    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "Launched app: $packageName")
                true
            } else {
                Log.e(TAG, "No launch intent found for package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app $packageName", e)
            false
        }
    }

    // P5.2: Find node by text/id
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "Cannot find node: root window is null")
            return null
        }

        try {
            val found = findNodeByTextRecursive(rootNode, text)
            if (found != null) {
                Log.d(TAG, "Found node with text '$text': ${found.className}")
                return found
            } else {
                Log.w(TAG, "No node found with text: $text")
                return null
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // Check if this node matches
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }

        // Search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findNodeByTextRecursive(child, text)
                if (found != null) {
                    child.recycle()
                    return found
                }
                child.recycle()
            }
        }

        return null
    }

    fun findNodeById(resourceId: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "Cannot find node: root window is null")
            return null
        }

        try {
            val found = findNodeByIdRecursive(rootNode, resourceId)
            if (found != null) {
                Log.d(TAG, "Found node with id '$resourceId': ${found.className}")
                return found
            } else {
                Log.w(TAG, "No node found with id: $resourceId")
                return null
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun findNodeByIdRecursive(node: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        // Check if this node matches
        if (node.viewIdResourceName?.contains(resourceId) == true) {
            return node
        }

        // Search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findNodeByIdRecursive(child, resourceId)
                if (found != null) {
                    child.recycle()
                    return found
                }
                child.recycle()
            }
        }

        return null
    }

    // P5.3: Tap action works
    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            // If the node itself is clickable, tap it
            if (node.isClickable) {
                val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    Log.d(TAG, "Tapped node: ${node.className}, text=${node.text}")
                } else {
                    Log.w(TAG, "Tap action returned false for node: ${node.className}")
                }
                success
            } else {
                // Try to find a clickable parent
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        val success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (success) {
                            Log.d(TAG, "Tapped parent node: ${parent.className}, text=${parent.text}")
                        } else {
                            Log.w(TAG, "Tap action returned false for parent: ${parent.className}")
                        }
                        parent.recycle()
                        return success
                    }
                    val nextParent = parent.parent
                    parent.recycle()
                    parent = nextParent
                }
                Log.w(TAG, "Node and its parents are not clickable: ${node.className}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tapping node", e)
            false
        }
    }

    fun tapByText(text: String): Boolean {
        val node = findNodeByText(text)
        return if (node != null) {
            val success = tapNode(node)
            node.recycle()
            success
        } else {
            Log.w(TAG, "Cannot tap: node with text '$text' not found")
            false
        }
    }

    fun tapById(resourceId: String): Boolean {
        val node = findNodeById(resourceId)
        return if (node != null) {
            val success = tapNode(node)
            node.recycle()
            success
        } else {
            Log.w(TAG, "Cannot tap: node with id '$resourceId' not found")
            false
        }
    }
}
