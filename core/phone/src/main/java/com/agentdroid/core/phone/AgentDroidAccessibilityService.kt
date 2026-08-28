package com.agentdroid.core.phone

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.agentdroid.core.agent.AgentRuntimeControl
import com.agentdroid.core.agent.AgentRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class AgentDroidAccessibilityService : AccessibilityService() {
    @Volatile var lastPackageName: String? = null
        private set
    @Volatile var lastClassName: String? = null
        private set

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var statusText: TextView? = null
    private var pauseButton: Button? = null
    private var stopButton: Button? = null

    override fun onServiceConnected() {
        currentRef = WeakReference(this)
        super.onServiceConnected()
        installAgentCapsule()
        serviceScope.launch { AgentRuntimeControl.state.collectLatest(::updateCapsule) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        event.packageName?.toString()?.let { lastPackageName = it }
        event.className?.toString()?.let { lastClassName = it }
    }

    override fun onInterrupt() = Unit

    private fun installAgentCapsule() {
        if (overlay != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = manager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            background = GradientDrawable().apply {
                setColor(0xE6222222.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x66FFFFFF)
            }
            elevation = dp(8).toFloat()
        }
        statusText = TextView(this).also { text ->
            text.setTextColor(Color.WHITE)
            text.textSize = 12f
            text.text = getString(R.string.agent_capsule_ready)
            root.addView(text)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseButton = capsuleButton(getString(R.string.agent_capsule_pause)) {
            when (AgentRuntimeControl.state.value) {
                AgentRuntimeState.RUNNING -> AgentRuntimeControl.pause()
                AgentRuntimeState.PAUSED -> AgentRuntimeControl.resume()
                else -> Unit
            }
        }.also(row::addView)
        stopButton = capsuleButton(getString(R.string.agent_capsule_stop)) { AgentRuntimeControl.stop() }.also(row::addView)
        row.addView(capsuleButton(getString(R.string.agent_capsule_take_over)) {
            AgentRuntimeControl.pause()
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }?.let(::startActivity)
        })
        root.addView(row)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(72)
        }
        runCatching { manager.addView(root, params) }.onSuccess { overlay = root }
    }

    private fun capsuleButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 10f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(7), 0, dp(7), 0)
        setOnClickListener { action() }
    }

    private fun updateCapsule(state: AgentRuntimeState) {
        statusText?.text = getString(when (state) {
            AgentRuntimeState.IDLE -> R.string.agent_capsule_ready
            AgentRuntimeState.RUNNING -> R.string.agent_capsule_running
            AgentRuntimeState.PAUSED -> R.string.agent_capsule_paused
            AgentRuntimeState.STOPPED -> R.string.agent_capsule_stopping
        })
        pauseButton?.apply {
            isEnabled = state == AgentRuntimeState.RUNNING || state == AgentRuntimeState.PAUSED
            text = getString(if (state == AgentRuntimeState.PAUSED) R.string.agent_capsule_resume else R.string.agent_capsule_pause)
        }
        stopButton?.isEnabled = state == AgentRuntimeState.RUNNING || state == AgentRuntimeState.PAUSED
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (currentRef?.get() === this) currentRef = null
        overlay?.let { view -> runCatching { windowManager?.removeViewImmediate(view) } }
        overlay = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile private var currentRef: WeakReference<AgentDroidAccessibilityService>? = null
        fun current(): AgentDroidAccessibilityService? = currentRef?.get()
    }
}
