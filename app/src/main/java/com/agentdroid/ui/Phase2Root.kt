package com.agentdroid.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.agentdroid.AgentDroidApplication
import com.agentdroid.R
import com.agentdroid.settings.AppLanguage
import com.agentdroid.settings.AppTheme
import com.agentdroid.viewmodel.ContainerViewModelFactory
import com.agentdroid.viewmodel.SettingsViewModel
import java.util.Locale

@Composable
fun Phase2Root() {
    val original = LocalContext.current
    val application = original.applicationContext as AgentDroidApplication
    val factory = remember(application) { ContainerViewModelFactory(application.container) }
    val settingsVm: SettingsViewModel = viewModel(factory = factory)
    val settings by settingsVm.settings.collectAsState()
    val localized = remember(settings.language, original) { phase2LocalizedContext(original, settings.language) }
    val dark = when (settings.theme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val systemRtl = original.resources.configuration.locales[0].language.equals("ar", true) || original.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
    val rtl = when (settings.language) { AppLanguage.ARABIC -> true; AppLanguage.ENGLISH -> false; AppLanguage.SYSTEM -> systemRtl }
    val colors = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(localized)
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(localized)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalContext provides localized, LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = colors) { Phase2Navigation(factory) }
    }
}

@Composable
private fun Phase2Navigation(factory: ContainerViewModelFactory) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val topLevel = route in setOf("agent", "workspaces", "more")
    Scaffold(
        bottomBar = {
            if (topLevel) NavigationBar {
                NavigationBarItem(route == "agent", { nav.navigate("agent") { launchSingleTop = true; popUpTo("agent") { inclusive = false } } }, { Icon(Icons.Default.Chat, stringResource(R.string.agent)) }, label = { Text(stringResource(R.string.agent)) })
                NavigationBarItem(route == "workspaces", { nav.navigate("workspaces") { launchSingleTop = true } }, { Icon(Icons.Default.Folder, stringResource(R.string.workspaces)) }, label = { Text(stringResource(R.string.workspaces)) })
                NavigationBarItem(route == "more", { nav.navigate("more") { launchSingleTop = true } }, { Icon(Icons.Default.MoreHoriz, stringResource(R.string.more)) }, label = { Text(stringResource(R.string.more)) })
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "agent", modifier = Modifier.padding(padding)) {
            composable("agent") { Phase2ChatScreen(nav, factory) }
            composable("agent/{conversationId}", arguments = listOf(navArgument("conversationId") { type = NavType.StringType })) { entry -> Phase2ChatScreen(nav, factory, entry.arguments?.getString("conversationId")) }
            composable("workspaces") { Phase2WorkspacesScreen(nav, factory) }
            composable("workspace/{workspaceId}", arguments = listOf(navArgument("workspaceId") { type = NavType.StringType })) { entry -> WorkspaceBrowserScreen(nav, factory, entry.arguments?.getString("workspaceId").orEmpty()) }
            composable("changes/{workspaceId}", arguments = listOf(navArgument("workspaceId") { type = NavType.StringType })) { entry -> WorkspaceChangesScreen(nav, factory, entry.arguments?.getString("workspaceId").orEmpty()) }
            composable(
                "diff/{workspaceId}/{changeSetId}",
                arguments = listOf(navArgument("workspaceId") { type = NavType.StringType }, navArgument("changeSetId") { type = NavType.StringType })
            ) { entry -> DiffScreen(nav, factory, entry.arguments?.getString("workspaceId").orEmpty(), entry.arguments?.getString("changeSetId").orEmpty()) }
            composable("permissions") { PermissionRulesScreen(nav, factory) }
            composable("classic") { AgentDroidRoot() }
            composable("more") {
                Column(Modifier.padding(20.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    ListItem(headlineContent = { Text(stringResource(R.string.agent_permissions_audit)) }, leadingContent = { Icon(Icons.Default.Security, null) })
                    Button({ nav.navigate("permissions") }) { Text(stringResource(R.string.manage_permissions)) }
                    OutlinedButton({ nav.navigate("classic") }) { Text(stringResource(R.string.providers_memory_skills_settings)) }
                }
            }
        }
    }
}

private fun phase2LocalizedContext(context: Context, language: AppLanguage): Context {
    if (language == AppLanguage.SYSTEM) return context
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(if (language == AppLanguage.ARABIC) Locale("ar") else Locale.ENGLISH)
    return context.createConfigurationContext(configuration)
}
