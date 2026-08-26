package com.agentdroid

import android.content.Context
import androidx.room.Room
import com.agentdroid.core.ai.ProviderRegistry
import com.agentdroid.core.ai.providers.AnthropicProvider
import com.agentdroid.core.ai.providers.FakeAiProvider
import com.agentdroid.core.ai.providers.GeminiProvider
import com.agentdroid.core.ai.providers.OpenAiProvider
import com.agentdroid.core.ai.providers.OpenRouterProvider
import com.agentdroid.core.ai.providers.CompatibleProvider
import com.agentdroid.data.database.AgentDatabase
import com.agentdroid.data.database.DatabaseMigrations
import com.agentdroid.data.database.RoomConversationRepository
import com.agentdroid.data.database.RoomMemoryRepository
import com.agentdroid.data.database.RoomMessageRepository
import com.agentdroid.data.database.RoomProviderRepository
import com.agentdroid.data.database.RoomSkillRepository
import com.agentdroid.data.database.RoomWorkspaceRepository
import com.agentdroid.security.SecureSecretStore
import com.agentdroid.settings.SettingsRepository

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
}
