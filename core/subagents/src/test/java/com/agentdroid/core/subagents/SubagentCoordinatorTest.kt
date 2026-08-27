package com.agentdroid.core.subagents

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentCoordinatorTest {
    @Test fun mainAgentResearchThenReviewPipelineProducesFinalResult() = runBlocking {
        val factory = SubagentFactory { profile -> object : Subagent {
            override val role = profile.role
            override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload = when (role) {
                SubagentRole.RESEARCH -> SubagentResultPayload("Research result", sourceReferences = listOf("source-1"))
                SubagentRole.REVIEW -> {
                    assertEquals("Research result", task.context.sections[ContextSection.SUBAGENT_RESULTS])
                    SubagentResultPayload("Final reviewed answer")
                }
                else -> error("Unexpected role")
            }
        } }
        val coordinator = coordinator(factory)
        val research = coordinator.delegate(SubagentRole.RESEARCH, "Collect sources", context())
        val review = coordinator.delegate(
            SubagentRole.REVIEW,
            "Review research",
            context(ContextSection.SUBAGENT_RESULTS to research.summary)
        )

        assertEquals(SubagentStatus.COMPLETED, research.status)
        assertEquals(listOf("source-1"), research.sourceReferences)
        assertEquals("Final reviewed answer", review.summary)
    }

    @Test fun mainDelegatesResearchThenResearchDelegatesReview() = runBlocking {
        lateinit var researchTask: SubagentTask
        val factory = SubagentFactory { profile ->
            when (profile.role) {
                SubagentRole.RESEARCH -> object : Subagent {
                    override val role = SubagentRole.RESEARCH
                    override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload {
                        researchTask = task
                        val review = scope.delegate(
                            SubagentRole.REVIEW,
                            "Review the evidence",
                            task.context.copy(sections = task.context.sections + (ContextSection.SUBAGENT_RESULTS to "research summary"))
                        )
                        return SubagentResultPayload(
                            summary = "Research and review completed: ${review.summary}",
                            sourceReferences = listOf("source-1")
                        )
                    }
                }
                SubagentRole.REVIEW -> object : Subagent {
                    override val role = SubagentRole.REVIEW
                    override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope) =
                        SubagentResultPayload("Evidence approved")
                }
                else -> error("Unexpected role")
            }
        }
        val coordinator = coordinator(factory, limits = SubagentLimits(maxConcurrentSubagents = 2))
        val result = coordinator.delegate(
            role = SubagentRole.RESEARCH,
            objective = "Compare Android PTY libraries",
            context = context(
                ContextSection.TASK_SUMMARY to "Phase 3 runtime architecture",
                ContextSection.SELECTED_FILES to "must not leak to research"
            )
        )

        assertEquals(SubagentStatus.COMPLETED, result.status)
        assertTrue(result.summary.contains("Evidence approved"))
        assertEquals(listOf("source-1"), result.sourceReferences)
        assertFalse(researchTask.context.sections.containsKey(ContextSection.SELECTED_FILES))
        assertEquals(2, coordinator.timeline.value.size)
        val review = coordinator.timeline.value.single { it.role == SubagentRole.REVIEW }
        val research = coordinator.timeline.value.single { it.role == SubagentRole.RESEARCH }
        assertEquals(research.subagentId, review.parentSubagentId)
        assertTrue(coordinator.timeline.value.all { it.status == SubagentStatus.COMPLETED })
    }

    @Test fun retryThenSuccessReturnsAttemptCount() = runBlocking {
        var calls = 0
        val factory = SubagentFactory { profile -> object : Subagent {
            override val role = profile.role
            override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload {
                calls++
                if (calls == 1) error("temporary source outage")
                return SubagentResultPayload("Recovered")
            }
        } }
        val coordinator = coordinator(factory, limits = SubagentLimits(maxRetries = 1))
        val result = coordinator.delegate(SubagentRole.RESEARCH, "Research", context())

        assertEquals(SubagentStatus.COMPLETED, result.status)
        assertEquals(2, result.attempts)
        assertEquals("Recovered", result.summary)
    }

    @Test fun failureCanFallbackToReviewRole() = runBlocking {
        val factory = SubagentFactory { profile -> object : Subagent {
            override val role = profile.role
            override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload {
                if (role == SubagentRole.RESEARCH) error("research backend unavailable")
                return SubagentResultPayload("Fallback reviewed available material")
            }
        } }
        val coordinator = coordinator(
            factory,
            limits = SubagentLimits(maxRetries = 0),
            recovery = RetryThenFallbackPolicy(mapOf(SubagentRole.RESEARCH to SubagentRole.REVIEW), retriesBeforeFallback = 0)
        )
        val result = coordinator.delegate(SubagentRole.RESEARCH, "Research", context())

        assertEquals(SubagentStatus.COMPLETED, result.status)
        assertEquals(SubagentRole.REVIEW, result.role)
        assertEquals(2, result.attempts)
    }

    @Test fun permanentFailureReturnsSafeFailureSummaryWithoutReasoning() = runBlocking {
        val coordinator = coordinator(
            SubagentFactory { profile -> object : Subagent {
                override val role = profile.role
                override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload =
                    error("source unavailable")
            } },
            limits = SubagentLimits(maxRetries = 0)
        )
        val result = coordinator.delegate(SubagentRole.RESEARCH, "Research", context())

        assertEquals(SubagentStatus.FAILED, result.status)
        assertEquals(SubagentFailureCode.EXECUTION_FAILED, result.failure?.code)
        assertTrue(result.summary.contains("source unavailable"))
        val timeline = coordinator.timeline.value.single()
        assertEquals("source unavailable", timeline.failureSummary)
        assertFalse(timeline.toString().contains("instructions="))
    }

    @Test fun rejectsDepthAndTotalLimits() = runBlocking {
        val limitedFactory = SubagentFactory { profile -> object : Subagent {
            override val role = profile.role
            override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload {
                scope.delegate(SubagentRole.REVIEW, "child")
                return SubagentResultPayload("unexpected")
            }
        } }
        val coordinator = coordinator(limitedFactory, limits = SubagentLimits(maxSubagents = 1, maxDelegationDepth = 1, maxRetries = 0))
        val limited = coordinator.delegate(SubagentRole.RESEARCH, "root", context())
        assertEquals(SubagentStatus.FAILED, limited.status)
        assertTrue(limited.summary.contains("Subagent limit"))

        val depthCoordinator = coordinator(successFactory(), limits = SubagentLimits(maxDelegationDepth = 1))
        val tooDeep = SubagentTask("deep", SubagentRole.REVIEW, "deep", context(), delegationDepth = 2)
        assertTrue(runCatching { depthCoordinator.delegate(tooDeep) }.exceptionOrNull() is DelegationDepthExceeded)
    }

    @Test fun rejectsConcurrentExecutionPastLimit() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factory = SubagentFactory { profile -> object : Subagent {
            override val role = profile.role
            override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload {
                entered.complete(Unit)
                release.await()
                return SubagentResultPayload("done")
            }
        } }
        val coordinator = coordinator(factory, limits = SubagentLimits(maxConcurrentSubagents = 1))
        val first = async { coordinator.delegate(SubagentRole.REVIEW, "first", context()) }
        entered.await()
        val secondFailure = runCatching { coordinator.delegate(SubagentRole.REVIEW, "second", context()) }.exceptionOrNull()
        assertTrue(secondFailure is SubagentLimitReached)
        release.complete(Unit)
        assertEquals(SubagentStatus.COMPLETED, first.await().status)
    }

    @Test fun roleToolWhitelistAndToolBudgetAreEnforced() = runBlocking {
        var gatewayCalls = 0
        val deniedFactory = SubagentFactory { profile -> object : Subagent {
            override val role = profile.role
            override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload {
                scope.executeTool("write_file", buildJsonObject {})
                return SubagentResultPayload("unexpected")
            }
        } }
        val denied = DefaultSubagentCoordinator(
            deniedFactory,
            SubagentToolGateway { _, _, _ -> gatewayCalls++; SubagentToolResult(true, "ok") },
            limits = SubagentLimits(maxRetries = 0)
        ).delegate(SubagentRole.RESEARCH, "research", context())
        assertEquals(SubagentFailureCode.TOOL_NOT_ALLOWED, denied.failure?.code)
        assertEquals(0, gatewayCalls)

        val tinyProfile = DefaultSubagentProfiles.REVIEW.copy(allowedTools = setOf("read_file"), toolCallLimit = 1)
        val budgetFactory = SubagentFactory { profile -> object : Subagent {
            override val role = profile.role
            override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload {
                scope.executeTool("read_file", buildJsonObject {})
                scope.executeTool("read_file", buildJsonObject {})
                return SubagentResultPayload("unexpected")
            }
        } }
        val budget = DefaultSubagentCoordinator(
            budgetFactory,
            SubagentToolGateway { _, _, _ -> SubagentToolResult(true, "ok") },
            profiles = SubagentProfileProvider { tinyProfile },
            limits = SubagentLimits(defaultToolCallLimit = 5, maxRetries = 0)
        ).delegate(SubagentRole.REVIEW, "review", context())
        assertEquals(SubagentFailureCode.TOOL_LIMIT_REACHED, budget.failure?.code)
    }

    @Test fun contextSubsetHonorsAllowedSectionsAndCharacterBudget() {
        val profile = DefaultSubagentProfiles.RESEARCH.copy(maxContextCharacters = 5)
        val subset = ContextSubsetter().subset(
            context(
                ContextSection.TASK_SUMMARY to "123456789",
                ContextSection.SELECTED_FILES to "secret"
            ),
            profile
        )
        assertEquals("12345", subset.sections[ContextSection.TASK_SUMMARY])
        assertFalse(subset.sections.containsKey(ContextSection.SELECTED_FILES))
    }

    @Test fun taskDurationIsBoundedAndReported() = runBlocking {
        val slow = SubagentFactory { profile -> object : Subagent {
            override val role = profile.role
            override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope): SubagentResultPayload {
                delay(500)
                return SubagentResultPayload("late")
            }
        } }
        val result = coordinator(
            slow,
            limits = SubagentLimits(maxTaskDurationMs = 25, maxRetries = 0)
        ).delegate(SubagentRole.REVIEW, "bounded", context())

        assertEquals(SubagentStatus.FAILED, result.status)
        assertEquals(SubagentFailureCode.TIMED_OUT, result.failure?.code)
    }

    @Test fun skillBindingAddsGuidanceButCannotExpandToolsByDefault() = runBlocking {
        var observedProfile: SubagentProfile? = null
        val bindingRepository = InMemorySkillRoleBindingRepository(
            listOf(SkillRoleBinding("android-reviewer", SubagentRole.REVIEW, "Check Android lifecycle usage", setOf("run_command")))
        )
        val coordinator = DefaultSubagentCoordinator(
            factory = SubagentFactory { profile ->
                observedProfile = profile
                object : Subagent {
                    override val role = profile.role
                    override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope) = SubagentResultPayload("reviewed")
                }
            },
            toolGateway = SubagentToolGateway { _, _, _ -> SubagentToolResult(true, "ok") },
            skillBindings = bindingRepository
        )
        coordinator.delegate(SubagentRole.REVIEW, "review", context())

        assertTrue(observedProfile?.instructions?.contains("Check Android lifecycle usage") == true)
        assertFalse(observedProfile?.allowedTools?.contains("run_command") == true)
    }

    private fun coordinator(
        factory: SubagentFactory,
        limits: SubagentLimits = SubagentLimits(),
        recovery: SubagentRecoveryPolicy = RetryThenStopPolicy
    ) = DefaultSubagentCoordinator(
        factory = factory,
        toolGateway = SubagentToolGateway { _, _, _ -> SubagentToolResult(true, "ok") },
        limits = limits,
        recoveryPolicy = recovery,
        idGenerator = object {
            var next = 0
            fun id() = "id-${++next}"
        }.let { ids -> { ids.id() } }
    )

    private fun successFactory() = SubagentFactory { profile -> object : Subagent {
        override val role = profile.role
        override suspend fun execute(task: SubagentTask, scope: SubagentExecutionScope) = SubagentResultPayload("done")
    } }

    private fun context(vararg values: Pair<ContextSection, String>) =
        SubagentContext("workspace", "conversation", mapOf(*values))
}
