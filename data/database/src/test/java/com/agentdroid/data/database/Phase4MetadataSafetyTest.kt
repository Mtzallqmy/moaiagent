package com.agentdroid.data.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4MetadataSafetyTest {
    @Test fun browserUrlDropsCredentialsFragmentAndSecretParameters() {
        val sanitized = sanitizePersistedUrl(
            "https://user:pass@example.com/path?q=android&access_token=secret#private"
        )!!

        assertEquals("https://example.com/path?q=android", sanitized)
        assertFalse(sanitized.contains("user"))
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("private"))
    }

    @Test fun browserUrlRejectsNonWebSchemes() {
        assertNull(sanitizePersistedUrl("file:///data/data/private"))
        assertNull(sanitizePersistedUrl("intent://unsafe"))
        assertEquals("about:blank", sanitizePersistedUrl("about:blank"))
    }

    @Test fun delegationSummaryRedactsKnownSecrets() {
        val result = redactSensitiveSummary(
            "Authorization: Bearer abc.def password=hunter2 api_key=top-secret"
        )

        assertFalse(result.contains("abc.def"))
        assertFalse(result.contains("hunter2"))
        assertFalse(result.contains("top-secret"))
        assertTrue(result.contains("[REDACTED]"))
    }
}
