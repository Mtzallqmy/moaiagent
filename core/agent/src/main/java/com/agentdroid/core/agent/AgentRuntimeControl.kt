package com.agentdroid.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Process-wide execution control shared by the Agent loop and Android accessibility overlay.
 * Pause is cooperative and is checked before every model turn and before every tool action.
 * Stop is immediate: it cancels the active Agent coroutine and therefore also cancels provider
 * network calls through HttpTransport.
 */
enum class AgentRuntimeState { IDLE, RUNNING, PAUSED, STOPPED }

object AgentRuntimeControl {
    private val lock = Any()
    private val _state = MutableStateFlow(AgentRuntimeState.IDLE)
    val state: StateFlow<AgentRuntimeState> = _state.asStateFlow()

    @Volatile private var activeSessionId: String? = null
    @Volatile private var activeJob: Job? = null

    fun begin(sessionId: String, job: Job?) {
        synchronized(lock) {
            activeJob?.takeIf { it !== job && it.isActive }?.cancel(CancellationException("Superseded by a new Agent session"))
            activeSessionId = sessionId
            activeJob = job
            _state.value = AgentRuntimeState.RUNNING
        }
    }

    fun pause(): Boolean = synchronized(lock) {
        if (_state.value != AgentRuntimeState.RUNNING) return@synchronized false
        _state.value = AgentRuntimeState.PAUSED
        true
    }

    fun resume(): Boolean = synchronized(lock) {
        if (_state.value != AgentRuntimeState.PAUSED) return@synchronized false
        _state.value = AgentRuntimeState.RUNNING
        true
    }

    fun stop(): Boolean {
        val job = synchronized(lock) {
            if (_state.value == AgentRuntimeState.IDLE || _state.value == AgentRuntimeState.STOPPED) return false
            _state.value = AgentRuntimeState.STOPPED
            activeJob
        }
        job?.cancel(CancellationException("Agent stopped by user"))
        return true
    }

    suspend fun checkpoint() {
        val resumed = state.first { it != AgentRuntimeState.PAUSED }
        if (resumed == AgentRuntimeState.STOPPED) throw CancellationException("Agent stopped by user")
    }

    fun finish(sessionId: String) {
        synchronized(lock) {
            if (activeSessionId != sessionId) return
            activeSessionId = null
            activeJob = null
            _state.value = AgentRuntimeState.IDLE
        }
    }

    fun activeSessionId(): String? = activeSessionId
}
