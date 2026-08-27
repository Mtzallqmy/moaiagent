package com.agentdroid.core.permissions

import com.agentdroid.core.agent.PermissionDecision
import com.agentdroid.core.agent.PermissionGateway
import com.agentdroid.core.agent.PermissionOutcome
import com.agentdroid.core.agent.PermissionRequest
import com.agentdroid.core.agent.PermissionScope
import com.agentdroid.core.agent.RiskLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class PermissionRule(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val workspaceId: String? = null,
    val decision: PermissionDecision,
    val scope: PermissionScope = PermissionScope.ALWAYS,
    val createdAt: Long = System.currentTimeMillis()
)

interface PermissionRuleStore {
    suspend fun list(): List<PermissionRule>
    suspend fun save(rule: PermissionRule)
    suspend fun delete(id: String)
}

class InMemoryPermissionRuleStore(initial: List<PermissionRule> = emptyList()) : PermissionRuleStore {
    private val values = ConcurrentHashMap(initial.associateBy { it.id })
    override suspend fun list(): List<PermissionRule> = values.values.sortedByDescending { it.createdAt }
    override suspend fun save(rule: PermissionRule) { values[rule.id] = rule }
    override suspend fun delete(id: String) { values.remove(id) }
}

@Serializable
data class PermissionResponse(val decision: PermissionDecision, val scope: PermissionScope = PermissionScope.ONCE)

fun interface PermissionPrompter { suspend fun prompt(request: PermissionRequest): PermissionResponse }

class PermissionRequestCoordinator : PermissionPrompter {
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<PermissionRequest?>(null)
    val pending: StateFlow<PermissionRequest?> = _pending.asStateFlow()
    @Volatile private var answer: CompletableDeferred<PermissionResponse>? = null

    override suspend fun prompt(request: PermissionRequest): PermissionResponse = mutex.withLock {
        val deferred = CompletableDeferred<PermissionResponse>()
        answer = deferred; _pending.value = request
        try { deferred.await() } finally {
            if (answer === deferred) answer = null
            if (_pending.value?.requestId == request.requestId) _pending.value = null
        }
    }

    fun resolve(decision: PermissionDecision, scope: PermissionScope = PermissionScope.ONCE) { answer?.complete(PermissionResponse(decision, scope)) }
    fun denyPending() = resolve(PermissionDecision.DENY, PermissionScope.ONCE)
}

class PermissionEngine(
    private val ruleStore: PermissionRuleStore,
    private val prompter: PermissionPrompter,
    private val defaultPolicy: (RiskLevel) -> PermissionDecision = ::defaultPermissionForRisk
) : PermissionGateway {
    private data class SessionKey(val sessionId: String, val workspaceId: String, val permissionKey: String)
    private val sessionRules = ConcurrentHashMap<SessionKey, PermissionDecision>()

    override suspend fun authorize(request: PermissionRequest): PermissionOutcome {
        val dynamicKey = request.ruleKey?.takeIf(::isSafePermissionKey)
        val permissionKey = dynamicKey ?: request.definition.name
        val key = SessionKey(request.sessionId, request.workspaceId, permissionKey)
        sessionRules[key]?.let { return PermissionOutcome(it, PermissionScope.SESSION, "session") }

        val stored = ruleStore.list().asSequence()
            .filter { it.scope == PermissionScope.ALWAYS }
            .filter { rule ->
                if (dynamicKey != null) rule.toolName == dynamicKey
                else rule.toolName == request.definition.name || rule.toolName == "*"
            }
            .filter { it.workspaceId == null || it.workspaceId == request.workspaceId }
            .sortedWith(compareByDescending<PermissionRule> { it.workspaceId != null }.thenByDescending { it.createdAt })
            .firstOrNull()

        if (stored?.decision == PermissionDecision.ALLOW || stored?.decision == PermissionDecision.DENY) {
            return PermissionOutcome(stored.decision, PermissionScope.ALWAYS, "stored")
        }

        val policyDecision = if (stored?.decision == PermissionDecision.ASK) PermissionDecision.ASK else defaultPolicy(request.definition.riskLevel)
        return when (policyDecision) {
            PermissionDecision.ALLOW -> PermissionOutcome(PermissionDecision.ALLOW, PermissionScope.ONCE, "risk-default")
            PermissionDecision.DENY -> PermissionOutcome(PermissionDecision.DENY, PermissionScope.ONCE, "risk-default")
            PermissionDecision.ASK -> {
                val response = prompter.prompt(request)
                if (response.decision == PermissionDecision.ALLOW) {
                    when (response.scope) {
                        PermissionScope.ONCE -> Unit
                        PermissionScope.SESSION -> sessionRules[key] = PermissionDecision.ALLOW
                        PermissionScope.ALWAYS -> ruleStore.save(PermissionRule(toolName = permissionKey, workspaceId = request.workspaceId, decision = PermissionDecision.ALLOW, scope = PermissionScope.ALWAYS))
                    }
                }
                PermissionOutcome(response.decision, response.scope, if (stored?.decision == PermissionDecision.ASK) "stored-ask" else "prompt")
            }
        }
    }

    override fun clearSession(sessionId: String) { sessionRules.keys.removeIf { it.sessionId == sessionId } }

    suspend fun setAlways(toolName: String, workspaceId: String?, decision: PermissionDecision) {
        require(isSafePermissionKey(toolName)) { "Unsafe permission rule key" }
        ruleStore.save(PermissionRule(toolName = toolName, workspaceId = workspaceId, decision = decision, scope = PermissionScope.ALWAYS))
    }

    suspend fun removeRule(ruleId: String) = ruleStore.delete(ruleId)
    suspend fun rules(): List<PermissionRule> = ruleStore.list()

    private fun isSafePermissionKey(value: String): Boolean {
        if (value.length !in 1..300 || value.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        val stars = value.count { it == '*' }
        return stars == 0 || (stars == 1 && value.endsWith(" *") && value.substringBeforeLast(" *").isNotBlank())
    }
}

fun defaultPermissionForRisk(risk: RiskLevel): PermissionDecision = when (risk) {
    RiskLevel.SAFE -> PermissionDecision.ALLOW
    RiskLevel.MODIFY, RiskLevel.DESTRUCTIVE, RiskLevel.EXTERNAL, RiskLevel.SENSITIVE -> PermissionDecision.ASK
}
