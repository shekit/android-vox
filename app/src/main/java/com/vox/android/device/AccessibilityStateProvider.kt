package com.vox.android.device

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.vox.android.VoxAccessibilityService
import com.vox.android.core.interfaces.DeviceStateProvider
import com.vox.android.core.models.AppInfo
import com.vox.android.core.models.DeviceState
import com.vox.android.core.models.UITree
import com.vox.android.core.models.UINode
import com.vox.android.core.models.Bounds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Implementation of DeviceStateProvider using Android Accessibility Service.
 */
class AccessibilityStateProvider(
    private val service: AccessibilityService
) : DeviceStateProvider {

    companion object {
        private const val TAG = "Vox"
        private val screenshotExecutor = Executors.newSingleThreadExecutor()
    }

    override fun getUITree(): UITree {
        val rootNode = service.rootInActiveWindow
        return if (rootNode != null) {
            try {
                val root = nodeToUINode(rootNode)
                val tree = UITree(root = root, packageName = root.packageName)
                rootNode.recycle()
                tree
            } catch (e: Exception) {
                Log.e(TAG, "Error building UI tree", e)
                rootNode.recycle()
                UITree.empty()
            }
        } else {
            Log.w(TAG, "Root window is null")
            UITree.empty()
        }
    }

    private fun nodeToUINode(node: AccessibilityNodeInfo): UINode {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val children = mutableListOf<UINode>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                children.add(nodeToUINode(child))
                child.recycle()
            }
        }

        return UINode(
            className = node.className?.toString() ?: "",
            packageName = node.packageName?.toString() ?: "",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            viewIdResourceName = node.viewIdResourceName ?: "",
            isClickable = node.isClickable,
            isLongClickable = node.isLongClickable,
            isFocusable = node.isFocusable,
            isEnabled = node.isEnabled,
            isPassword = node.isPassword,
            isScrollable = node.isScrollable,
            isChecked = node.isChecked,
            isCheckable = node.isCheckable,
            isEditable = node.isEditable,
            bounds = Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            children = children
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override suspend fun captureScreenshot(): String? = suspendCancellableCoroutine { continuation ->
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)

                            if (bitmap != null) {
                                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                val byteArrayOutputStream = ByteArrayOutputStream()
                                softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
                                val base64 = Base64.encodeToString(
                                    byteArrayOutputStream.toByteArray(),
                                    Base64.NO_WRAP
                                )
                                softwareBitmap.recycle()
                                hardwareBuffer.close()
                                Log.d(TAG, "Screenshot captured: ${base64.length} chars")
                                if (continuation.isActive) continuation.resume(base64)
                            } else {
                                hardwareBuffer.close()
                                if (continuation.isActive) continuation.resume(null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing screenshot", e)
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            if (continuation.isActive) continuation.resume(null)
        }
    }

    override fun getInstalledApps(): List<AppInfo> {
        return try {
            val pm = service.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(mainIntent, 0)
            apps.map {
                AppInfo(
                    name = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName
                )
            }.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting installed apps", e)
            emptyList()
        }
    }

    override suspend fun getDeviceState(includeScreenshot: Boolean): DeviceState {
        val uiTree = getUITree()
        val screenshot = if (includeScreenshot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureScreenshot()
        } else null
        val apps = getInstalledApps().associate { it.name to it.packageName }

        return DeviceState(
            uiTree = uiTree,
            screenshotBase64 = screenshot,
            installedApps = apps,
            foregroundPackage = uiTree.packageName
        )
    }

    override fun uiChangeEvents(): Flow<UITree> {
        return if (service is VoxAccessibilityService) {
            VoxAccessibilityService.uiChangeEvents
        } else {
            callbackFlow {
                awaitClose { }
            }
        }
    }

    override fun isPackageInstalled(packageName: String): Boolean {
        return try {
            service.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun findAppByName(name: String): String? {
        val apps = getInstalledApps()
        val searchLower = name.lowercase()

        // Exact match first
        apps.find { it.name.equals(name, ignoreCase = true) }?.let {
            return it.packageName
        }

        // Partial match
        apps.find {
            it.name.lowercase().contains(searchLower) || searchLower.contains(it.name.lowercase())
        }?.let {
            return it.packageName
        }

        return null
    }

    /**
     * Serialize UI tree to JSON string (for backward compatibility).
     */
    fun getUITreeJson(): String {
        return getUITree().toJsonString()
    }
}
