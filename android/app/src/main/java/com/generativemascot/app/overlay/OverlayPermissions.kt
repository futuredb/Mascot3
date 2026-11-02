package com.generativemascot.app.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal enum class OverlayAccess(val title: String, val reason: String) {
    DRAW("Поверх других приложений", "Чтобы герой и подсказки появлялись на главном экране и поверх приложений."),
    USAGE("Доступ к данным об использовании", "Чтобы автоматически скрывать и показывать героя в выбранных приложениях."),
    NOTIFICATIONS("Уведомления", "Чтобы управлять героем из шторки уведомлений.")
}

internal data class OverlayPermissionState(val draw: Boolean, val usage: Boolean, val notifications: Boolean) {
    fun granted(access: OverlayAccess) = when (access) {
        OverlayAccess.DRAW -> draw
        OverlayAccess.USAGE -> usage
        OverlayAccess.NOTIFICATIONS -> notifications
    }
    val complete get() = draw && usage && notifications
    fun canShow(context: Context): Boolean {
        val rules = OverlayAppRules.read(context)
        return draw && (!(rules.needsTracking || rules.mode == OverlayAppMode.ONLY_SELECTED) || usage)
    }
    companion object {
        fun read(context: Context) = OverlayPermissionState(
            Settings.canDrawOverlays(context), OverlayAppRules.hasUsageAccess(context),
            NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
}

internal object OverlayPermissionIntro {
    private const val KEY = "permission_intro_v1"
    fun needed(context: Context) = !context.getSharedPreferences(OverlaySize.PREFERENCES, 0).getBoolean(KEY, false)
    fun seen(context: Context) {
        context.getSharedPreferences(OverlaySize.PREFERENCES, 0).edit().putBoolean(KEY, true).apply()
    }
}

/** The same visible checklist is used on first launch, in settings and from the widget. */
@Composable
internal fun OverlayPermissionsDialog(
    activity: ComponentActivity,
    onDismiss: () -> Unit,
    onContinue: (() -> Unit)? = null,
) {
    var permissions by remember { mutableStateOf(OverlayPermissionState.read(activity)) }
    var error by remember { mutableStateOf<String?>(null) }
    val preferences = remember { activity.getSharedPreferences(OverlaySize.PREFERENCES, 0) }
    val refresh = { permissions = OverlayPermissionState.read(activity) }
    val systemSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    fun request(access: OverlayAccess) {
        error = null
        val target = when (access) {
            OverlayAccess.DRAW -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
            OverlayAccess.USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            OverlayAccess.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33 && (!preferences.getBoolean("notification_requested", false) ||
                        activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
                    preferences.edit().putBoolean("notification_requested", true).apply()
                    notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
        }
        runCatching { systemSettings.launch(target) }.recoverCatching {
            systemSettings.launch(Intent(target.action).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
        }.onFailure { error = "Не удалось открыть настройки Android. Попробуйте ещё раз." }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Разрешения для героя") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Настройте доступы один раз. После каждого шага возвращайтесь сюда — статус обновится автоматически.")
                OverlayPermissionChecklist(permissions, ::request)
                Text("Для автопереключения нужно только название открытого приложения. Содержимое экрана не читается.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (permissions.complete) {
                TextButton(onClick = { onContinue?.invoke() ?: onDismiss() }) {
                    Text(if (onContinue == null) "Готово" else "Показать героя")
                }
            } else {
                TextButton(onClick = { request(OverlayAccess.entries.first { !permissions.granted(it) }) }) {
                    Text("Настроить доступы")
                }
            }
        },
        dismissButton = {
            Row {
                if (!permissions.complete && onContinue != null && permissions.canShow(activity)) {
                    TextButton(onClick = onContinue) { Text("Показать сейчас") }
                }
                if (!permissions.complete || onContinue != null)
                    TextButton(onClick = onDismiss) { Text(if (onContinue == null) "Позже" else "Отмена") }
            }
        }
    )
}

@Composable
internal fun OverlayPermissionChecklist(state: OverlayPermissionState, onRequest: (OverlayAccess) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OverlayAccess.entries.forEach { access ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(access.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(access.reason, fontSize = 13.sp)
                    if (state.granted(access)) Text("✓  Разрешено", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    else TextButton(onClick = { onRequest(access) }, contentPadding = PaddingValues(0.dp)) {
                        Text("Разрешить")
                    }
                }
            }
        }
    }
}
