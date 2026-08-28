package com.agentdroid.core.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

class AccessibilityPhoneAutomationEngine(
    context: Context,
    private val serviceProvider: () -> AgentDroidAccessibilityService? = AgentDroidAccessibilityService::current,
    private val screenshotDir: File = File(context.cacheDir, "phone-screenshots"),
    private val verifier: PhoneActionVerifier = PhoneActionVerifier()
) : PhoneAutomationEngine {
    private val appContext = context.applicationContext

    override suspend fun captureState(includeScreenshot: Boolean): ScreenState = withContext(Dispatchers.Default) {
        val service = serviceProvider() ?: return@withContext ScreenState(fingerprint = "accessibility-unavailable")
        val root = service.rootInActiveWindow
        val roots = if (root == null) emptyList() else listOf(readNode(root, "e:0"))
        val screenshot = if (includeScreenshot) captureScreenshot(service) else null
        val base = buildString {
            append(service.lastPackageName).append('|').append(service.lastClassName).append('|')
            fun appendNode(node: UiNode) {
                append(node.elementId).append(':').append(node.text).append(':').append(node.contentDescription)
                    .append(':').append(node.resourceId).append(':').append(node.bounds).append(';')
                node.children.forEach(::appendNode)
            }
            roots.forEach(::appendNode)
        }
        ScreenState(
            packageName = service.lastPackageName ?: root?.packageName?.toString(),
            activityName = service.lastClassName,
            nodes = roots,
            screenshotPath = screenshot,
            fingerprint = sha256(base)
        )
    }

    override suspend fun perform(action: PhoneAction, maxAttempts: Int): PhoneActionResult {
        require(maxAttempts in 1..3) { "Phone action retries must stay bounded" }
        var before = captureState(false)
        var lastError: String? = null
        repeat(maxAttempts) { index ->
            val raw = runCatching { performOnce(action) }
            if (raw.isFailure) lastError = raw.exceptionOrNull()?.message
            val after = captureState(action.type == PhoneActionType.TAKE_SCREENSHOT)
            val verified = raw.getOrDefault(false) && verifier.verify(action, before, after)
            if (verified) {
                return PhoneActionResult(true, "${action.type.name} verified", before.fingerprint, after.fingerprint, true, index + 1, after.screenshotPath)
            }
            before = after
            if (index + 1 < maxAttempts) delay(180)
        }
        return PhoneActionResult(false, "${action.type.name} did not produce the expected screen change", beforeFingerprint = before.fingerprint, verified = false, attempts = maxAttempts, error = lastError ?: "verification_failed")
    }

    override suspend fun waitForElement(query: String, timeoutMs: Long): UiNode? = withTimeoutOrNull(timeoutMs.coerceIn(250, 30_000)) {
        while (true) {
            val q = query.trim().lowercase()
            val node = captureState(false).flatten().firstOrNull {
                it.elementId.equals(query, true) || it.text?.lowercase()?.contains(q) == true ||
                    it.contentDescription?.lowercase()?.contains(q) == true || it.resourceId?.lowercase()?.contains(q) == true
            }
            if (node != null) return@withTimeoutOrNull node
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    override suspend fun listApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        appContext.packageManager.queryIntentActivities(intent, 0).map { info ->
            InstalledApp(info.activityInfo.packageName, info.loadLabel(appContext.packageManager).toString())
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    private suspend fun performOnce(action: PhoneAction): Boolean {
        val service = serviceProvider() ?: error("Accessibility service is not enabled")
        return when (action.type) {
            PhoneActionType.TAP_ELEMENT -> {
                val node = resolveNode(service, requireNotNull(action.elementId)) ?: return false
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK) || dispatchTap(service, center(node).first, center(node).second, 80)
            }
            PhoneActionType.TAP_COORDINATES -> dispatchTap(service, requireNotNull(action.x), requireNotNull(action.y), 80)
            PhoneActionType.LONG_PRESS -> dispatchTap(service, requireNotNull(action.x), requireNotNull(action.y), action.durationMs.coerceIn(450, 2_500))
            PhoneActionType.SWIPE -> dispatchSwipe(service, requireNotNull(action.x), requireNotNull(action.y), requireNotNull(action.endX), requireNotNull(action.endY), action.durationMs.coerceIn(120, 2_500))
            PhoneActionType.SCROLL -> {
                val node = action.elementId?.let { resolveNode(service, it) } ?: service.rootInActiveWindow
                node?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            }
            PhoneActionType.TYPE_TEXT -> {
                val node = resolveNode(service, requireNotNull(action.elementId)) ?: return false
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text.orEmpty()) }
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            PhoneActionType.CLEAR_TEXT -> {
                val node = resolveNode(service, requireNotNull(action.elementId)) ?: return false
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            PhoneActionType.PRESS_BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            PhoneActionType.PRESS_HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            PhoneActionType.OPEN_APP -> {
                val launch = appContext.packageManager.getLaunchIntentForPackage(requireNotNull(action.packageName)) ?: return false
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(launch); true
            }
            PhoneActionType.TAKE_SCREENSHOT -> captureScreenshot(service) != null
        }
    }

    private fun readNode(node: AccessibilityNodeInfo, id: String): UiNode {
        val rect = Rect(); node.getBoundsInScreen(rect)
        val children = buildList {
            for (index in 0 until node.childCount) node.getChild(index)?.let { add(readNode(it, "$id.$index")) }
        }
        return UiNode(
            elementId = id,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            resourceId = node.viewIdResourceName,
            bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            enabled = node.isEnabled,
            selected = node.isSelected,
            packageName = node.packageName?.toString(),
            children = children
        )
    }

    private fun resolveNode(service: AgentDroidAccessibilityService, elementId: String): AccessibilityNodeInfo? {
        if (!elementId.startsWith("e:0")) return null
        var node = service.rootInActiveWindow ?: return null
        val suffix = elementId.removePrefix("e:0").trimStart('.')
        if (suffix.isBlank()) return node
        for (part in suffix.split('.')) node = node.getChild(part.toIntOrNull() ?: return null) ?: return null
        return node
    }

    private fun center(node: AccessibilityNodeInfo): Pair<Int, Int> {
        val rect = Rect(); node.getBoundsInScreen(rect); return rect.centerX() to rect.centerY()
    }

    private suspend fun dispatchTap(service: AccessibilityService, x: Int, y: Int, duration: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(service, GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build())
    }

    private suspend fun dispatchSwipe(service: AccessibilityService, x: Int, y: Int, endX: Int, endY: Int, duration: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(endX.toFloat(), endY.toFloat()) }
        return dispatch(service, GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build())
    }

    private suspend fun dispatch(service: AccessibilityService, gesture: GestureDescription): Boolean = suspendCancellableCoroutine { continuation ->
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
        }, null)
        if (!accepted && continuation.isActive) continuation.resume(false)
    }

    private suspend fun captureScreenshot(service: AccessibilityService): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        screenshotDir.mkdirs()
        val deferred = CompletableDeferred<Bitmap?>()
        service.takeScreenshot(Display.DEFAULT_DISPLAY, appContext.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close(); deferred.complete(bitmap)
            }
            override fun onFailure(errorCode: Int) { deferred.complete(null) }
        })
        val bitmap = deferred.await() ?: return null
        val file = File(screenshotDir, "screen-${System.currentTimeMillis()}.png")
        withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        bitmap.recycle()
        return file.absolutePath
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
