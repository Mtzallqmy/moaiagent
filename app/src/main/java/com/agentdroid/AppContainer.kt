package com.agentdroid

import android.content.Context
import androidx.room.Room
import com.agentdroid.core.ai.ProviderRegistry
import com.agentdroid.core.ai.providers.AnthropicProvider
import com.agentdroid.core.ai.providers.CompatibleProvider
import com.agentdroid.core.ai.providers.FakeAiProvider
import com.agentdroid.core.ai.providers.GeminiProvider
import com.agentdroid.core.ai.providers.OpenAiProvider
import com.agentdroid.core.ai.providers.OpenRouterProvider
import com.agentdroid.core.agent.ToolRegistry
import com.agentdroid.core.permissions.PermissionEngine
import com.agentdroid.core.permissions.PermissionRequestCoordinator
import com.agentdroid.core.workspace.ChangeSetManager
import com.agentdroid.core.workspace.DiffEngine
import com.agentdroid.core.workspace.WorkspaceFileSystem
import com.agentdroid.core.workspace.WorkspaceServices
import com.agentdroid.core.workspace.createWorkspaceToolRegistry
import com.agentdroid.data.database.AgentDatabase
import com.agentdroid.data.database.AuditRepository
import com.agentdroid.data.database.DatabaseMigrations
import com.agentdroid.data.database.RoomAuditSink
import com.agentdroid.data.database.RoomChangeSetStore
import com.agentdroid.data.database.RoomConversationRepository
import com.agentdroid.data.database.RoomMemoryRepository
import com.agentdroid.data.database.RoomMessageRepository
import com.agentdroid.data.database.RoomPermissionRuleStore
import com.agentdroid.data.database.RoomProviderRepository
import com.agentdroid.data.database.RoomSkillRepository
import com.agentdroid.data.database.RoomWorkspaceRepository
import com.agentdroid.security.SecureSecretStore
import com.agentdroid.settings.SettingsRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AgentDatabase = Room.databaseBuilder(appContext, AgentDatabase::class.java, "agentdroid.db")
        .addMigrations(*DatabaseMigrations.ALL)
        .build()
    val secretStore = SecureSecretStore(appContext)
    val providerRegistry = ProviderRegistry(buildList { add(OpenAiProvider()); add(AnthropicProvider()); add(GeminiProvider()); add(OpenRouterProvider()); add(CompatibleProvider()); if (BuildConfig.DEBUG) add(FakeAiProvider()) })
    val conversations = RoomConversationRepository(database.conversations(), database.messages())
    val messages = RoomMessageRepository(database.messages())
    val settings = SettingsRepository(appContext)
    val providers = RoomProviderRepository(database.providers())
    val workspaces = RoomWorkspaceRepository(database.workspaces())
    val memory = RoomMemoryRepository(database.memory())
    val skills = RoomSkillRepository(database.skills())

    val permissionRuleStore = RoomPermissionRuleStore(database.permissionRules())
    val permissionCoordinator = PermissionRequestCoordinator()
    val permissionEngine = PermissionEngine(permissionRuleStore, permissionCoordinator)
    val auditSink = RoomAuditSink(database.auditLogs())
    val auditRepository = AuditRepository(database.auditLogs())
    val changeSetStore = RoomChangeSetStore(database.changeSets())
    val diffEngine = DiffEngine()

    private val fileSystems = ConcurrentHashMap<String, WorkspaceFileSystem>()
    private val changeManagers = ConcurrentHashMap<String, ChangeSetManager>()

    val workspaceServices: WorkspaceServices = object : WorkspaceServices {
        override fun fileSystem(workspaceId: String): WorkspaceFileSystem = workspaceFileSystem(workspaceId)
        override fun changeSets(workspaceId: String): ChangeSetManager = changeSetManager(workspaceId)
    }
    val toolRegistry: ToolRegistry = createWorkspaceToolRegistry(workspaceServices, diffEngine)

    fun workspaceRoot(workspaceId: String): File {
        require(workspaceId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid workspace id" }
        val base = File(appContext.filesDir, "workspaces")
        if (!base.exists()) base.mkdirs()
        return File(base, workspaceId)
    }

    fun workspaceFileSystem(workspaceId: String): WorkspaceFileSystem = fileSystems.getOrPut(workspaceId) {
        WorkspaceFileSystem(workspaceRoot(workspaceId))
    }

    fun changeSetManager(workspaceId: String): ChangeSetManager = changeManagers.getOrPut(workspaceId) {
        ChangeSetManager(workspaceId, workspaceFileSystem(workspaceId), changeSetStore, diffEngine)
    }

    fun deleteWorkspaceFiles(workspaceId: String): Boolean {
        fileSystems.remove(workspaceId)
        changeManagers.remove(workspaceId)
        val root = workspaceRoot(workspaceId)
        return !root.exists() || root.deleteRecursively()
    }
}
