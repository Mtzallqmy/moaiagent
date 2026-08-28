package com.agentdroid

import android.content.ClipData
import android.content.ClipboardManager
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
import com.agentdroid.core.artifacts.ArtifactServices
import com.agentdroid.core.artifacts.ArtifactWorkspaceProvider
import com.agentdroid.core.artifacts.CitationSourceCatalog
import com.agentdroid.core.artifacts.CitationValidator
import com.agentdroid.core.artifacts.FileArtifactRepository
import com.agentdroid.core.artifacts.createArtifactTools
import com.agentdroid.core.browser.WebViewBrowserEngine
import com.agentdroid.core.browser.createBrowserAgentTools
import com.agentdroid.core.git.GitServices
import com.agentdroid.core.git.JGitEngine
import com.agentdroid.core.git.createGitTools
import com.agentdroid.core.localai.FileLocalModelManager
import com.agentdroid.core.localai.LlamaCppEngine
import com.agentdroid.core.localai.LocalAiProvider
import com.agentdroid.core.permissions.PermissionEngine
import com.agentdroid.core.permissions.PermissionRequestCoordinator
import com.agentdroid.core.runtime.CommandPolicy
import com.agentdroid.core.runtime.DefaultProcessRunner
import com.agentdroid.core.runtime.ProcessManager
import com.agentdroid.core.runtime.RuntimeComponent
import com.agentdroid.core.runtime.RuntimeDiscovery
import com.agentdroid.core.runtime.RuntimeLimits
import com.agentdroid.core.runtime.RuntimeServices
import com.agentdroid.core.runtime.createRuntimeTools
import com.agentdroid.core.research.DefaultResearchEngine
import com.agentdroid.core.research.DuckDuckGoInstantAnswerProvider
import com.agentdroid.core.research.OkHttpResearchSourceFetcher
import com.agentdroid.core.research.createResearchAgentTools
import com.agentdroid.core.terminal.TerminalClipboard
import com.agentdroid.core.terminal.TermuxTerminalManager
import com.agentdroid.core.workspace.ChangeSetManager
import com.agentdroid.core.workspace.DiffEngine
import com.agentdroid.core.workspace.WorkspaceFileSystem
import com.agentdroid.core.workspace.WorkspaceServices
import com.agentdroid.core.workspace.createWorkspaceToolRegistry
import com.agentdroid.core.tasks.ConciseTaskPlanner
import com.agentdroid.core.tasks.InMemoryTaskRepository
import com.agentdroid.core.tasks.TaskEngine
import com.agentdroid.core.tasks.TaskIdGenerator
import com.agentdroid.core.tasks.createTaskTools
import com.agentdroid.core.model.ProviderKind
import com.agentdroid.data.database.AgentDatabase
import com.agentdroid.data.database.AuditRepository
import com.agentdroid.data.database.DatabaseMigrations
import com.agentdroid.data.database.ProviderConfigEntity
import com.agentdroid.data.database.RoomAuditSink
import com.agentdroid.data.database.RoomChangeSetStore
import com.agentdroid.data.database.RoomConversationRepository
import com.agentdroid.data.database.RoomMemoryRepository
import com.agentdroid.data.database.RoomArtifactMetadataStore
import com.agentdroid.data.database.RoomBrowserMetadataStore
import com.agentdroid.data.database.RoomMessageRepository
import com.agentdroid.data.database.RoomPermissionRuleStore
import com.agentdroid.data.database.RoomProcessMetadataStore
import com.agentdroid.data.database.RoomProviderRepository
import com.agentdroid.data.database.RoomResearchSessionRepository
import com.agentdroid.data.database.RoomSkillRepository
import com.agentdroid.data.database.RoomTaskPersistence
import com.agentdroid.data.database.RoomSubagentDelegationEventStore
import com.agentdroid.data.database.Phase4RecoveryCoordinator
import com.agentdroid.data.database.RoomTerminalSessionMetadataStore
import com.agentdroid.data.database.RoomWorkspaceRepository
import com.agentdroid.security.SecureSecretStore
import com.agentdroid.settings.SettingsRepository
import com.agentdroid.integration.ArtifactBrowserScreenshotSink
import com.agentdroid.integration.PersistedArtifactRepository
import com.agentdroid.integration.PersistedBrowserSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AgentDatabase = Room.databaseBuilder(appContext, AgentDatabase::class.java, "agentdroid.db")
        .addMigrations(*DatabaseMigrations.ALL)
        .build()
    val secretStore = SecureSecretStore(appContext)
    val localModelManager = FileLocalModelManager(
        File(appContext.filesDir, "local-models"),
        listOf(LlamaCppEngine())
    )
    val localAiProvider = LocalAiProvider(localModelManager)
    val providerRegistry = ProviderRegistry(buildList {
        add(OpenAiProvider())
        add(AnthropicProvider())
        add(GeminiProvider())
        add(OpenRouterProvider())
        add(CompatibleProvider())
        add(localAiProvider)
        if (BuildConfig.DEBUG) add(FakeAiProvider())
    })
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

    val runtimeLimits = RuntimeLimits()
    val processMetadataStore = RoomProcessMetadataStore(database.processes())
    val processRunner = DefaultProcessRunner(runtimeLimits)
    val processManager = ProcessManager(processRunner, processMetadataStore, runtimeLimits)
    val commandPolicy = CommandPolicy()
    val gitEngine = JGitEngine()
    val terminalMetadataStore = RoomTerminalSessionMetadataStore(database.terminalSessions())

    private val taskIds = TaskIdGenerator { UUID.randomUUID().toString() }
    val taskPersistence = RoomTaskPersistence(database)
    val taskRepository = InMemoryTaskRepository(ids = taskIds, persistence = taskPersistence)
    val taskEngine = TaskEngine(taskRepository, ConciseTaskPlanner(taskIds))
    val subagentEvents = RoomSubagentDelegationEventStore(database.subagentEvents())

    val researchSessions = RoomResearchSessionRepository(database)
    val researchEngine = DefaultResearchEngine(
        searchProvider = DuckDuckGoInstantAnswerProvider(),
        sourceFetcher = OkHttpResearchSourceFetcher(),
        repository = researchSessions
    )

    private val citationValidator = CitationValidator(CitationSourceCatalog { sessionId, sourceId, canonicalUrl ->
        researchSessions.get(sessionId)?.sources?.any { source ->
            source.id == sourceId && canonicalResearchUrl(source.url) == canonicalResearchUrl(canonicalUrl)
        } == true
    })
    private val artifactFiles = FileArtifactRepository(
        ArtifactWorkspaceProvider(::workspaceRoot),
        citationValidator
    )
    val artifactRepository = PersistedArtifactRepository(
        artifactFiles,
        RoomArtifactMetadataStore(database.artifacts())
    )
    private val browserScreenshotSink = ArtifactBrowserScreenshotSink(::workspaceRoot, artifactRepository)
    val browserEngine = WebViewBrowserEngine(appContext, screenshotSink = browserScreenshotSink)
    val browserMetadata = RoomBrowserMetadataStore(database)
    val browserSessions = PersistedBrowserSessionService(browserEngine, browserMetadata, applicationScope)

    private val fileSystems = ConcurrentHashMap<String, WorkspaceFileSystem>()
    private val changeManagers = ConcurrentHashMap<String, ChangeSetManager>()

    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val terminalClipboard = object : TerminalClipboard {
        override fun copy(text: String) { clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text)) }
        override fun paste(): String? = clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
    }

    val terminalManager = TermuxTerminalManager(::workspaceRoot, terminalMetadataStore, terminalClipboard)

    val runtimeDiscovery = RuntimeDiscovery(
        processRunner,
        appContext.cacheDir,
        gitFallback = { RuntimeComponent("git", "Git", true, "JGit embedded", "embedded:jgit") }
    )

    val workspaceServices: WorkspaceServices = object : WorkspaceServices {
        override fun fileSystem(workspaceId: String): WorkspaceFileSystem = workspaceFileSystem(workspaceId)
        override fun changeSets(workspaceId: String): ChangeSetManager = changeSetManager(workspaceId)
    }
    val runtimeServices: RuntimeServices = object : RuntimeServices {
        override val processManager: ProcessManager get() = this@AppContainer.processManager
        override val commandPolicy: CommandPolicy get() = this@AppContainer.commandPolicy
        override val limits: RuntimeLimits get() = this@AppContainer.runtimeLimits
        override fun workspaceRoot(workspaceId: String): File = this@AppContainer.workspaceRoot(workspaceId)
    }
    val gitServices: GitServices = object : GitServices {
        override val engine get() = gitEngine
        override fun workspaceRoot(workspaceId: String): File = this@AppContainer.workspaceRoot(workspaceId)
    }

    val toolRegistry: ToolRegistry = createWorkspaceToolRegistry(workspaceServices, diffEngine).also { registry ->
        registry.registerAll(createRuntimeTools(runtimeServices))
        registry.registerAll(createGitTools(gitServices))
        registry.registerAll(createBrowserAgentTools(browserSessions))
        registry.registerAll(createTaskTools(taskEngine))
        registry.registerAll(createResearchAgentTools(researchEngine))
        registry.registerAll(createArtifactTools(ArtifactServices { artifactRepository }))
    }

    init {
        applicationScope.launch {
            ensureLocalProviderConfig()
            Phase4RecoveryCoordinator(taskPersistence, subagentEvents).recover()
            taskEngine.restore()
        }
    }

    private suspend fun ensureLocalProviderConfig() {
        val current = providers.get(LOCAL_PROVIDER_ID)
        val defaultModel = localModelManager.defaultModel()?.id
        if (current == null) {
            providers.save(
                ProviderConfigEntity(
                    id = LOCAL_PROVIDER_ID,
                    name = "Local",
                    kind = ProviderKind.LOCAL.name,
                    baseUrl = null,
                    modelId = defaultModel,
                    secretAlias = null,
                    organizationId = null,
                    appName = null,
                    siteUrl = null,
                    customHeadersJson = "{}",
                    enabled = true
                )
            )
        } else if (current.modelId == null && defaultModel != null) {
            providers.setModel(LOCAL_PROVIDER_ID, defaultModel)
        }
    }

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

    private fun canonicalResearchUrl(raw: String): String = runCatching {
        val uri = URI(raw.trim()).normalize()
        URI(
            uri.scheme?.lowercase(Locale.ROOT), null, uri.host?.lowercase(Locale.ROOT),
            uri.port, uri.path, uri.query, null
        ).toASCIIString()
    }.getOrDefault(raw.trim())

    companion object { const val LOCAL_PROVIDER_ID = "local-provider" }
}
