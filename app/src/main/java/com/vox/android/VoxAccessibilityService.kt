package com.vox.android

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

            // P4.3: Traverse tree - temporarily disabled for testing
            try {
                val nodeCount = traverseTree(rootNode, 0)
                Log.d(TAG, "Traversed $nodeCount nodes in the tree")
            } catch (e: Exception) {
                Log.e(TAG, "Error traversing tree", e)
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

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
    }
}
