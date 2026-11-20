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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.generativemascot.app.ui.FigmaInk
import com.generativemascot.app.ui.SbSansDisplayMedium
import com.generativemascot.app.ui.SbSansTextRegular

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
    val complete get() = draw && notifications
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
    compact: Boolean = false,
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
    val next = listOf(OverlayAccess.DRAW, OverlayAccess.NOTIFICATIONS).firstOrNull { !permissions.granted(it) }
    val confirm = { if (next == null) onContinue?.invoke() ?: onDismiss() else request(next) }
    if (compact) CompactOverlayPermissionPrompt(next, error, confirm, onDismiss)
    else com.generativemascot.app.ui.MascotConfirmationSheet(
        title = "Разрешения для героя",
        message = if (next == null) "Герой готов появиться поверх приложений."
            else "${next.title}. ${next.reason} После настройки вернитесь в приложение.",
        confirmLabel = if (next == null) { if (onContinue == null) "Готово" else "Показать героя" } else "Разрешить",
        onConfirm = confirm,
        onDismiss = onDismiss,
        dismissLabel = "Позже",
        error = error)
}

@Composable
private fun CompactOverlayPermissionPrompt(next: OverlayAccess?, error: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Popup(alignment = Alignment.BottomCenter, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        Surface(modifier = Modifier.widthIn(max = 353.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
            shape = RoundedCornerShape(28.dp), color = Color(0xFFF4F4F4), shadowElevation = 12.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (next == null) "Всё готово" else "Быстрая настройка",
                    fontFamily = SbSansDisplayMedium, fontSize = 18.sp, color = FigmaInk)
                Text(when (next) {
                    OverlayAccess.DRAW -> "Разреши герою появляться поверх других приложений."
                    OverlayAccess.NOTIFICATIONS -> "Разреши управление героем из уведомления."
                    else -> "Герой готов к работе."
                }, fontFamily = SbSansTextRegular, fontSize = 14.sp, lineHeight = 19.6.sp,
                    color = FigmaInk.copy(alpha = .62f))
                if (!error.isNullOrBlank()) Text(error, fontFamily = SbSansTextRegular,
                    fontSize = 12.sp, color = Color(0xFFC43535))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text("Позже", fontFamily = SbSansDisplayMedium, color = FigmaInk.copy(alpha = .58f))
                    }
                    Button(onClick = onConfirm, shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = FigmaInk),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
                        Text(if (next == null) "Готово" else "Включить",
                            fontFamily = SbSansDisplayMedium, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
