package com.agentdroid.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AppLanguage { SYSTEM, ARABIC, ENGLISH }
enum class AppTheme { SYSTEM, LIGHT, DARK }
data class AppSettings(val language: AppLanguage = AppLanguage.SYSTEM, val theme: AppTheme = AppTheme.SYSTEM, val dynamicColor: Boolean = true, val defaultProvider: String? = null, val defaultModel: String? = null, val developerMode: Boolean = false)

private val Context.dataStore by preferencesDataStore("agentdroid_settings")

class SettingsRepository(private val context: Context) {
    private object Keys { val language = stringPreferencesKey("language"); val theme = stringPreferencesKey("theme"); val dynamicColor = booleanPreferencesKey("dynamic_color"); val defaultProvider = stringPreferencesKey("default_provider"); val defaultModel = stringPreferencesKey("default_model"); val developerMode = booleanPreferencesKey("developer_mode") }
    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> AppSettings(AppLanguage.valueOf(p[Keys.language] ?: AppLanguage.SYSTEM.name), AppTheme.valueOf(p[Keys.theme] ?: AppTheme.SYSTEM.name), p[Keys.dynamicColor] ?: true, p[Keys.defaultProvider], p[Keys.defaultModel], p[Keys.developerMode] ?: false) }
    suspend fun setLanguage(value: AppLanguage) = context.dataStore.edit { it[Keys.language] = value.name }
    suspend fun setTheme(value: AppTheme) = context.dataStore.edit { it[Keys.theme] = value.name }
    suspend fun setDynamicColor(value: Boolean) = context.dataStore.edit { it[Keys.dynamicColor] = value }
    suspend fun setDefaultProvider(value: String?) = context.dataStore.edit { if (value == null) it.remove(Keys.defaultProvider) else it[Keys.defaultProvider] = value }
    suspend fun setDefaultModel(value: String?) = context.dataStore.edit { if (value == null) it.remove(Keys.defaultModel) else it[Keys.defaultModel] = value }
    suspend fun setDeveloperMode(value: Boolean) = context.dataStore.edit { it[Keys.developerMode] = value }
}
