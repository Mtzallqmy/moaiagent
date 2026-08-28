package com.agentdroid

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agentdroid.core.phone.AgentDroidAccessibilityService
import com.agentdroid.core.phone.PhoneAction
import com.agentdroid.core.phone.PhoneActionType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneAutomationEndToEndTest {
    @Test fun accessibilityEngineTypesTapsAndReadsResult(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AgentDroidApplication>()
        withTimeout(10_000) { while (AgentDroidAccessibilityService.current() == null) delay(100) }
        ActivityScenario.launch(PhoneAutomationTestActivity::class.java).use {
            val input = app.container.phoneAutomation.waitForElement("agentdroid_e2e_input", 8_000)
            assertNotNull("Input must be visible through Accessibility", input)
            val typed = app.container.phoneAutomation.perform(PhoneAction(PhoneActionType.TYPE_TEXT, elementId = input!!.elementId, text = "hello-agent"), 2)
            assertTrue("Typing must be verified: $typed", typed.success)
            val button = app.container.phoneAutomation.waitForElement("agentdroid_e2e_submit", 5_000)
            assertNotNull(button)
            val tapped = app.container.phoneAutomation.perform(PhoneAction(PhoneActionType.TAP_ELEMENT, elementId = button!!.elementId), 2)
            assertTrue("Tap must be verified: $tapped", tapped.success)
            val result = app.container.phoneAutomation.waitForElement("AGENTDROID_PHONE_OK:hello-agent", 5_000)
            assertNotNull("Result must be read back through Accessibility tree", result)
            val state = app.container.phoneAutomation.captureState(includeScreenshot = true)
            assertTrue(state.flatten().any { node -> node.text == "AGENTDROID_PHONE_OK:hello-agent" })
            assertTrue("API 35 emulator should return screenshot evidence", state.screenshotPath != null)
        }
    }
}
