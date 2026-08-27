package com.agentdroid.core.browser

import java.net.URI
import java.util.Locale

enum class UrlDisposition { ALLOW, ASK_EXTERNAL, DENY }

data class UrlAssessment(
    val disposition: UrlDisposition,
    val normalizedUrl: String?,
    val scheme: String?,
    val reason: String
)

/** Central URL policy used by navigation and WebView callbacks. */
class BrowserUrlPolicy(
    private val allowedSchemes: Set<String> = setOf("http", "https"),
    private val externalSchemes: Set<String> = setOf("intent", "tel", "sms", "market"),
    private val deniedSchemes: Set<String> = setOf("file", "content", "javascript", "data")
) {
    fun assess(rawUrl: String): UrlAssessment {
        val value = rawUrl.trim()
        if (value.isEmpty()) return denied(rawUrl, null, "URL is empty")
        if (value.length > BrowserLimits.MAX_URL_LENGTH) return denied(rawUrl, null, "URL is too long")
        if (value.any { it == '\u0000' || it == '\r' || it == '\n' }) return denied(rawUrl, null, "URL contains control characters")

        val uri = runCatching { URI(value) }.getOrNull() ?: return denied(rawUrl, null, "URL is malformed")
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme == null) return denied(rawUrl, null, "URL must include a scheme")
        if (scheme in deniedSchemes) return denied(rawUrl, scheme, "$scheme URLs are blocked")
        if (scheme in externalSchemes) return UrlAssessment(UrlDisposition.ASK_EXTERNAL, null, scheme, "$scheme URLs require explicit external-app permission")
        if (scheme !in allowedSchemes) return denied(rawUrl, scheme, "Unsupported URL scheme")
        if (uri.host.isNullOrBlank()) return denied(rawUrl, scheme, "Network URL must include a host")
        if (!uri.rawUserInfo.isNullOrBlank()) return denied(rawUrl, scheme, "Credentials in URLs are blocked")
        return UrlAssessment(UrlDisposition.ALLOW, uri.toString(), scheme, "Allowed network URL")
    }

    fun requireNavigable(rawUrl: String): String {
        val assessment = assess(rawUrl)
        if (assessment.disposition != UrlDisposition.ALLOW) {
            throw BrowserException(BrowserError.UnsafeUrl(assessment.reason, rawUrl, assessment.scheme))
        }
        return assessment.normalizedUrl!!
    }

    private fun denied(url: String, scheme: String?, reason: String) =
        UrlAssessment(UrlDisposition.DENY, null, scheme, reason)
}

object BrowserElementId {
    private val valid = Regex("ad-[0-9]{1,10}")
    fun requireValid(value: String): String {
        require(valid.matches(value)) { "Invalid browser element id" }
        return value
    }
}

data class FormSubmissionPreview(
    val domain: String,
    val action: String?,
    val fieldIds: List<String>,
    val sensitiveFieldIds: List<String>
)

/** Capability created only by trusted in-module adapters after an allow-once decision. */
class FormSubmissionApproval internal constructor(
    internal val elementId: String,
    internal val domain: String,
    internal val action: String?
)

data class BrowserPermissionAssessment(
    val risk: BrowserRisk,
    val permissionClass: BrowserPermissionClass,
    val requiresConfirmation: Boolean,
    val reason: String
)

/** Dynamic assessment for ToolRegistry adapters; descriptors only contain the base risk. */
class BrowserRiskAssessor(private val urlPolicy: BrowserUrlPolicy = BrowserUrlPolicy()) {
    fun navigation(url: String): BrowserPermissionAssessment {
        val assessment = urlPolicy.assess(url)
        return when (assessment.disposition) {
            UrlDisposition.ALLOW -> BrowserPermissionAssessment(BrowserRisk.SAFE, BrowserPermissionClass.NAVIGATION, false, assessment.reason)
            UrlDisposition.ASK_EXTERNAL -> BrowserPermissionAssessment(BrowserRisk.EXTERNAL, BrowserPermissionClass.OPEN_EXTERNAL_APP, true, assessment.reason)
            UrlDisposition.DENY -> BrowserPermissionAssessment(BrowserRisk.DESTRUCTIVE, BrowserPermissionClass.NAVIGATION, true, assessment.reason)
        }
    }

    fun click(element: BrowserElement, form: BrowserForm? = null): BrowserPermissionAssessment {
        val isSubmit = element.inputType?.lowercase(Locale.US) == "submit"
        if (isSubmit) {
            val fingerprint = listOf(element.text, element.ariaLabel.orEmpty(), form?.action.orEmpty()) +
                form?.fields.orEmpty().flatMap { listOf(it.text, it.ariaLabel.orEmpty(), it.inputType.orEmpty()) }
            val sensitiveIntent = fingerprint.joinToString(" ").contains(
                Regex("(?i)(log[ -]?in|sign[ -]?in|password|payment|pay|card|checkout|delete|remove|purchase|transfer)")
            ) || form?.sensitiveFieldIds?.isNotEmpty() == true
            return BrowserPermissionAssessment(
                if (sensitiveIntent) BrowserRisk.SENSITIVE else BrowserRisk.EXTERNAL,
                BrowserPermissionClass.SUBMIT_FORM,
                true,
                if (sensitiveIntent) "Sensitive form submission requires an explicit allow-once confirmation" else "Form submission requires an allow-once confirmation"
            )
        }
        element.href?.let { href ->
            val url = urlPolicy.assess(href)
            if (url.disposition != UrlDisposition.ALLOW) {
                return BrowserPermissionAssessment(BrowserRisk.EXTERNAL, BrowserPermissionClass.OPEN_EXTERNAL_APP, true, url.reason)
            }
        }
        return BrowserPermissionAssessment(BrowserRisk.SAFE, BrowserPermissionClass.CLICK, false, "Non-submit page interaction")
    }

    fun fill(element: BrowserElement): BrowserPermissionAssessment =
        BrowserPermissionAssessment(
            risk = if (BrowserFormSafety.isSensitive(element)) BrowserRisk.SENSITIVE else BrowserRisk.MODIFY,
            permissionClass = BrowserPermissionClass.FILL_FORM,
            requiresConfirmation = true,
            reason = if (BrowserFormSafety.isSensitive(element)) "Sensitive field value must never be logged" else "Filling changes page state"
        )
}

object BrowserFormSafety {
    private val sensitiveTypes = setOf("password", "email", "tel")
    private val sensitiveNames = Regex("(?i)(pass(word)?|token|secret|card|cvv|cvc|account|email|phone|otp|pin)")

    fun isSensitive(element: BrowserElement): Boolean =
        element.inputType?.lowercase(Locale.US) in sensitiveTypes ||
            sensitiveNames.containsMatchIn(element.ariaLabel.orEmpty()) ||
            sensitiveNames.containsMatchIn(element.text)
}
