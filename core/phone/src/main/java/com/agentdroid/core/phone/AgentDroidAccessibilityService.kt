package com.agentdroid.core.phone

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

class AgentDroidAccessibilityService : AccessibilityService() {
    @Volatile var lastPackageName: String? = null
        private set
    @Volatile var lastClassName: String? = null
        private set

    override fun onServiceConnected() {
        currentRef = WeakReference(this)
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        event.packageName?.toString()?.let { lastPackageName = it }
        event.className?.toString()?.let { lastClassName = it }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (currentRef?.get() === this) currentRef = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var currentRef: WeakReference<AgentDroidAccessibilityService>? = null
        fun current(): AgentDroidAccessibilityService? = currentRef?.get()
    }
}
