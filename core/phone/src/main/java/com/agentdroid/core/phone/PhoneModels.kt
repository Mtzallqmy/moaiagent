package com.agentdroid.core.phone

import kotlinx.serialization.Serializable

@Serializable
data class UiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

@Serializable
data class UiNode(
    val elementId: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val resourceId: String? = null,
    val bounds: UiBounds,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val packageName: String? = null,
    val children: List<UiNode> = emptyList()
)

@Serializable
data class ScreenState(
    val packageName: String? = null,
    val activityName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val nodes: List<UiNode> = emptyList(),
    val screenshotPath: String? = null,
    val fingerprint: String
) {
    fun flatten(): List<UiNode> = buildList {
        fun visit(node: UiNode) { add(node); node.children.forEach(::visit) }
        nodes.forEach(::visit)
    }
}

@Serializable
data class InstalledApp(val packageName: String, val label: String)

enum class PhoneActionType {
    TAP_ELEMENT, TAP_COORDINATES, LONG_PRESS, SWIPE, SCROLL,
    TYPE_TEXT, CLEAR_TEXT, PRESS_BACK, PRESS_HOME, OPEN_APP, TAKE_SCREENSHOT
}

data class PhoneAction(
    val type: PhoneActionType,
    val elementId: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val text: String? = null,
    val packageName: String? = null,
    val durationMs: Long = 300
)

@Serializable
data class PhoneActionResult(
    val success: Boolean,
    val summary: String,
    val beforeFingerprint: String? = null,
    val afterFingerprint: String? = null,
    val verified: Boolean = false,
    val attempts: Int = 1,
    val screenshotPath: String? = null,
    val error: String? = null
)

interface PhoneAutomationEngine {
    suspend fun captureState(includeScreenshot: Boolean = false): ScreenState
    suspend fun perform(action: PhoneAction, maxAttempts: Int = 2): PhoneActionResult
    suspend fun waitForElement(query: String, timeoutMs: Long = 5_000): UiNode?
    suspend fun listApps(): List<InstalledApp>
}
