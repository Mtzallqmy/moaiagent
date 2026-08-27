package com.agentdroid.core.browser

import com.agentdroid.core.agent.*
import kotlinx.serialization.json.*

fun interface BrowserSessionService {
    suspend fun session(context: ToolContext): BrowserSession
}

fun createBrowserAgentTools(
    sessions: BrowserSessionService,
    riskAssessor: BrowserRiskAssessor = BrowserRiskAssessor()
): List<AgentTool> = listOf(
    NavigateAgentTool(sessions, riskAssessor), ReadAgentTool(sessions), FindAgentTool(sessions),
    ClickAgentTool(sessions, riskAssessor), FillAgentTool(sessions, riskAssessor), ScrollAgentTool(sessions),
    PageActionAgentTool("browser_back", "Go back in browser history", sessions) { goBack() },
    PageActionAgentTool("browser_forward", "Go forward in browser history", sessions) { goForward() },
    PageActionAgentTool("browser_reload", "Reload the current page", sessions) { reloadPage() },
    ScreenshotAgentTool(sessions)
)

private abstract class BrowserAgentTool(protected val sessions: BrowserSessionService) : AgentTool {
    override fun availableInMode(mode: AgentMode) = mode != AgentMode.CHAT
    protected suspend fun session(context: ToolContext) = sessions.session(context)
    protected fun failure(error: Throwable): ToolResult {
        val browser = (error as? BrowserException)?.error
        val code = when (browser) {
            is BrowserError.Navigation -> AgentErrorCode.BROWSER_NAVIGATION_ERROR
            is BrowserError.UnsafeUrl -> AgentErrorCode.UNSAFE_URL
            is BrowserError.ElementNotFound -> AgentErrorCode.ELEMENT_NOT_FOUND
            is BrowserError.ElementNotInteractable -> AgentErrorCode.ELEMENT_NOT_INTERACTABLE
            is BrowserError.FormSubmissionDenied -> AgentErrorCode.FORM_SUBMISSION_DENIED
            else -> AgentErrorCode.INTERNAL_ERROR
        }
        val technical = browser?.technicalMessage ?: error.message ?: error::class.java.simpleName
        return ToolResult.failure(AgentError(code, technical, "The browser operation failed.", browser?.recoverable ?: true))
    }
}

private class NavigateAgentTool(sessions: BrowserSessionService, private val assessor: BrowserRiskAssessor) : BrowserAgentTool(sessions) {
    override val definition = browserDefinition("browser_navigate", "Navigate to an HTTP(S) URL.", RiskLevel.SAFE, ToolCategory.BROWSER_READ, listOf("url"), "url" to "string")
    override suspend fun effectiveRisk(input: JsonObject, context: ToolContext) = assessor.navigation(input.string("url")).risk.agentRisk()
    override suspend fun permissionKey(input: JsonObject, context: ToolContext) = "browser_navigate:${effectiveRisk(input, context).name}:${safeDomain(input.string("url"))}"
    override suspend fun preview(input: JsonObject, context: ToolContext) = ToolPreview("Navigate browser", metadata = mapOf("url" to safeAction(input.string("url")), "domain" to safeDomain(input.string("url"))))
    override fun auditInputSummary(input: JsonObject, context: ToolContext) = buildJsonObject { put("url", safeAction(input.string("url"))) }.toString()
    override suspend fun execute(input: JsonObject, context: ToolContext) = runCatching { session(context).navigate(input.string("url")).result("Browser navigated") }.getOrElse(::failure)
}

private class ReadAgentTool(sessions: BrowserSessionService) : BrowserAgentTool(sessions) {
    override val definition = browserDefinition("browser_read", "Read bounded visible page text and metadata.", RiskLevel.SAFE, ToolCategory.BROWSER_READ, fields = arrayOf("maxChars" to "integer"))
    override suspend fun execute(input: JsonObject, context: ToolContext) = runCatching {
        val session = session(context); val limit = (input["maxChars"]?.jsonPrimitive?.intOrNull ?: BrowserLimits.DEFAULT_MAX_PAGE_TEXT).coerceIn(1, BrowserLimits.MAX_PAGE_TEXT)
        ToolResult.success("Browser page read", buildJsonObject { put("title", session.getPageTitle()); put("url", session.getCurrentUrl()); put("text", session.getPageText(limit)) })
    }.getOrElse(::failure)
}

private class FindAgentTool(sessions: BrowserSessionService) : BrowserAgentTool(sessions) {
    override val definition = browserDefinition("browser_find", "Find text in the current page.", RiskLevel.SAFE, ToolCategory.BROWSER_READ, listOf("query"), "query" to "string", "maxResults" to "integer")
    override suspend fun execute(input: JsonObject, context: ToolContext) = runCatching {
        val matches = session(context).findText(input.string("query"), (input["maxResults"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 100))
        ToolResult.success("${matches.size} browser matches", buildJsonObject { put("matches", buildJsonArray { matches.forEach { add(buildJsonObject { put("text", it.text); put("context", it.context); put("occurrence", it.occurrence) }) } }) })
    }.getOrElse(::failure)
}

private class ClickAgentTool(sessions: BrowserSessionService, private val assessor: BrowserRiskAssessor) : BrowserAgentTool(sessions) {
    override val definition = browserDefinition("browser_click", "Click a structured page element by elementId.", RiskLevel.SAFE, ToolCategory.BROWSER_MODIFY, listOf("elementId"), "elementId" to "string")
    private data class Target(val session: BrowserSession, val element: BrowserElement, val form: BrowserForm?)
    private suspend fun target(input: JsonObject, context: ToolContext): Target {
        val browserSession = session(context)
        val id = BrowserElementId.requireValid(input.string("elementId"))
        val element = browserSession.elements().firstOrNull { it.elementId == id }
            ?: throw BrowserException(BrowserError.ElementNotFound(id))
        val form = if (element.inputType?.lowercase() == "submit") {
            browserSession.getForms().firstOrNull { candidate -> candidate.fields.any { it.elementId == id } }
        } else null
        return Target(browserSession, element, form)
    }
    override suspend fun effectiveRisk(input: JsonObject, context: ToolContext): RiskLevel {
        val target = target(input, context)
        return assessor.click(target.element, target.form).risk.agentRisk()
    }
    override suspend fun permissionKey(input: JsonObject, context: ToolContext): String {
        val target = target(input, context)
        val risk = assessor.click(target.element, target.form).risk.agentRisk()
        // A submit approval is deliberately call-bound, so an "always" response cannot silently
        // authorize a later login/payment/delete submission.
        return if (target.form != null) {
            "browser_submit:${risk.name}:${context.toolCallId ?: "once-${System.nanoTime()}"}"
        } else "browser_click:${risk.name}:${target.element.elementId}"
    }
    override suspend fun preview(input: JsonObject, context: ToolContext): ToolPreview {
        val target = target(input, context)
        val fields = target.form?.fields.orEmpty().joinToString(", ") { it.ariaLabel ?: it.inputType ?: it.tag }
        val sensitive = target.form?.sensitiveFieldIds.orEmpty().toSet()
        val redactedFields = target.form?.fields.orEmpty().joinToString(", ") { field ->
            "${field.ariaLabel ?: field.inputType ?: field.tag}=${if (field.elementId in sensitive) "[SENSITIVE REDACTED]" else "[REDACTED]"}"
        }
        return ToolPreview(
            if (target.form != null) "Submit form" else "Click ${target.element.role ?: target.element.tag}",
            metadata = mapOf(
                "elementId" to target.element.elementId,
                "domain" to safeDomain(target.session.getCurrentUrl()),
                "formAction" to safeAction(target.form?.action),
                "fieldNames" to fields,
                "fields" to redactedFields
            )
        )
    }
    override suspend fun execute(input: JsonObject, context: ToolContext) = runCatching {
        val target = target(input, context)
        val result = if (target.form != null) {
            val domain = safeDomain(target.session.getCurrentUrl())
            target.session.submitForm(target.element.elementId, FormSubmissionApproval(target.element.elementId, domain, target.form.action))
        } else target.session.clickElement(target.element.elementId)
        result.result(if (target.form != null) "Browser form submitted" else "Browser element clicked")
    }.getOrElse(::failure)
}

private class FillAgentTool(sessions: BrowserSessionService, private val assessor: BrowserRiskAssessor) : BrowserAgentTool(sessions) {
    override val definition = browserDefinition("browser_fill", "Fill a structured form field without submitting it.", RiskLevel.MODIFY, ToolCategory.BROWSER_MODIFY, listOf("elementId", "value"), "elementId" to "string", "value" to "string")
    private suspend fun element(input: JsonObject, context: ToolContext): BrowserElement { val id = BrowserElementId.requireValid(input.string("elementId")); return session(context).elements().firstOrNull { it.elementId == id } ?: throw BrowserException(BrowserError.ElementNotFound(id)) }
    override suspend fun effectiveRisk(input: JsonObject, context: ToolContext) = assessor.fill(element(input, context)).risk.agentRisk()
    override suspend fun permissionKey(input: JsonObject, context: ToolContext) = "browser_fill:${effectiveRisk(input, context).name}:${input.string("elementId")}"
    override suspend fun preview(input: JsonObject, context: ToolContext): ToolPreview { val e = element(input, context); return ToolPreview("Fill ${e.ariaLabel ?: e.inputType ?: e.tag}", metadata = mapOf("elementId" to e.elementId, "value" to "[REDACTED]", "sensitive" to BrowserFormSafety.isSensitive(e).toString())) }
    override fun auditInputSummary(input: JsonObject, context: ToolContext) = buildJsonObject { put("elementId", input.string("elementId")); put("value", "[REDACTED]") }.toString()
    override suspend fun execute(input: JsonObject, context: ToolContext) = runCatching { session(context).fillField(input.string("elementId"), input.string("value")).result("Browser field filled") }.getOrElse(::failure)
}

private class ScrollAgentTool(sessions: BrowserSessionService) : BrowserAgentTool(sessions) {
    override val definition = browserDefinition("browser_scroll", "Scroll the current page.", RiskLevel.SAFE, ToolCategory.BROWSER_READ, fields = arrayOf("direction" to "string", "amount" to "integer"))
    override suspend fun execute(input: JsonObject, context: ToolContext) = runCatching {
        val direction = runCatching { ScrollDirection.valueOf((input["direction"]?.jsonPrimitive?.contentOrNull ?: "DOWN").uppercase()) }.getOrDefault(ScrollDirection.DOWN)
        session(context).scrollPage(direction, (input["amount"]?.jsonPrimitive?.intOrNull ?: 700).coerceIn(1, 5_000)).result("Browser scrolled")
    }.getOrElse(::failure)
}

private class PageActionAgentTool(name: String, description: String, sessions: BrowserSessionService, private val action: suspend BrowserSession.() -> BrowserPageState) : BrowserAgentTool(sessions) {
    override val definition = browserDefinition(name, description, RiskLevel.SAFE, ToolCategory.BROWSER_READ)
    override suspend fun execute(input: JsonObject, context: ToolContext) = runCatching { session(context).action().result(definition.description) }.getOrElse(::failure)
}

private class ScreenshotAgentTool(sessions: BrowserSessionService) : BrowserAgentTool(sessions) {
    override val definition = browserDefinition("browser_screenshot", "Capture the viewport as an artifact reference.", RiskLevel.SAFE, ToolCategory.ARTIFACT)
    override suspend fun execute(input: JsonObject, context: ToolContext) = runCatching { val shot = session(context).takeScreenshot(); ToolResult.success("Browser screenshot captured", buildJsonObject { put("artifactId", shot.referenceId); put("mimeType", shot.mimeType); put("width", shot.width); put("height", shot.height) }) }.getOrElse(::failure)
}

private fun BrowserPageState.result(summary: String) = ToolResult.success(summary, buildJsonObject { put("title", title); currentUrl?.let { put("url", it) }; put("loading", loading); put("canGoBack", canGoBack); put("canGoForward", canGoForward) })
private fun BrowserInteractionResult.result(summary: String) = ToolResult.success(summary, buildJsonObject { put("performed", performed); elementId?.let { put("elementId", it) }; pageState.currentUrl?.let { put("url", it) } })
private fun BrowserRisk.agentRisk() = when (this) { BrowserRisk.SAFE -> RiskLevel.SAFE; BrowserRisk.MODIFY -> RiskLevel.MODIFY; BrowserRisk.EXTERNAL -> RiskLevel.EXTERNAL; BrowserRisk.SENSITIVE -> RiskLevel.SENSITIVE; BrowserRisk.DESTRUCTIVE -> RiskLevel.DESTRUCTIVE }
private fun safeDomain(url: String) = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
private fun safeAction(url: String?): String = runCatching {
    val uri = java.net.URI(url.orEmpty())
    java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
}.getOrDefault("")
private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun browserDefinition(name: String, description: String, risk: RiskLevel, category: ToolCategory, required: List<String> = emptyList(), vararg fields: Pair<String, String>) = ToolDefinition(name, description, buildJsonObject { put("type", "object"); put("properties", buildJsonObject { fields.forEach { (key, type) -> put(key, buildJsonObject { put("type", type) }) } }); if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } }) }, risk, category)
