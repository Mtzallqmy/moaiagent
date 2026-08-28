package com.agentdroid.integration

import com.agentdroid.AppContainer
import com.agentdroid.core.agent.AgentModelClient
import com.agentdroid.core.agent.AgentSession
import com.agentdroid.core.agent.ContextSnapshot
import com.agentdroid.core.agent.ToolRegistry
import com.agentdroid.core.runtime.RuntimeCapabilityEvidence
import com.agentdroid.core.runtime.RuntimeVerifier
import com.agentdroid.core.tasks.ModelDrivenPlanner
import com.agentdroid.core.tasks.ModelPlanningResult
import com.agentdroid.core.tasks.PlannerInput
import com.agentdroid.core.tasks.Task
import com.agentdroid.core.tasks.TaskIdGenerator
import java.util.UUID

/**
 * App-level composition for Phase 5 planning. It deliberately derives capabilities from the
 * registry and execution evidence instead of hard-coded language/runtime claims.
 */
class ModelPlanningCoordinator(
    private val container: AppContainer,
    private val model: AgentModelClient,
    private val registry: ToolRegistry,
    private val ids: TaskIdGenerator = TaskIdGenerator { UUID.randomUUID().toString() }
) {
    data class Outcome(
        val task: Task,
        val planning: ModelPlanningResult?,
        val runtimeEvidence: RuntimeCapabilityEvidence?
    )

    suspend fun ensurePlan(
        session: AgentSession,
        goal: String,
        context: ContextSnapshot
    ): Outcome {
        val existing = container.taskEngine.list(session.workspaceId, session.conversationId)
            .firstOrNull { !it.status.isTerminal }
        if (existing != null) return Outcome(existing, planning = null, runtimeEvidence = null)

        val verifier = RuntimeVerifier(container.processRunner, container.workspaceRoot(session.workspaceId))
        val runtimeEvidence = verifier.verify(container.runtimeDiscovery.list())
        val toolCapabilities = registry.toolsForMode(session.mode).map { "tool.${it.name}" }
        val capabilities = (toolCapabilities + runtimeEvidence.plannerCapabilities()).distinct().sorted()
        val planning = ModelDrivenPlanner(model, session.modelId, ids).create(
            PlannerInput(
                goal = goal,
                projectMemory = context.memories,
                skills = context.skills,
                currentCapabilities = capabilities,
                workspaceSummary = context.workspaceSummary
            ),
            now = System.currentTimeMillis()
        )
        val task = container.taskEngine.createFromPlan(
            title = planning.structuredPlan.summary.take(240),
            workspaceId = session.workspaceId,
            conversationId = session.conversationId,
            plan = planning.taskPlan
        )
        return Outcome(task, planning, runtimeEvidence)
    }
}
