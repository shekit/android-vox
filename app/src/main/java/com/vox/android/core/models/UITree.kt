package com.vox.android.core.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a node in the UI accessibility tree.
 * Pure Kotlin data class - no Android dependencies except for JSON parsing.
 */
data class UINode(
    val className: String,
    val packageName: String,
    val text: String,
    val contentDescription: String,
    val viewIdResourceName: String,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isFocusable: Boolean,
    val isEnabled: Boolean,
    val isPassword: Boolean,
    val isScrollable: Boolean,
    val isChecked: Boolean,
    val isCheckable: Boolean,
    val isEditable: Boolean,
    val bounds: Bounds,
    val children: List<UINode>
) {
    companion object {
        fun fromJson(json: JSONObject): UINode {
            val boundsJson = json.optJSONObject("bounds")
            val bounds = if (boundsJson != null) {
                Bounds(
                    left = boundsJson.optInt("left", 0),
                    top = boundsJson.optInt("top", 0),
                    right = boundsJson.optInt("right", 0),
                    bottom = boundsJson.optInt("bottom", 0)
                )
            } else {
                Bounds(0, 0, 0, 0)
            }

            val childrenJson = json.optJSONArray("children") ?: JSONArray()
            val children = mutableListOf<UINode>()
            for (i in 0 until childrenJson.length()) {
                childrenJson.optJSONObject(i)?.let {
                    children.add(fromJson(it))
                }
            }

            return UINode(
                className = json.optString("className", ""),
                packageName = json.optString("packageName", ""),
                text = json.optString("text", ""),
                contentDescription = json.optString("contentDescription", ""),
                viewIdResourceName = json.optString("viewIdResourceName", ""),
                isClickable = json.optBoolean("isClickable", false),
                isLongClickable = json.optBoolean("isLongClickable", false),
                isFocusable = json.optBoolean("isFocusable", false),
                isEnabled = json.optBoolean("isEnabled", true),
                isPassword = json.optBoolean("isPassword", false),
                isScrollable = json.optBoolean("isScrollable", false),
                isChecked = json.optBoolean("isChecked", false),
                isCheckable = json.optBoolean("isCheckable", false),
                isEditable = json.optBoolean("isEditable", false),
                bounds = bounds,
                children = children
            )
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("className", className)
        json.put("packageName", packageName)
        json.put("text", text)
        json.put("contentDescription", contentDescription)
        json.put("viewIdResourceName", viewIdResourceName)
        json.put("isClickable", isClickable)
        json.put("isLongClickable", isLongClickable)
        json.put("isFocusable", isFocusable)
        json.put("isEnabled", isEnabled)
        json.put("isPassword", isPassword)
        json.put("isScrollable", isScrollable)
        json.put("isChecked", isChecked)
        json.put("isCheckable", isCheckable)
        json.put("isEditable", isEditable)

        val boundsJson = JSONObject()
        boundsJson.put("left", bounds.left)
        boundsJson.put("top", bounds.top)
        boundsJson.put("right", bounds.right)
        boundsJson.put("bottom", bounds.bottom)
        json.put("bounds", boundsJson)

        val childrenJson = JSONArray()
        children.forEach { childrenJson.put(it.toJson()) }
        json.put("children", childrenJson)

        return json
    }

    /** Count total nodes in this subtree */
    fun nodeCount(): Int = 1 + children.sumOf { it.nodeCount() }

    /** Find nodes matching a predicate */
    fun findNodes(predicate: (UINode) -> Boolean): List<UINode> {
        val result = mutableListOf<UINode>()
        if (predicate(this)) result.add(this)
        children.forEach { result.addAll(it.findNodes(predicate)) }
        return result
    }

    /** Find first node matching text (case-insensitive) */
    fun findByText(text: String): UINode? {
        if (this.text.contains(text, ignoreCase = true) ||
            this.contentDescription.contains(text, ignoreCase = true)) {
            return this
        }
        children.forEach { child ->
            child.findByText(text)?.let { return it }
        }
        return null
    }

    /** Check if this node is actionable or has meaningful content */
    fun isActionable(): Boolean {
        return isClickable || isLongClickable || isEditable || isScrollable || isCheckable
    }

    /** Check if this node has meaningful text content */
    fun hasContent(): Boolean {
        return text.isNotBlank() || contentDescription.isNotBlank()
    }

    /** Check if this node should be included in compact representation */
    fun isRelevant(): Boolean {
        return isActionable() || hasContent()
    }

    /**
     * Convert to compact JSON with only essential properties.
     * Omits empty strings and false booleans to minimize size.
     */
    fun toCompactJson(): JSONObject {
        val json = JSONObject()

        // Only include non-empty text fields
        if (text.isNotBlank()) json.put("text", text)
        if (contentDescription.isNotBlank()) json.put("desc", contentDescription)
        if (viewIdResourceName.isNotBlank()) {
            // Shorten the resource name by removing package prefix
            val shortId = viewIdResourceName.substringAfterLast("/")
            json.put("id", shortId)
        }

        // Only include true action flags
        if (isClickable) json.put("click", true)
        if (isLongClickable) json.put("longClick", true)
        if (isEditable) json.put("edit", true)
        if (isScrollable) json.put("scroll", true)
        if (isCheckable) json.put("check", true)
        if (isChecked) json.put("checked", true)

        // Compact bounds format: [left, top, right, bottom]
        val boundsArray = JSONArray()
        boundsArray.put(bounds.left)
        boundsArray.put(bounds.top)
        boundsArray.put(bounds.right)
        boundsArray.put(bounds.bottom)
        json.put("bounds", boundsArray)

        // Center point for easy tap_at targeting
        val centerArray = JSONArray()
        centerArray.put(bounds.centerX)
        centerArray.put(bounds.centerY)
        json.put("center", centerArray)

        return json
    }

    /**
     * Collect all relevant (actionable or content-bearing) nodes from this subtree.
     * Returns a flat list instead of nested hierarchy.
     */
    fun collectRelevantNodes(): List<UINode> {
        val result = mutableListOf<UINode>()
        collectRelevantNodesRecursive(result)
        return result
    }

    private fun collectRelevantNodesRecursive(result: MutableList<UINode>) {
        if (isRelevant()) {
            result.add(this)
        }
        children.forEach { it.collectRelevantNodesRecursive(result) }
    }
}

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Represents the complete UI tree of the active window.
 */
data class UITree(
    val root: UINode?,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromJsonString(jsonString: String): UITree {
            return try {
                val json = JSONObject(jsonString)
                val root = UINode.fromJson(json)
                UITree(
                    root = root,
                    packageName = root.packageName
                )
            } catch (e: Exception) {
                UITree(root = null, packageName = "")
            }
        }

        fun empty(): UITree = UITree(root = null, packageName = "")
    }

    fun toJsonString(): String = root?.toJson()?.toString() ?: "{}"

    fun nodeCount(): Int = root?.nodeCount() ?: 0

    fun isEmpty(): Boolean = root == null

    /** Compute a hash for change detection */
    fun contentHash(): Int = toJsonString().hashCode()

    /**
     * Generate compact JSON representation with only actionable/relevant elements.
     * Returns a flat array of elements instead of a nested tree structure.
     * This typically reduces the JSON size by 80-90%.
     */
    fun toCompactJsonString(): String {
        val root = this.root ?: return "[]"
        val relevantNodes = root.collectRelevantNodes()
        val jsonArray = JSONArray()
        relevantNodes.forEach { node ->
            jsonArray.put(node.toCompactJson())
        }
        return jsonArray.toString()
    }

    /** Count of relevant (actionable/content-bearing) nodes */
    fun relevantNodeCount(): Int = root?.collectRelevantNodes()?.size ?: 0
}
