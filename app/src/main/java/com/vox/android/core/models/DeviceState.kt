package com.vox.android.core.models

/**
 * Represents the current state of the device.
 * This is the single source of truth for what's happening on the device.
 */
data class DeviceState(
    /** The current UI tree */
    val uiTree: UITree,
    /** Screenshot as base64 JPEG (null if not captured) */
    val screenshotBase64: String? = null,
    /** Map of installed app names to package names */
    val installedApps: Map<String, String> = emptyMap(),
    /** The foreground package name */
    val foregroundPackage: String = "",
    /** Timestamp when this state was captured */
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun empty() = DeviceState(
            uiTree = UITree.empty(),
            screenshotBase64 = null,
            installedApps = emptyMap(),
            foregroundPackage = ""
        )
    }

    /** Compute a hash for change detection (excludes screenshot for efficiency) */
    fun contentHash(): Int = uiTree.contentHash()

    /** Check if the UI has changed from a previous state */
    fun hasUiChangedFrom(previousHash: Int): Boolean = contentHash() != previousHash
}

/**
 * Represents app information.
 */
data class AppInfo(
    val name: String,
    val packageName: String
)
