package com.agentdroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class AgentDroidSmokeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun launcherShowsAgentDroidShell() {
        composeRule.onNodeWithText("AgentDroid").assertIsDisplayed()
    }
}
