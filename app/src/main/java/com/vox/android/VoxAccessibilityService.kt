package com.vox.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.vox.android.core.interfaces.ActionExecutor
import com.vox.android.core.interfaces.DeviceStateProvider
import com.vox.android.core.models.UITree
import com.vox.android.device.AccessibilityActionExecutor
import com.vox.android.device.AccessibilityStateProvider
import com.vox.android.device.AppManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper around Android AccessibilityService.
 * Delegates to DeviceStateProvider and ActionExecutor implementations.
 *
 * Maintains backward compatibility with existing code while enabling
 * the new modular architecture.
 */
class VoxAccessibilityService : AccessibilityService() {

    // Device layer components
    private lateinit var stateProvider: AccessibilityStateProvider
    private lateinit var actionExecutor: AccessibilityActionExecutor
    private lateinit var appManager: AppManager

    companion object {
        private const val TAG = "Vox"

        // Shared executor for screenshot capture
        private val screenshotExecutor = Executors.newSingleThreadExecutor()

        // Shared storage for the latest UI tree (backward compatibility)
        @Volatile
        var latestTreeJson: String = ""
            private set

        fun getLatestTree(): String = latestTreeJson

        // Shared reference to the service instance
        @Volatile
        private var instance: VoxAccessibilityService? = null

        fun getInstance(): VoxAccessibilityService? = instance

        // UI change callback using AtomicReference to prevent race conditions
        private val uiChangeCallback = AtomicReference<(() -> Unit)?>(null)

        // UI change events flow for new architecture
        private val _uiChangeEvents = MutableSharedFlow<UITree>(replay = 1)
        val uiChangeEvents: SharedFlow<UITree> = _uiChangeEvents.asSharedFlow()

        fun waitForUiChange(timeoutMs: Long, callback: () -> Unit) {
            uiChangeCallback.set(callback)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                uiChangeCallback.getAndSet(null)?.let {
                    Log.d(TAG, "UI change timeout after ${timeoutMs}ms")
                    it()
                }
            }, timeoutMs)
        }

        private fun notifyUiChange() {
            uiChangeCallback.getAndSet(null)?.let {
                Log.d(TAG, "UI change detected, triggering callback")
                it()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Initialize device layer components
        stateProvider = AccessibilityStateProvider(this)
        actionExecutor = AccessibilityActionExecutor(this, stateProvider)
        appManager = AppManager(this)

        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.d(TAG, "Accessibility event: ${event?.eventType} from ${event?.packageName}")

        // Notify callbacks on meaningful UI change events
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                notifyUiChange()
                if (this::stateProvider.isInitialized) {
                    try {
                        val tree = stateProvider.getUITree()
                        _uiChangeEvents.tryEmit(tree)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error emitting UI change event", e)
                    }
                }
            }
        }

        // Update latest tree JSON (backward compatibility)
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            try {
                latestTreeJson = serializeTreeToJson(rootNode).toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error processing tree", e)
            }
            rootNode.recycle()
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    // ========== New Architecture Access ==========

    fun getStateProvider(): DeviceStateProvider = stateProvider
    fun getActionExecutor(): ActionExecutor = actionExecutor
    fun getAppManager(): AppManager = appManager

    // ========== Backward Compatibility Methods ==========
    // These delegate to the new components while maintaining the old API

    fun launchApp(packageName: String): Boolean = actionExecutor.launchApp(packageName)
    fun findAppByName(appName: String): String? = stateProvider.findAppByName(appName)
    fun getInstalledApps(): Map<String, String> = appManager.getInstalledAppsMap()
    fun isPackageInstalled(packageName: String): Boolean = stateProvider.isPackageInstalled(packageName)
    fun tapByText(text: String): Boolean = actionExecutor.tapByText(text)
    fun typeTextByText(nodeText: String, textToType: String): Boolean = actionExecutor.typeText(nodeText, textToType)
    fun scrollForwardInActiveWindow(): Boolean = actionExecutor.scroll(true)
    fun scrollBackwardInActiveWindow(): Boolean = actionExecutor.scroll(false)
    fun pressBack(): Boolean = actionExecutor.pressBack()
    fun pressHome(): Boolean = actionExecutor.pressHome()
    fun pressEnter(): Boolean = actionExecutor.pressEnter()

    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenshot(callback: (String?) -> Unit) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)

                        if (bitmap != null) {
                            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            val byteArrayOutputStream = ByteArrayOutputStream()
                            softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
                            val base64 = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)
                            Log.d(TAG, "Screenshot captured: ${base64.length} chars base64")
                            softwareBitmap.recycle()
                            hardwareBuffer.close()
                            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(base64) }
                        } else {
                            hardwareBuffer.close()
                            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(null) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing screenshot", e)
                        android.os.Handler(android.os.Looper.getMainLooper()).post { callback(null) }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { callback(null) }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            callback(null)
        }
    }

    // JSON serialization for backward compatibility
    private fun serializeTreeToJson(node: AccessibilityNodeInfo): JSONObject {
        val json = JSONObject()
        json.put("className", node.className?.toString() ?: "")
        json.put("packageName", node.packageName?.toString() ?: "")
        json.put("text", node.text?.toString() ?: "")
        json.put("contentDescription", node.contentDescription?.toString() ?: "")
        json.put("viewIdResourceName", node.viewIdResourceName ?: "")
        json.put("isClickable", node.isClickable)
        json.put("isLongClickable", node.isLongClickable)
        json.put("isFocusable", node.isFocusable)
        json.put("isEnabled", node.isEnabled)
        json.put("isPassword", node.isPassword)
        json.put("isScrollable", node.isScrollable)
        json.put("isChecked", node.isChecked)
        json.put("isCheckable", node.isCheckable)

        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val boundsJson = JSONObject()
        boundsJson.put("left", bounds.left)
        boundsJson.put("top", bounds.top)
        boundsJson.put("right", bounds.right)
        boundsJson.put("bottom", bounds.bottom)
        json.put("bounds", boundsJson)

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
}
