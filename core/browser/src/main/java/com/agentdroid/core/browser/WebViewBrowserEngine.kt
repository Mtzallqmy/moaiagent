package com.agentdroid.core.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class WebViewBrowserEngine(
    context: Context,
    private val urlPolicy: BrowserUrlPolicy = BrowserUrlPolicy(),
    private val screenshotSink: BrowserScreenshotSink = AppCacheScreenshotSink(context.applicationContext)
) : BrowserEngine, BrowserSurfaceProvider {
    private val appContext = context.applicationContext
    private val values = ConcurrentHashMap<String, WebViewBrowserSession>()

    override suspend fun createSession(request: BrowserSessionRequest): BrowserSession {
        val id = request.restoredSessionId ?: UUID.randomUUID().toString()
        require(values[id] == null) { "Browser session already exists: $id" }
        val session = withContext(Dispatchers.Main.immediate) {
            WebViewBrowserSession(appContext, id, request, urlPolicy, screenshotSink)
        }
        values[id] = session
        request.initialUrl?.let {
            try {
                session.navigate(it)
            } catch (failure: Throwable) {
                values.remove(id)
                session.close()
                throw failure
            }
        }
        return session
    }

    override fun session(sessionId: String): BrowserSession? = values[sessionId]
    override fun sessions(): List<BrowserSession> = values.values.sortedByDescending { it.metadata.value.lastUsedAt }
    override fun surface(sessionId: String, tabId: String?): BrowserSurface? = values[sessionId]?.surface(tabId)

    override suspend fun closeSession(sessionId: String) {
        values.remove(sessionId)?.close()
    }
}

private class AppCacheScreenshotSink(private val context: Context) : BrowserScreenshotSink {
    override suspend fun save(session: BrowserSessionMetadata, pngBytes: ByteArray, width: Int, height: Int): BrowserScreenshotReference =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "browser-screenshots").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val file = File(directory, "$id.png")
            file.outputStream().use { it.write(pngBytes) }
            BrowserScreenshotReference("browser-screenshot:$id", "image/png", width, height)
        }
}

private class WebViewBrowserSession(
    context: Context,
    private val sessionId: String,
    request: BrowserSessionRequest,
    private val urlPolicy: BrowserUrlPolicy,
    private val screenshotSink: BrowserScreenshotSink
) : BrowserSession {
    private class TabRuntime(
        val tabId: String,
        val webView: WebView,
        var pageState: BrowserPageState,
        var pendingNavigation: CompletableDeferred<BrowserPageState>? = null,
        var pendingExpectedUrl: String? = null,
        var pendingHistoryIndex: Int? = null,
        val visitedUrls: MutableList<String> = mutableListOf(),
        var historyIndex: Int = -1,
        var cachedElements: List<BrowserElement> = emptyList(),
        var lastUsedAt: Long = System.currentTimeMillis(),
        var needsReload: Boolean = false
    )

    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(BrowserPageState())
    override val state: StateFlow<BrowserPageState> = _state.asStateFlow()
    private val workspaceId = request.workspaceId
    private val conversationId = request.conversationId
    private val runtimes = linkedMapOf<String, TabRuntime>()
    private var activeTabId = request.restoredActiveTabId
        ?.takeIf { id -> request.restoredTabs.any { it.tabId == id } }
        ?: request.restoredTabs.firstOrNull()?.tabId
        ?: UUID.randomUUID().toString()
    private val _metadata = MutableStateFlow(emptyMetadata())
    override val metadata: StateFlow<BrowserSessionMetadata> = _metadata.asStateFlow()

    private var closed = false

    init {
        require(request.restoredTabs.size <= BrowserLimits.MAX_TABS_PER_SESSION) { "Too many restored browser tabs" }
        require(request.restoredTabs.map { it.tabId }.distinct().size == request.restoredTabs.size) { "Duplicate restored browser tab ids" }
        val restored = request.restoredTabs.ifEmpty { listOf(BrowserTabMetadata(activeTabId)) }
        restored.forEach { tab ->
            val restoredUrl = tab.currentUrl?.takeIf { urlPolicy.assess(it).disposition == UrlDisposition.ALLOW }
            runtimes[tab.tabId] = TabRuntime(
                tabId = tab.tabId,
                webView = createWebView(context, tab.tabId),
                pageState = BrowserPageState(title = tab.title, currentUrl = restoredUrl),
                lastUsedAt = tab.lastUsedAt,
                needsReload = restoredUrl != null
            )
        }
        if (activeTabId !in runtimes) activeTabId = runtimes.keys.first()
        publishActiveState()
        touch(activeRuntime())
    }

    private val webView: WebView get() = activeRuntime().webView
    private var pendingNavigation: CompletableDeferred<BrowserPageState>?
        get() = activeRuntime().pendingNavigation
        set(value) { activeRuntime().pendingNavigation = value }
    private var cachedElements: List<BrowserElement>
        get() = activeRuntime().cachedElements
        set(value) { activeRuntime().cachedElements = value }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, tabId: String): WebView = WebView(context).apply {
        settings.apply {
            // JavaScript is needed for modern pages and fixed DOM adapters. No arbitrary script API
            // or JavaScript bridge is exposed by BrowserSession.
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        isFocusable = true
        isFocusableInTouchMode = true
        setDownloadListener(DownloadListener { _, _, _, _, _ ->
            recordError(tabId, BrowserError.Navigation("Downloads require explicit permission and are disabled in this engine", url.orEmpty()))
        })
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                updateTabState(tabId, loading = newProgress < 100, progress = newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                updateTabState(tabId, title = title.orEmpty())
            }
        }
        webViewClient = SecureClient(tabId)
    }

    fun surface(requestedTabId: String?): BrowserSurface? {
        val runtime = runtimes[requestedTabId ?: activeTabId] ?: return null
        return BrowserSurface(runtime.tabId, runtime.webView)
    }

    override suspend fun createTab(initialUrl: String?, activate: Boolean): BrowserTabMetadata {
        val safeUrl = initialUrl?.let(urlPolicy::requireNavigable)
        val runtime = operationMutex.withLock {
            ensureOpen()
            if (runtimes.size >= BrowserLimits.MAX_TABS_PER_SESSION) {
                throw BrowserException(BrowserError.TabLimitReached(BrowserLimits.MAX_TABS_PER_SESSION))
            }
            onMain {
                val id = UUID.randomUUID().toString()
                TabRuntime(id, createWebView(context = webView.context.applicationContext, tabId = id), BrowserPageState()).also {
                    runtimes[id] = it
                    if (activate) activeTabId = id
                    publishActiveState()
                    touch(it)
                }
            }
        }
        safeUrl?.let { url ->
            if (activate) navigate(url)
            else onMain {
                runtime.needsReload = false
                runtime.webView.loadUrl(url)
            }
        }
        return tabMetadata(runtime)
    }

    override suspend fun switchTab(tabId: String): BrowserPageState = operationMutex.withLock {
        ensureOpen()
        onMain {
            val runtime = runtimes[tabId] ?: throw BrowserException(BrowserError.TabNotFound(tabId))
            activeTabId = tabId
            runtime.lastUsedAt = System.currentTimeMillis()
            publishActiveState()
            touch(runtime)
            runtime.pageState
        }
    }

    override suspend fun closeTab(tabId: String): Boolean = operationMutex.withLock {
        ensureOpen()
        if (runtimes.size == 1 && tabId in runtimes) throw BrowserException(BrowserError.LastTab(tabId))
        val runtime = runtimes.remove(tabId) ?: throw BrowserException(BrowserError.TabNotFound(tabId))
        onMain {
            runtime.pendingNavigation?.cancel()
            runtime.webView.stopLoading()
            runtime.webView.webChromeClient = null
            runtime.webView.webViewClient = WebViewClient()
            runtime.webView.removeAllViews()
            runtime.webView.destroy()
            if (activeTabId == tabId) activeTabId = runtimes.values.maxBy { it.lastUsedAt }.tabId
            publishActiveState()
            touch(activeRuntime())
        }
        true
    }

    override suspend fun navigate(url: String): BrowserPageState {
        val safeUrl = urlPolicy.requireNavigable(url)
        val runtime = operationMutex.withLock {
            ensureOpen()
            activeRuntime().also { it.needsReload = false }
        }
        return navigateAndAwait(runtime) { loadUrl(safeUrl) }
    }

    override suspend fun getPageText(maxChars: Int): String {
        require(maxChars in 1..BrowserLimits.MAX_PAGE_TEXT) { "maxChars must be between 1 and ${BrowserLimits.MAX_PAGE_TEXT}" }
        val value = evaluateFixed("(function(){var b=document.body;return b?(b.innerText||'').slice(0,$maxChars):'';})()")
        touch()
        return decodeJavascriptString(value)
    }

    override suspend fun getPageTitle(): String = onMain {
        ensureOpen()
        touch()
        webView.title ?: activeRuntime().pageState.title
    }

    override suspend fun getCurrentUrl(): String = onMain {
        ensureOpen()
        touch()
        webView.url ?: activeRuntime().pageState.currentUrl.orEmpty()
    }

    override suspend fun findText(query: String, maxResults: Int): List<BrowserTextMatch> {
        require(query.isNotBlank()) { "query must not be blank" }
        require(query.length <= 2_000) { "query is too long" }
        require(maxResults in 1..200) { "maxResults must be between 1 and 200" }
        val text = getPageText(BrowserLimits.MAX_PAGE_TEXT)
        val lowerText = text.lowercase()
        val needle = query.lowercase()
        val output = ArrayList<BrowserTextMatch>()
        var start = 0
        while (output.size < maxResults) {
            val index = lowerText.indexOf(needle, start)
            if (index < 0) break
            val contextStart = max(0, index - 80)
            val contextEnd = minOf(text.length, index + query.length + 80)
            output += BrowserTextMatch(text.substring(index, minOf(index + query.length, text.length)), text.substring(contextStart, contextEnd), output.size + 1)
            start = index + max(1, needle.length)
        }
        return output
    }

    override suspend fun elements(refresh: Boolean): List<BrowserElement> = operationMutex.withLock {
        ensureOpen()
        if (!refresh && cachedElements.isNotEmpty()) return@withLock cachedElements
        val array = evaluateJsonArray(ELEMENTS_SCRIPT)
        cachedElements = (0 until minOf(array.length(), BrowserLimits.MAX_ELEMENTS)).map { index -> array.getJSONObject(index).toElement() }
        touch()
        cachedElements
    }

    override suspend fun clickElement(elementId: String): BrowserInteractionResult = operationMutex.withLock {
        ensureOpen()
        BrowserElementId.requireValid(elementId)
        val known = refreshElement(elementId) ?: throw BrowserException(BrowserError.ElementNotFound(elementId))
        if (!known.visible || !known.enabled) throw BrowserException(BrowserError.ElementNotInteractable(elementId))
        if (known.tag == "button" && known.inputType == "submit" || known.tag == "input" && known.inputType == "submit") {
            throw BrowserException(BrowserError.FormSubmissionDenied(elementId))
        }
        val clickAssessment = BrowserRiskAssessor(urlPolicy).click(known)
        if (clickAssessment.permissionClass == BrowserPermissionClass.OPEN_EXTERNAL_APP) {
            throw BrowserException(BrowserError.UnsafeUrl(clickAssessment.reason, known.href.orEmpty(), known.href?.substringBefore(':')))
        }
        val result = decodeJavascriptString(evaluateFixed(clickScript(elementId)))
        when (result) {
            "OK" -> BrowserInteractionResult(true, elementId, snapshot(), "Element clicked")
            "NOT_INTERACTABLE" -> throw BrowserException(BrowserError.ElementNotInteractable(elementId))
            else -> throw BrowserException(BrowserError.ElementNotFound(elementId))
        }
    }

    override suspend fun submitForm(elementId: String, approval: FormSubmissionApproval): BrowserInteractionResult = operationMutex.withLock {
        ensureOpen()
        BrowserElementId.requireValid(elementId)
        val known = refreshElement(elementId) ?: throw BrowserException(BrowserError.ElementNotFound(elementId))
        val isSubmit = known.inputType?.lowercase() == "submit" && known.tag in setOf("button", "input")
        if (!isSubmit || !known.visible || !known.enabled) throw BrowserException(BrowserError.ElementNotInteractable(elementId))
        val form = readFormsUnsafe().firstOrNull { candidate -> candidate.fields.any { it.elementId == elementId } }
            ?: throw BrowserException(BrowserError.FormSubmissionDenied(elementId))
        // Use the committed page state instead of reading WebView.url off the UI thread. This is
        // also the URL shown in the permission preview, so both sides bind to the same origin.
        val domain = runCatching { java.net.URI(_state.value.currentUrl.orEmpty()).host.orEmpty().lowercase() }.getOrDefault("")
        val bindingChecks = listOf(
            "element" to (approval.elementId == elementId),
            "form" to (approval.formElementId == form.elementId),
            "origin" to sameOriginHost(approval.domain, domain),
            "action" to sameAction(approval.action, form.action)
        )
        if (bindingChecks.any { !it.second }) {
            val failedBindings = bindingChecks.filterNot { it.second }.joinToString(",") { it.first }
            throw BrowserException(BrowserError.FormSubmissionDenied(elementId, "approval binding mismatch: $failedBindings"))
        }
        val result = decodeJavascriptString(evaluateFixed(clickScript(elementId)))
        when (result) {
            "OK" -> BrowserInteractionResult(true, elementId, snapshot(), "Form submitted with allow-once approval")
            "NOT_INTERACTABLE" -> throw BrowserException(BrowserError.ElementNotInteractable(elementId))
            else -> throw BrowserException(BrowserError.ElementNotFound(elementId))
        }
    }

    override suspend fun fillField(elementId: String, value: String): BrowserInteractionResult = operationMutex.withLock {
        ensureOpen()
        BrowserElementId.requireValid(elementId)
        require(value.length <= BrowserLimits.MAX_FIELD_VALUE) { "Field value is too long" }
        val known = refreshElement(elementId) ?: throw BrowserException(BrowserError.ElementNotFound(elementId))
        if (!known.visible || !known.enabled || known.tag !in setOf("input", "textarea", "select")) {
            throw BrowserException(BrowserError.ElementNotInteractable(elementId))
        }
        val result = decodeJavascriptString(evaluateFixed(fillScript(elementId, value)))
        when (result) {
            "OK" -> BrowserInteractionResult(true, elementId, snapshot(), "Field filled; form was not submitted")
            "NOT_INTERACTABLE" -> throw BrowserException(BrowserError.ElementNotInteractable(elementId))
            else -> throw BrowserException(BrowserError.ElementNotFound(elementId))
        }
    }

    override suspend fun scrollPage(direction: ScrollDirection, amount: Int): BrowserInteractionResult = operationMutex.withLock {
        ensureOpen()
        require(amount in 1..20_000) { "Scroll amount must be between 1 and 20000" }
        val (x, y) = when (direction) {
            ScrollDirection.UP -> 0 to -amount
            ScrollDirection.DOWN -> 0 to amount
            ScrollDirection.LEFT -> -amount to 0
            ScrollDirection.RIGHT -> amount to 0
        }
        evaluateFixed("(function(){window.scrollBy({left:$x,top:$y,behavior:'auto'});return 'OK';})()")
        BrowserInteractionResult(true, pageState = snapshot(), message = "Page scrolled")
    }

    override suspend fun goBack(): BrowserPageState {
        val runtime = operationMutex.withLock { ensureOpen(); activeRuntime() }
        awaitPageIdle(runtime)
        val targetIndex = runtime.historyIndex - 1
        val expectedUrl = runtime.visitedUrls.getOrNull(targetIndex) ?: return runtime.pageState
        // WebView's native history index can lag behind on older API levels after a
        // JavaScript-triggered link. Load the session's validated target deterministically.
        return navigateAndAwait(runtime, expectedUrl, targetIndex) { loadUrl(expectedUrl) }
    }

    override suspend fun goForward(): BrowserPageState {
        val runtime = operationMutex.withLock { ensureOpen(); activeRuntime() }
        awaitPageIdle(runtime)
        val targetIndex = runtime.historyIndex + 1
        val expectedUrl = runtime.visitedUrls.getOrNull(targetIndex) ?: return runtime.pageState
        return navigateAndAwait(runtime, expectedUrl, targetIndex) { loadUrl(expectedUrl) }
    }

    override suspend fun reloadPage(): BrowserPageState {
        val runtime = operationMutex.withLock { ensureOpen(); activeRuntime().also { it.needsReload = false } }
        awaitPageIdle(runtime)
        val restoredUrl = runtime.pageState.currentUrl
        val hasLoadedPage = onMain { runtime.webView.url != null }
        return if (!hasLoadedPage && restoredUrl != null) {
            val safeUrl = urlPolicy.requireNavigable(restoredUrl)
            navigateAndAwait(runtime) { loadUrl(safeUrl) }
        } else navigateAndAwait(runtime) { reload() }
    }

    override suspend fun stopLoading() = onMain {
        ensureOpen()
        webView.stopLoading()
        pendingNavigation?.complete(snapshot().copy(loading = false))
        updateState(loading = false)
    }

    override suspend fun takeScreenshot(): BrowserScreenshotReference {
        val captured = onMain {
            ensureOpen()
            val width = webView.width.coerceAtLeast(1)
            val height = webView.height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            webView.draw(Canvas(bitmap))
            Triple(bitmap, width, height)
        }
        val output = ByteArrayOutputStream()
        try {
            if (!captured.first.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw BrowserException(BrowserError.Screenshot("Could not encode browser screenshot"))
            }
        } finally {
            captured.first.recycle()
        }
        return screenshotSink.save(metadata.value, output.toByteArray(), captured.second, captured.third)
    }

    override suspend fun getLinks(): List<BrowserLink> = elements().asSequence()
        .filter { it.tag == "a" && !it.href.isNullOrBlank() }
        .map { BrowserLink(it.elementId, it.text, it.href!!, it.visible) }
        .toList()

    override suspend fun getForms(): List<BrowserForm> = operationMutex.withLock {
        ensureOpen()
        readFormsUnsafe()
    }

    private suspend fun readFormsUnsafe(): List<BrowserForm> {
        val array = evaluateJsonArray(FORMS_SCRIPT)
        return (0 until minOf(array.length(), BrowserLimits.MAX_FORMS)).map { index ->
            val item = array.getJSONObject(index)
            val fields = item.getJSONArray("fields").let { fieldArray ->
                (0 until fieldArray.length()).map { fieldArray.getJSONObject(it).toElement() }
            }
            BrowserForm(
                elementId = item.getString("elementId"),
                action = item.optString("action").takeIf(String::isNotBlank),
                method = item.optString("method", "get").lowercase(),
                fields = fields,
                sensitiveFieldIds = fields.filter(BrowserFormSafety::isSensitive).map { it.elementId }
            )
        }
    }

    override suspend fun getAccessibilityTree(): List<BrowserAccessibilityNode> = elements().mapNotNull { element ->
        val role = element.role ?: inferredRole(element.tag, element.inputType) ?: return@mapNotNull null
        BrowserAccessibilityNode(element.elementId, role, element.ariaLabel ?: element.text, element.enabled, element.visible)
    }

    override suspend fun close() = onMain {
        if (closed) return@onMain
        closed = true
        runtimes.values.forEach { runtime ->
            runtime.pendingNavigation?.cancel()
            runtime.pendingNavigation = null
            runtime.webView.stopLoading()
            runtime.webView.webChromeClient = null
            runtime.webView.webViewClient = WebViewClient()
            runtime.webView.removeAllViews()
            runtime.webView.destroy()
        }
        runtimes.clear()
    }

    private suspend fun navigateAndAwait(
        runtime: TabRuntime,
        expectedUrl: String? = null,
        expectedHistoryIndex: Int? = null,
        action: WebView.() -> Unit
    ): BrowserPageState {
        val deferred = onMain {
            ensureOpen()
            runtime.cachedElements = emptyList()
            runtime.pendingNavigation?.cancel()
            CompletableDeferred<BrowserPageState>().also {
                runtime.pendingNavigation = it
                runtime.pendingExpectedUrl = expectedUrl
                runtime.pendingHistoryIndex = expectedHistoryIndex
                updateTabState(runtime.tabId, loading = true, progress = 0, lastError = null)
                runtime.webView.action()
            }
        }
        return try {
            withTimeout(30_000) { deferred.await() }
        } catch (failure: Throwable) {
            onMain { runtime.webView.stopLoading() }
            if (failure is CancellationException) throw failure
            if (failure is BrowserException) throw failure
            throw BrowserException(BrowserError.Navigation(failure.message ?: "Navigation timed out", runtime.pageState.currentUrl.orEmpty()))
        } finally {
            onMain {
                if (runtime.pendingNavigation === deferred) {
                    runtime.pendingNavigation = null
                    runtime.pendingExpectedUrl = null
                    runtime.pendingHistoryIndex = null
                }
            }
        }
    }

    /** Prevent a late callback from a click-triggered navigation completing a history operation. */
    private suspend fun awaitPageIdle(runtime: TabRuntime) = withTimeout(10_000) {
        while (onMain { runtime.pageState.loading }) delay(25)
    }

    private suspend fun refreshElement(elementId: String): BrowserElement? {
        val array = evaluateJsonArray(ELEMENTS_SCRIPT)
        cachedElements = (0 until minOf(array.length(), BrowserLimits.MAX_ELEMENTS)).map { array.getJSONObject(it).toElement() }
        return cachedElements.firstOrNull { it.elementId == elementId }
    }

    private fun sameOriginHost(approved: String, current: String): Boolean {
        val left = approved.lowercase()
        val right = current.lowercase()
        if (left == right) return true
        val loopback = setOf("localhost", "127.0.0.1", "::1", "[::1]")
        return left in loopback && right in loopback
    }

    private fun sameAction(approved: String?, current: String?): Boolean {
        if (approved == current) return true
        if (approved == null || current == null) return false
        return runCatching {
            val left = java.net.URI(approved).normalize()
            val right = java.net.URI(current).normalize()
            left.scheme.equals(right.scheme, ignoreCase = true) &&
                sameOriginHost(left.host.orEmpty(), right.host.orEmpty()) &&
                left.port == right.port &&
                left.rawPath.orEmpty() == right.rawPath.orEmpty() &&
                left.rawQuery == right.rawQuery
        }.getOrDefault(false)
    }

    /** The only JavaScript gateway. Every caller supplies a module-owned fixed script. */
    private suspend fun evaluateFixed(fixedScript: String): String = onMain {
        ensureOpen()
        val answer = CompletableDeferred<String>()
        webView.evaluateJavascript(fixedScript) { answer.complete(it ?: "null") }
        answer.await()
    }

    private suspend fun evaluateJsonArray(fixedScript: String): JSONArray {
        val decoded = decodeJavascriptString(evaluateFixed(fixedScript))
        return JSONArray(decoded)
    }

    private suspend fun snapshot(): BrowserPageState = onMain { snapshotNow() }
    private fun snapshotNow(): BrowserPageState {
        val runtime = activeRuntime()
        return _state.value.copy(
            title = webView.title ?: runtime.pageState.title,
            currentUrl = webView.url ?: runtime.pageState.currentUrl,
            canGoBack = runtime.historyIndex > 0,
            canGoForward = runtime.historyIndex in 0 until runtime.visitedUrls.lastIndex
        )
    }

    private fun updateState(
        title: String = webView.title ?: _state.value.title,
        loading: Boolean = _state.value.loading,
        progress: Int = _state.value.progress,
        lastError: BrowserError? = _state.value.lastError
    ) {
        updateTabState(activeTabId, title = title, loading = loading, progress = progress, lastError = lastError)
    }

    private fun updateTabState(
        tabId: String,
        title: String = runtimes[tabId]?.webView?.title.orEmpty(),
        currentUrl: String? = runtimes[tabId]?.webView?.url ?: runtimes[tabId]?.pageState?.currentUrl,
        loading: Boolean = runtimes[tabId]?.pageState?.loading ?: false,
        progress: Int = runtimes[tabId]?.pageState?.progress ?: 0,
        lastError: BrowserError? = runtimes[tabId]?.pageState?.lastError
    ) {
        val runtime = runtimes[tabId] ?: return
        runtime.pageState = BrowserPageState(
            title = title,
            currentUrl = currentUrl,
            loading = loading,
            progress = progress,
            canGoBack = runtime.historyIndex > 0,
            canGoForward = runtime.historyIndex in 0 until runtime.visitedUrls.lastIndex,
            lastError = lastError
        )
        runtime.lastUsedAt = System.currentTimeMillis()
        runtime.needsReload = false
        if (activeTabId == tabId) _state.value = runtime.pageState
        touch(runtime)
    }

    private fun recordError(tabId: String, error: BrowserError) {
        updateTabState(tabId, loading = false, lastError = error)
    }

    private fun touch(runtime: TabRuntime = activeRuntime()) {
        val now = System.currentTimeMillis()
        runtime.lastUsedAt = now
        _metadata.value = BrowserSessionMetadata(
            sessionId = sessionId,
            workspaceId = workspaceId,
            conversationId = conversationId,
            tabs = runtimes.values.map(::tabMetadata),
            activeTabId = activeTabId,
            currentUrl = runtimes[activeTabId]?.pageState?.currentUrl,
            lastUsedAt = now
        )
    }

    private fun tabMetadata(runtime: TabRuntime) = BrowserTabMetadata(
        tabId = runtime.tabId,
        title = runtime.pageState.title,
        currentUrl = runtime.pageState.currentUrl,
        lastUsedAt = runtime.lastUsedAt,
        needsReload = runtime.needsReload
    )

    private fun emptyMetadata() = BrowserSessionMetadata(
        sessionId = sessionId,
        workspaceId = workspaceId,
        conversationId = conversationId,
        tabs = emptyList(),
        activeTabId = activeTabId,
        currentUrl = null,
        lastUsedAt = System.currentTimeMillis()
    )

    private fun activeRuntime(): TabRuntime = runtimes[activeTabId]
        ?: throw BrowserException(BrowserError.TabNotFound(activeTabId))

    private fun publishActiveState() {
        _state.value = activeRuntime().pageState
    }

    private fun ensureOpen() {
        if (closed) throw BrowserException(BrowserError.SessionClosed(sessionId))
    }

    private inner class SecureClient(private val tabId: String) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = intercept(request.url.toString(), request.isForMainFrame)
        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = intercept(url, true)

        private fun intercept(url: String, mainFrame: Boolean): Boolean {
            if (!mainFrame) return false
            val assessment = urlPolicy.assess(url)
            if (assessment.disposition == UrlDisposition.ALLOW) return false
            val error = BrowserError.UnsafeUrl(assessment.reason, url, assessment.scheme)
            recordError(tabId, error)
            runtimes[tabId]?.pendingNavigation?.completeExceptionally(BrowserException(error))
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            runtimes[tabId]?.cachedElements = emptyList()
            updateTabState(tabId, currentUrl = url, loading = true, progress = 0, lastError = null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val runtime = runtimes[tabId] ?: return
            val expected = runtime.pendingExpectedUrl
            if (expected == null || expected == url) {
                if (url != null) {
                    val requestedIndex = runtime.pendingHistoryIndex
                    if (requestedIndex != null && runtime.visitedUrls.getOrNull(requestedIndex) == url) {
                        runtime.historyIndex = requestedIndex
                    } else if (runtime.visitedUrls.getOrNull(runtime.historyIndex) != url) {
                        while (runtime.visitedUrls.lastIndex > runtime.historyIndex) {
                            runtime.visitedUrls.removeAt(runtime.visitedUrls.lastIndex)
                        }
                        runtime.visitedUrls += url
                        runtime.historyIndex = runtime.visitedUrls.lastIndex
                    }
                }
                updateTabState(tabId, currentUrl = url, loading = false, progress = 100)
                runtime.pendingNavigation?.complete(runtime.pageState)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val mapped = BrowserError.Navigation(error.description.toString(), request.url.toString())
            recordError(tabId, mapped)
            runtimes[tabId]?.pendingNavigation?.completeExceptionally(BrowserException(mapped))
        }

        // WebViewClient only dispatches this callback on API 27+, where SafeBrowsingResponse exists.
        @SuppressLint("NewApi")
        override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
            callback.backToSafety(true)
            val error = BrowserError.UnsafeUrl("Android Safe Browsing blocked a dangerous page", request.url.toString(), request.url.scheme)
            recordError(tabId, error)
            runtimes[tabId]?.pendingNavigation?.completeExceptionally(BrowserException(error))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val error = BrowserError.Navigation("Web renderer process exited", view.url.orEmpty())
            recordError(tabId, error)
            runtimes[tabId]?.pendingNavigation?.completeExceptionally(BrowserException(error))
            return true
        }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    companion object {
        private val ELEMENTS_SCRIPT = """
            (function(){
              function id(e){var v=e.getAttribute('data-agentdroid-id');if(!/^ad-[0-9]{1,10}${'$'}/.test(v||'')){window.__agentDroidSeq=(window.__agentDroidSeq||0)+1;v='ad-'+window.__agentDroidSeq;e.setAttribute('data-agentdroid-id',v);}return v;}
              function visible(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);return !!(r.width&&r.height&&s.visibility!=='hidden'&&s.display!=='none');}
              var nodes=document.querySelectorAll('a,button,input,textarea,select,form,[role],[aria-label]');
              var out=[];
              for(var i=0;i<nodes.length&&out.length<${BrowserLimits.MAX_ELEMENTS};i++){var e=nodes[i];out.push({elementId:id(e),tag:(e.tagName||'').toLowerCase(),text:((e.innerText||e.value||e.placeholder||'')+'').slice(0,1000),role:e.getAttribute('role'),ariaLabel:e.getAttribute('aria-label')||e.getAttribute('name'),href:e.href||null,inputType:e.type||null,visible:visible(e),enabled:!e.disabled});}
              return JSON.stringify(out);
            })()
        """.trimIndent()

        private val FORMS_SCRIPT = """
            (function(){
              function id(e){var v=e.getAttribute('data-agentdroid-id');if(!/^ad-[0-9]{1,10}${'$'}/.test(v||'')){window.__agentDroidSeq=(window.__agentDroidSeq||0)+1;v='ad-'+window.__agentDroidSeq;e.setAttribute('data-agentdroid-id',v);}return v;}
              function visible(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);return !!(r.width&&r.height&&s.visibility!=='hidden'&&s.display!=='none');}
              var fs=document.forms,out=[];
              for(var i=0;i<fs.length&&i<${BrowserLimits.MAX_FORMS};i++){var f=fs[i],fields=[];for(var j=0;j<f.elements.length;j++){var e=f.elements[j];fields.push({elementId:id(e),tag:(e.tagName||'').toLowerCase(),text:((e.placeholder||'')+'').slice(0,1000),role:e.getAttribute('role'),ariaLabel:e.getAttribute('aria-label')||e.getAttribute('name'),href:null,inputType:e.type||null,visible:visible(e),enabled:!e.disabled});}out.push({elementId:id(f),action:f.action||null,method:f.method||'get',fields:fields});}
              return JSON.stringify(out);
            })()
        """.trimIndent()

        private fun clickScript(elementId: String) = """
            (function(){var e=document.querySelector('[data-agentdroid-id="${elementId}"]');if(!e)return 'NOT_FOUND';var r=e.getBoundingClientRect(),s=getComputedStyle(e);if(e.disabled||!r.width||!r.height||s.visibility==='hidden'||s.display==='none')return 'NOT_INTERACTABLE';e.click();return 'OK';})()
        """.trimIndent()

        private fun fillScript(elementId: String, value: String): String {
            val encoded = JSONObject.quote(value)
            return """
                (function(){var e=document.querySelector('[data-agentdroid-id="${elementId}"]');if(!e)return 'NOT_FOUND';var r=e.getBoundingClientRect(),s=getComputedStyle(e);if(e.disabled||e.readOnly||!r.width||!r.height||s.visibility==='hidden'||s.display==='none')return 'NOT_INTERACTABLE';var v=$encoded;var p=Object.getPrototypeOf(e),d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(e,v);else e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'OK';})()
            """.trimIndent()
        }

        private fun decodeJavascriptString(raw: String): String {
            if (raw == "null" || raw == "undefined") return ""
            return (JSONTokener(raw).nextValue() as? String) ?: raw
        }

        private fun JSONObject.toElement() = BrowserElement(
            elementId = getString("elementId"),
            tag = optString("tag"),
            text = optString("text"),
            role = optNullable("role"),
            ariaLabel = optNullable("ariaLabel"),
            href = optNullable("href"),
            inputType = optNullable("inputType"),
            visible = optBoolean("visible"),
            enabled = optBoolean("enabled", true)
        )

        private fun JSONObject.optNullable(name: String): String? = if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

        private fun inferredRole(tag: String, inputType: String?): String? = when (tag) {
            "a" -> "link"
            "button" -> "button"
            "form" -> "form"
            "textarea" -> "textbox"
            "select" -> "combobox"
            "input" -> when (inputType) { "checkbox" -> "checkbox"; "radio" -> "radio"; "submit", "button" -> "button"; else -> "textbox" }
            else -> null
        }
    }
}
