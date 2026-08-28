package com.agentdroid.core.phone

import com.agentdroid.core.agent.RiskLevel
import org.junit.Assert.*
import org.junit.Test

class PhonePolicyVerifierTest {
    @Test fun sensitiveAppsAreBlockedUntilExplicitOverride() {
        val policy = SensitiveAppPolicy()
        val blocked = policy.evaluate("com.example.bank.mobile", overrideSensitive = false, baseRisk = RiskLevel.MODIFY)
        assertTrue(blocked.blocked)
        assertEquals(RiskLevel.SENSITIVE, blocked.risk)
        val overridden = policy.evaluate("com.example.bank.mobile", overrideSensitive = true, baseRisk = RiskLevel.MODIFY)
        assertFalse(overridden.blocked)
        assertEquals(RiskLevel.SENSITIVE, overridden.risk)
    }

    @Test fun verifierRequiresObservableEffectForTap() {
        val before = ScreenState(packageName = "pkg", nodes = emptyList(), fingerprint = "a")
        val same = ScreenState(packageName = "pkg", nodes = emptyList(), fingerprint = "a")
        val changed = ScreenState(packageName = "pkg", nodes = emptyList(), fingerprint = "b")
        val verifier = PhoneActionVerifier()
        val action = PhoneAction(PhoneActionType.TAP_COORDINATES, x = 10, y = 10)
        assertFalse(verifier.verify(action, before, same))
        assertTrue(verifier.verify(action, before, changed))
    }

    @Test fun semanticIdsRemainPathBased() {
        val root = UiNode("e:0", bounds = UiBounds(0,0,100,100), clickable = false, scrollable = false, editable = false, enabled = true, selected = false,
            children = listOf(UiNode("e:0.0", text = "hello", bounds = UiBounds(0,0,50,50), clickable = true, scrollable = false, editable = false, enabled = true, selected = false)))
        val state = ScreenState(nodes = listOf(root), fingerprint = "x")
        assertEquals("hello", state.flatten().first { it.elementId == "e:0.0" }.text)
    }
}
