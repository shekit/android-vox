package com.vox.android.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.vox.android.core.models.AppInfo

/**
 * Manages app-related operations (listing, launching, finding).
 */
class AppManager(private val context: Context) {

    companion object {
        private const val TAG = "Vox"
    }

    /**
     * Get all installed launchable apps.
     */
    fun getInstalledApps(): List<AppInfo> {
        return try {
            val pm = context.packageManager
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

    /**
     * Get installed apps as a map (name -> package).
     */
    fun getInstalledAppsMap(): Map<String, String> {
        return getInstalledApps().associate { it.name to it.packageName }
    }

    /**
     * Check if a package is installed.
     */
    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Find app by name (exact then partial match).
     * Returns package name or null.
     */
    fun findAppByName(name: String): String? {
        val apps = getInstalledApps()
        val searchLower = name.lowercase()

        // Exact match first
        apps.find { it.name.equals(name, ignoreCase = true) }?.let {
            Log.d(TAG, "Found exact match for '$name': ${it.packageName}")
            return it.packageName
        }

        // Partial match
        apps.find {
            it.name.lowercase().contains(searchLower) || searchLower.contains(it.name.lowercase())
        }?.let {
            Log.d(TAG, "Found partial match for '$name': ${it.packageName} (${it.name})")
            return it.packageName
        }

        Log.w(TAG, "No app found matching: $name")
        return null
    }

    /**
     * Launch an app by package name.
     */
    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
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

    /**
     * Resolve an app identifier (could be name or package) to a package name.
     */
    fun resolvePackageName(appOrPackage: String): String {
        return if (appOrPackage.contains(".") && isPackageInstalled(appOrPackage)) {
            appOrPackage
        } else {
            findAppByName(appOrPackage) ?: appOrPackage
        }
    }
}
