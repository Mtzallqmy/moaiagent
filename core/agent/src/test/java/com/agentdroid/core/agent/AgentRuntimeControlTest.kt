package com.agentdroid.core.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeControlTest {
    @Test fun `checkpoint waits while paused and resumes cooperatively`(): Unit = runBlocking {
        AgentRuntimeControl.begin("test-pause", coroutineContext[kotlinx.coroutines.Job])
        assertTrue(AgentRuntimeControl.pause())
        var passed = false
        val waiter = launch { AgentRuntimeControl.checkpoint(); passed = true }
        delay(80)
        assertFalse(passed)
        assertTrue(AgentRuntimeControl.resume())
        withTimeout(1_000) { waiter.join() }
        assertTrue(passed)
        AgentRuntimeControl.finish("test-pause")
        assertEquals(AgentRuntimeState.IDLE, AgentRuntimeControl.state.value)
    }

    @Test fun `stop cancels active agent job`(): Unit = runBlocking {
        val child = launch {
            AgentRuntimeControl.begin("test-stop", coroutineContext[kotlinx.coroutines.Job])
            try {
                delay(10_000)
            } finally {
                AgentRuntimeControl.finish("test-stop")
            }
        }
        delay(50)
        assertTrue(AgentRuntimeControl.stop())
        withTimeout(1_000) { child.join() }
        assertTrue(child.isCancelled)
        assertEquals(AgentRuntimeState.IDLE, AgentRuntimeControl.state.value)
    }
}
