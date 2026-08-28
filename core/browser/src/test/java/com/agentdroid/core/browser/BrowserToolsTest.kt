package com.agentdroid.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserToolsTest {
    @Test fun toolNamesAreUniqueAndRegistryReady() {
        val names = BrowserTools.all.map { it.descriptor.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.containsAll(listOf(
            "browser_navigate", "browser_read", "browser_find", "browser_click", "browser_fill",
            "browser_scroll", "browser_back", "browser_forward", "browser_reload", "browser_screenshot"
        )))
    }

    @Test fun mutationAndReadRisksAreExplicit() {
        assertEquals(BrowserRisk.MODIFY, BrowserTools.fill.descriptor.risk)
        assertEquals(BrowserPermissionClass.FILL_FORM, BrowserTools.fill.descriptor.permissionClass)
        assertTrue(BrowserTools.read.descriptor.readOnly)
        assertTrue(BrowserTools.links.descriptor.readOnly)
    }

    @Test fun agentToolAdapterRegistersExpectedBrowserSurface() {
        val tools = createBrowserAgentTools(BrowserSessionService { _ -> error("not executed") })
        assertEquals(
            setOf("browser_navigate", "browser_read", "browser_find", "browser_click", "browser_fill", "browser_scroll", "browser_back", "browser_forward", "browser_reload", "browser_screenshot"),
            tools.map { it.definition.name }.toSet()
        )
    }
}
