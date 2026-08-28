package com.agentdroid.core.browser

data class NavigateInput(val url: String)
data class ReadPageInput(val maxChars: Int = BrowserLimits.DEFAULT_MAX_PAGE_TEXT)
data class FindTextInput(val query: String, val maxResults: Int = 50)
data class ElementInput(val elementId: String)
data class FillFieldInput(val elementId: String, val value: String)
data class ScrollInput(val direction: ScrollDirection, val amount: Int = 700)

object BrowserTools {
    val navigate = object : BrowserTool<NavigateInput, BrowserPageState> {
        override val descriptor = descriptor("browser_navigate", "Navigate the active browser tab to an HTTP(S) URL.", BrowserRisk.SAFE, BrowserPermissionClass.NAVIGATION, false)
        override suspend fun execute(session: BrowserSession, input: NavigateInput) = session.navigate(input.url)
    }
    val read = object : BrowserTool<ReadPageInput, String> {
        override val descriptor = descriptor("browser_read", "Read bounded visible page text.", BrowserRisk.SAFE, BrowserPermissionClass.READ_PAGE, true)
        override suspend fun execute(session: BrowserSession, input: ReadPageInput) = session.getPageText(input.maxChars)
    }
    val title = object : BrowserTool<Unit, String> {
        override val descriptor = descriptor("browser_title", "Read the page title.", BrowserRisk.SAFE, BrowserPermissionClass.READ_PAGE, true)
        override suspend fun execute(session: BrowserSession, input: Unit) = session.getPageTitle()
    }
    val currentUrl = object : BrowserTool<Unit, String> {
        override val descriptor = descriptor("browser_current_url", "Read the current URL.", BrowserRisk.SAFE, BrowserPermissionClass.READ_PAGE, true)
        override suspend fun execute(session: BrowserSession, input: Unit) = session.getCurrentUrl()
    }
    val find = object : BrowserTool<FindTextInput, List<BrowserTextMatch>> {
        override val descriptor = descriptor("browser_find", "Find text in the visible page.", BrowserRisk.SAFE, BrowserPermissionClass.FIND_TEXT, true)
        override suspend fun execute(session: BrowserSession, input: FindTextInput) = session.findText(input.query, input.maxResults)
    }
    val click = object : BrowserTool<ElementInput, BrowserInteractionResult> {
        override val descriptor = descriptor("browser_click", "Click a structured page element by elementId.", BrowserRisk.SAFE, BrowserPermissionClass.CLICK, false)
        override suspend fun execute(session: BrowserSession, input: ElementInput) = session.clickElement(input.elementId)
    }
    val fill = object : BrowserTool<FillFieldInput, BrowserInteractionResult> {
        override val descriptor = descriptor("browser_fill", "Fill a structured form field without submitting it.", BrowserRisk.MODIFY, BrowserPermissionClass.FILL_FORM, false)
        override suspend fun execute(session: BrowserSession, input: FillFieldInput) = session.fillField(input.elementId, input.value)
    }
    val scroll = object : BrowserTool<ScrollInput, BrowserInteractionResult> {
        override val descriptor = descriptor("browser_scroll", "Scroll the current page.", BrowserRisk.SAFE, BrowserPermissionClass.READ_PAGE, true)
        override suspend fun execute(session: BrowserSession, input: ScrollInput) = session.scrollPage(input.direction, input.amount)
    }
    val back = pageAction("browser_back", "Go back in tab history") { goBack() }
    val forward = pageAction("browser_forward", "Go forward in tab history") { goForward() }
    val reload = pageAction("browser_reload", "Reload the current page") { reloadPage() }
    val screenshot = object : BrowserTool<Unit, BrowserScreenshotReference> {
        override val descriptor = descriptor("browser_screenshot", "Capture the visible browser viewport as an artifact reference.", BrowserRisk.SAFE, BrowserPermissionClass.SCREENSHOT, true)
        override suspend fun execute(session: BrowserSession, input: Unit) = session.takeScreenshot()
    }
    val links = readAction("browser_links", "Get structured links") { getLinks() }
    val forms = readAction("browser_forms", "Get structured forms and redacted field metadata") { getForms() }
    val accessibilityTree = readAction("browser_accessibility_tree", "Get a bounded structured accessibility tree") { getAccessibilityTree() }

    val all: List<BrowserTool<*, *>> = listOf(navigate, read, title, currentUrl, find, click, fill, scroll, back, forward, reload, screenshot, links, forms, accessibilityTree)

    private fun descriptor(name: String, description: String, risk: BrowserRisk, permission: BrowserPermissionClass, readOnly: Boolean): BrowserToolDescriptor =
        BrowserToolDescriptor(name, description, risk, permission, readOnly)

    private fun pageAction(name: String, description: String, block: suspend BrowserSession.() -> BrowserPageState): BrowserTool<Unit, BrowserPageState> =
        object : BrowserTool<Unit, BrowserPageState> {
            override val descriptor = descriptor(name, description, BrowserRisk.SAFE, BrowserPermissionClass.NAVIGATION, false)
            override suspend fun execute(session: BrowserSession, input: Unit) = session.block()
        }

    private fun <T> readAction(name: String, description: String, block: suspend BrowserSession.() -> T): BrowserTool<Unit, T> =
        object : BrowserTool<Unit, T> {
            override val descriptor = descriptor(name, description, BrowserRisk.SAFE, BrowserPermissionClass.READ_PAGE, true)
            override suspend fun execute(session: BrowserSession, input: Unit) = session.block()
        }
}
