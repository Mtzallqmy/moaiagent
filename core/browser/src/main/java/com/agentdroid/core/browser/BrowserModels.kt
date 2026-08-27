package com.agentdroid.core.browser

import android.view.View
import kotlinx.coroutines.flow.StateFlow

/** The model-facing engine. It intentionally has no WebView or JavaScript entry point. */
interface BrowserEngine {
    suspend fun createSession(request: BrowserSessionRequest): BrowserSession
    fun session(sessionId: String): BrowserSession?
    fun sessions(): List<BrowserSession>
    suspend fun closeSession(sessionId: String)
}

/** UI-only capability. Keep this out of ToolRegistry and model-visible service containers. */
interface BrowserSurfaceProvider {
    fun surface(sessionId: String, tabId: String? = null): BrowserSurface?
}

class BrowserSurface internal constructor(val tabId: String, val view: View)

interface BrowserSession {
    val metadata: StateFlow<BrowserSessionMetadata>
    val state: StateFlow<BrowserPageState>

    suspend fun createTab(initialUrl: String? = null, activate: Boolean = true): BrowserTabMetadata
    suspend fun switchTab(tabId: String): BrowserPageState
    suspend fun closeTab(tabId: String): Boolean
    suspend fun navigate(url: String): BrowserPageState
    suspend fun getPageText(maxChars: Int = BrowserLimits.DEFAULT_MAX_PAGE_TEXT): String
    suspend fun getPageTitle(): String
    suspend fun getCurrentUrl(): String
    suspend fun findText(query: String, maxResults: Int = 50): List<BrowserTextMatch>
    suspend fun elements(refresh: Boolean = true): List<BrowserElement>
    suspend fun clickElement(elementId: String): BrowserInteractionResult
    suspend fun submitForm(elementId: String, approval: FormSubmissionApproval): BrowserInteractionResult
    suspend fun fillField(elementId: String, value: String): BrowserInteractionResult
    suspend fun scrollPage(direction: ScrollDirection, amount: Int = 700): BrowserInteractionResult
    suspend fun goBack(): BrowserPageState
    suspend fun goForward(): BrowserPageState
    suspend fun reloadPage(): BrowserPageState
    suspend fun stopLoading()
    suspend fun takeScreenshot(): BrowserScreenshotReference
    suspend fun getLinks(): List<BrowserLink>
    suspend fun getForms(): List<BrowserForm>
    suspend fun getAccessibilityTree(): List<BrowserAccessibilityNode>
    suspend fun close()
}

data class BrowserSessionRequest(
    val workspaceId: String,
    val conversationId: String,
    val initialUrl: String? = null,
    val restoredSessionId: String? = null,
    val restoredTabs: List<BrowserTabMetadata> = emptyList(),
    val restoredActiveTabId: String? = null
)

data class BrowserSessionMetadata(
    val sessionId: String,
    val workspaceId: String,
    val conversationId: String,
    val tabs: List<BrowserTabMetadata>,
    val activeTabId: String,
    val currentUrl: String?,
    val lastUsedAt: Long
)

/** Persistence-safe metadata only: never cookies, headers, form values, or page bodies. */
data class BrowserTabMetadata(
    val tabId: String,
    val title: String = "",
    val currentUrl: String? = null,
    val lastUsedAt: Long = System.currentTimeMillis(),
    val needsReload: Boolean = false
)

data class BrowserPageState(
    val title: String = "",
    val currentUrl: String? = null,
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val lastError: BrowserError? = null
)

data class BrowserElement(
    val elementId: String,
    val tag: String,
    val text: String,
    val role: String?,
    val ariaLabel: String?,
    val href: String?,
    val inputType: String?,
    val visible: Boolean,
    val enabled: Boolean
)

data class BrowserTextMatch(val text: String, val context: String, val occurrence: Int)
data class BrowserLink(val elementId: String, val text: String, val href: String, val visible: Boolean)
data class BrowserForm(
    val elementId: String,
    val action: String?,
    val method: String,
    val fields: List<BrowserElement>,
    val sensitiveFieldIds: List<String>
)

data class BrowserAccessibilityNode(
    val elementId: String,
    val role: String,
    val name: String,
    val enabled: Boolean,
    val visible: Boolean
)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

data class BrowserInteractionResult(
    val performed: Boolean,
    val elementId: String? = null,
    val pageState: BrowserPageState,
    val message: String? = null
)

data class BrowserScreenshotReference(
    val referenceId: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val createdAt: Long = System.currentTimeMillis()
)

fun interface BrowserScreenshotSink {
    suspend fun save(session: BrowserSessionMetadata, pngBytes: ByteArray, width: Int, height: Int): BrowserScreenshotReference
}

object BrowserLimits {
    const val DEFAULT_MAX_PAGE_TEXT = 100_000
    const val MAX_PAGE_TEXT = 500_000
    const val MAX_ELEMENTS = 1_000
    const val MAX_FORMS = 100
    const val MAX_FIELD_VALUE = 32_000
    const val MAX_URL_LENGTH = 8_192
    const val MAX_TABS_PER_SESSION = 12
}

enum class BrowserRisk { SAFE, MODIFY, EXTERNAL, SENSITIVE, DESTRUCTIVE }
enum class BrowserPermissionClass { NAVIGATION, READ_PAGE, FIND_TEXT, CLICK, FILL_FORM, SUBMIT_FORM, DOWNLOAD, OPEN_EXTERNAL_APP, SCREENSHOT }

data class BrowserToolDescriptor(
    val name: String,
    val description: String,
    val risk: BrowserRisk,
    val permissionClass: BrowserPermissionClass,
    val readOnly: Boolean
)

interface BrowserTool<I, O> {
    val descriptor: BrowserToolDescriptor
    suspend fun execute(session: BrowserSession, input: I): O
}

sealed class BrowserError(open val technicalMessage: String, open val recoverable: Boolean = true) {
    data class Navigation(override val technicalMessage: String, val url: String) : BrowserError(technicalMessage)
    data class UnsafeUrl(override val technicalMessage: String, val url: String, val scheme: String?) : BrowserError(technicalMessage, false)
    data class ElementNotFound(val elementId: String) : BrowserError("Element not found: $elementId")
    data class ElementNotInteractable(val elementId: String) : BrowserError("Element is not visible or enabled: $elementId")
    data class FormSubmissionDenied(val elementId: String) : BrowserError("Form submission denied: $elementId", false)
    data class Screenshot(override val technicalMessage: String) : BrowserError(technicalMessage)
    data class SessionClosed(val sessionId: String) : BrowserError("Browser session is closed: $sessionId", false)
    data class TabNotFound(val tabId: String) : BrowserError("Browser tab not found: $tabId", false)
    data class LastTab(val tabId: String) : BrowserError("Close the browser session instead of its last tab: $tabId", false)
    data class TabLimitReached(val limit: Int) : BrowserError("Browser tab limit reached: $limit", false)
}

class BrowserException(val error: BrowserError) : IllegalStateException(error.technicalMessage)
