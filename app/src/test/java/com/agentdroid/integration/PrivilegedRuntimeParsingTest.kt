package com.agentdroid.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrivilegedRuntimeParsingTest {
    @Test fun parsesStructuredShizukuResultWithoutShellTextProtocol() {
        val result = ShizukuCapability.parseResult("{\"exitCode\":0,\"stdout\":\"ok\\n\",\"stderr\":\"\",\"timedOut\":false,\"durationMs\":12}")
        assertEquals(0, result.exitCode); assertEquals("ok\n", result.stdout); assertFalse(result.timedOut); assertEquals(12, result.durationMs)
    }
}
