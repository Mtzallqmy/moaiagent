package com.agentdroid.core.tasks

/** Produces and revises short action labels, never model reasoning or chain-of-thought. */
interface TaskPlanner {
    fun create(summary: String, stepTitles: List<String>, now: Long): TaskPlan
    fun revise(plan: TaskPlan, summary: String = plan.summary, remainingStepTitles: List<String>, now: Long): TaskPlan
}

class ConciseTaskPlanner(private val ids: TaskIdGenerator) : TaskPlanner {
    override fun create(summary: String, stepTitles: List<String>, now: Long): TaskPlan =
        TaskPlan(normalize(summary), steps(stepTitles), updatedAt = now)

    override fun revise(plan: TaskPlan, summary: String, remainingStepTitles: List<String>, now: Long): TaskPlan {
        require(plan.steps.none { it.status == TaskStatus.RUNNING }) { "Pause the running step before revising the plan" }
        val retained = plan.steps.filter { it.status == TaskStatus.COMPLETED }
        return TaskPlan(
            summary = normalize(summary),
            steps = (retained + steps(remainingStepTitles)).mapIndexed { index, step -> step.copy(position = index) },
            revision = plan.revision + 1,
            updatedAt = now
        )
    }

    private fun steps(titles: List<String>): List<TaskStep> {
        require(titles.isNotEmpty())
        return titles.mapIndexed { index, title -> TaskStep(ids.nextId(), normalize(title), position = index) }
    }

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ").also { require(it.isNotBlank()) }
}
