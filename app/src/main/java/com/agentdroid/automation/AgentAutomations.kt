package com.agentdroid.automation

import android.content.Context
import androidx.work.*
import com.agentdroid.AgentDroidApplication
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

@Serializable
data class WorkspaceAutomation(
    val id: String,
    val title: String,
    val goal: String,
    val workspaceId: String,
    val conversationId: String,
    val repeatMinutes: Long? = null,
    val initialDelayMinutes: Long = 0,
    val requiresNetwork: Boolean = false,
    val requiresCharging: Boolean = false
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,96}")))
        require(title.isNotBlank() && goal.isNotBlank())
        require(workspaceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(conversationId.isNotBlank())
        require(initialDelayMinutes >= 0)
        repeatMinutes?.let { require(it >= 15) { "Android periodic work minimum is 15 minutes" } }
    }
}

class AutomationManager(context: Context) {
    private val work = WorkManager.getInstance(context.applicationContext)

    fun schedule(spec: WorkspaceAutomation) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (spec.requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .setRequiresCharging(spec.requiresCharging)
            .build()
        val input = workDataOf(
            AgentAutomationWorker.KEY_ID to spec.id,
            AgentAutomationWorker.KEY_TITLE to spec.title,
            AgentAutomationWorker.KEY_GOAL to spec.goal,
            AgentAutomationWorker.KEY_WORKSPACE to spec.workspaceId,
            AgentAutomationWorker.KEY_CONVERSATION to spec.conversationId
        )
        val repeat = spec.repeatMinutes
        if (repeat == null) {
            val request = OneTimeWorkRequestBuilder<AgentAutomationWorker>()
                .setConstraints(constraints)
                .setInputData(input)
                .setInitialDelay(spec.initialDelayMinutes, TimeUnit.MINUTES)
                .addTag(TAG)
                .addTag("automation:${spec.id}")
                .build()
            work.enqueueUniqueWork("agentdroid-automation-${spec.id}", ExistingWorkPolicy.REPLACE, request)
        } else {
            val request = PeriodicWorkRequestBuilder<AgentAutomationWorker>(repeat, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(input)
                .setInitialDelay(spec.initialDelayMinutes, TimeUnit.MINUTES)
                .addTag(TAG)
                .addTag("automation:${spec.id}")
                .build()
            work.enqueueUniquePeriodicWork("agentdroid-automation-${spec.id}", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    fun cancel(id: String) = work.cancelUniqueWork("agentdroid-automation-$id")
    fun cancelAll() = work.cancelAllWorkByTag(TAG)

    companion object { private const val TAG = "agentdroid-automation" }
}

/**
 * Android may delay this work because of Doze, battery optimizations and scheduler quotas.
 * The worker intentionally creates a durable Task only; it never starts an unbounded autonomous
 * Agent loop or foreground service while the app is background-restricted.
 */
class AgentAutomationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val workspace = inputData.getString(KEY_WORKSPACE) ?: return Result.failure()
        val conversation = inputData.getString(KEY_CONVERSATION) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE)?.take(240)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val goal = inputData.getString(KEY_GOAL)?.take(8_000)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val application = applicationContext as? AgentDroidApplication ?: return Result.failure()
        return runCatching {
            application.container.taskEngine.create(
                title = title,
                workspaceId = workspace,
                conversationId = conversation,
                summary = "Scheduled automation request: $goal",
                steps = listOf("Review scheduled automation request", "Execute when AgentDroid is active and permissions allow it")
            )
        }.fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        const val KEY_ID = "automationId"
        const val KEY_TITLE = "title"
        const val KEY_GOAL = "goal"
        const val KEY_WORKSPACE = "workspaceId"
        const val KEY_CONVERSATION = "conversationId"
    }
}
