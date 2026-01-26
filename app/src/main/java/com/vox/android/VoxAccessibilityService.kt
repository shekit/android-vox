package com.vox.android

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class VoxAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoxAccessibility"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
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
                Log.d(TAG, "JSON tree: ${jsonTree.toString().take(500)}...") // Log first 500 chars
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
        Log.d(TAG, "Accessibility service destroyed")
    }
}
