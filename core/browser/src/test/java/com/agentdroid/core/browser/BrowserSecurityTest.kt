package com.agentdroid.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSecurityTest {
    private val policy = BrowserUrlPolicy()

    @Test fun allowsOnlyWellFormedHttpNetworkUrls() {
        val https = policy.assess("https://example.com/path?q=1")
        assertEquals(UrlDisposition.ALLOW, https.disposition)
        assertEquals("https", https.scheme)

        assertEquals(UrlDisposition.ALLOW, policy.assess("http://127.0.0.1:8080/").disposition)
        assertEquals(UrlDisposition.DENY, policy.assess("https:///missing-host").disposition)
        assertEquals(UrlDisposition.DENY, policy.assess("example.com").disposition)
        assertEquals(UrlDisposition.DENY, policy.assess("https://user:pass@example.com/").disposition)
    }

    @Test fun blocksScriptAndLocalResourceSchemes() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "content://settings/system", "data:text/html,test").forEach {
            assertEquals(it, UrlDisposition.DENY, policy.assess(it).disposition)
            assertNull(policy.assess(it).normalizedUrl)
        }
    }

    @Test fun externalAppSchemesAlwaysRequireSeparatePermission() {
        listOf("intent://scan/#Intent;scheme=zxing;end", "tel:+12025550123", "sms:+12025550123", "market://details?id=test").forEach {
            assertEquals(it, UrlDisposition.ASK_EXTERNAL, policy.assess(it).disposition)
        }
    }

    @Test fun elementIdsRejectSelectorsAndScriptFragments() {
        assertEquals("ad-42", BrowserElementId.requireValid("ad-42"))
        listOf("42", "ad-x", "ad-1']", "ad-1;alert(1)", "ad-12345678901").forEach {
            assertThrows(IllegalArgumentException::class.java) { BrowserElementId.requireValid(it) }
        }
    }

    @Test fun identifiesSensitiveFormMetadataWithoutKeepingValues() {
        val password = BrowserElement("ad-1", "input", "", null, "Password", null, "password", true, true)
        val card = BrowserElement("ad-2", "input", "", null, "card_number", null, "text", true, true)
        val plain = BrowserElement("ad-3", "input", "", null, "city", null, "text", true, true)
        assertTrue(BrowserFormSafety.isSensitive(password))
        assertTrue(BrowserFormSafety.isSensitive(card))
        assertFalse(BrowserFormSafety.isSensitive(plain))
        assertEquals(BrowserRisk.SENSITIVE, BrowserRiskAssessor().fill(password).risk)
        assertEquals(BrowserPermissionClass.FILL_FORM, BrowserRiskAssessor().fill(plain).permissionClass)
    }

    @Test fun dynamicallyEscalatesExternalLinksAndSubmitButtons() {
        val assessor = BrowserRiskAssessor()
        val external = BrowserElement("ad-1", "a", "Call", null, null, "tel:+12025550123", null, true, true)
        val submit = BrowserElement("ad-2", "button", "Pay", null, null, null, "submit", true, true)
        assertEquals(BrowserRisk.EXTERNAL, assessor.click(external).risk)
        assertTrue(assessor.click(external).requiresConfirmation)
        assertEquals(BrowserPermissionClass.SUBMIT_FORM, assessor.click(submit).permissionClass)
        assertEquals(BrowserRisk.SENSITIVE, assessor.click(submit).risk)
    }
}
