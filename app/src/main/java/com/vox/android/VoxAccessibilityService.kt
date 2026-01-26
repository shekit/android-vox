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
            rootNode.recycle()
        } else {
            Log.w(TAG, "Root window is null")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
    }
}
