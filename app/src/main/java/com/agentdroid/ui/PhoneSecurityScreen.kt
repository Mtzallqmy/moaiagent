package com.agentdroid.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agentdroid.AgentDroidApplication
import com.agentdroid.core.phone.AgentDroidAccessibilityService
import com.agentdroid.core.phone.ScreenState
import com.agentdroid.integration.ShizukuStatus
import kotlinx.coroutines.launch

@Composable
fun PhoneSecurityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AgentDroidApplication
    val scope = rememberCoroutineScope()
    val arabic = context.resources.configuration.locales[0].language.equals("ar", true)
    var screen by remember { mutableStateOf<ScreenState?>(null) }
    var shizuku by remember { mutableStateOf(ShizukuStatus(false, false)) }
    var root by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            shizuku = app.shizukuCapability.status()
            root = app.rootCapability.available()
            screen = runCatching { app.container.phoneAutomation.captureState(false) }.getOrNull()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(topBar = { TopAppBar(title = { Text(if (arabic) "الهاتف والأمان" else "Phone & Security") }, navigationIcon = { OutlinedButton(onClick = onBack) { Text(if (arabic) "رجوع" else "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(
                if (arabic) "إمكانية الوصول" else "Accessibility",
                if (AgentDroidAccessibilityService.current() != null) (if (arabic) "مفعّلة — أدوات الهاتف جاهزة" else "Enabled — phone tools ready") else (if (arabic) "غير مفعّلة" else "Not enabled")
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(if (arabic) "فتح إعدادات الوصول" else "Open Accessibility settings") }
                OutlinedButton(onClick = ::refresh) { Text(if (arabic) "تحديث" else "Refresh") }
            }
            StatusCard(if (arabic) "الحالة الحالية" else "Current screen", "${screen?.packageName ?: "—"}\n${screen?.activityName ?: "—"}\n${if (arabic) "العناصر" else "Nodes"}: ${screen?.flatten()?.size ?: 0}")
            StatusCard(
                "Shizuku",
                when {
                    shizuku.permissionGranted -> if (arabic) "جاهز ومصرّح" else "Ready and authorized"
                    shizuku.binderAvailable -> if (arabic) "متاح ويحتاج إذن المستخدم" else "Available; user permission required"
                    else -> if (arabic) "غير متاح — التطبيق يعمل بدونه" else "Unavailable — AgentDroid works without it"
                }
            )
            if (shizuku.binderAvailable && !shizuku.permissionGranted && !shizuku.preV11) {
                Button(onClick = {
                    scope.launch {
                        val granted = runCatching { app.shizukuCapability.requestPermission() }.getOrDefault(false)
                        message = if (granted) (if (arabic) "تم منح إذن Shizuku" else "Shizuku permission granted") else (if (arabic) "لم يتم منح الإذن" else "Permission not granted")
                        refresh()
                    }
                }) { Text(if (arabic) "طلب إذن Shizuku" else "Request Shizuku permission") }
            }
            StatusCard(if (arabic) "Root اختياري" else "Optional root", if (root) (if (arabic) "تم اكتشاف su — كل عملية تحتاج موافقة حساسة" else "su detected — every action requires sensitive approval") else (if (arabic) "غير موجود — لا يؤثر على الوظائف الأساسية" else "Not detected — core features are unaffected"))
            StatusCard(
                if (arabic) "حماية التطبيقات الحساسة" else "Sensitive-app protection",
                if (arabic) "البنوك، محافظ العملات، تطبيقات المصادقة، مدراء كلمات المرور، إدارة الجهاز ومثبت الحزم محظورة افتراضيًا. تجاوز الحظر يبقى SENSITIVE ويحتاج موافقة صريحة." else "Banking, crypto wallets, authenticators, password managers, device administration and package installers are blocked by default. Overrides remain SENSITIVE and require explicit approval."
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
