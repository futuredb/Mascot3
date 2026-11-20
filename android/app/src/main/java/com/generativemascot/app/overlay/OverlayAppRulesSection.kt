package com.generativemascot.app.overlay

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.generativemascot.app.ui.FigmaInk
import com.generativemascot.app.ui.SbSansDisplayMedium
import com.generativemascot.app.ui.SbSansTextMedium

@Composable
internal fun OverlayAppRulesSection(resumed: Boolean, onPermissions: (() -> Unit)? = null,
    showTitle: Boolean = true) {
    val context = LocalContext.current
    var rules by remember { mutableStateOf(OverlayAppRules.read(context)) }
    var granted by remember { mutableStateOf(OverlayAppRules.hasUsageAccess(context)) }
    var choosing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumed) { if (resumed) granted = OverlayAppRules.hasUsageAccess(context) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(OverlaySize.PREFERENCES, 0)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == OverlayAppRules.MODE_KEY || key == OverlayAppRules.APPS_KEY)
                rules = OverlayAppRules.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Surface(shape = RoundedCornerShape(30.dp), color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFE0E1E5), RoundedCornerShape(30.dp))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showTitle) Text("Где показывать героя", fontFamily = SbSansDisplayMedium,
                fontSize = 18.sp, color = FigmaInk)
            OverlayAppMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = rules.mode == mode, role = Role.RadioButton, onClick = {
                        rules = rules.copy(mode = mode); rules.save(context)
                    }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = rules.mode == mode, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = FigmaInk,
                            unselectedColor = FigmaInk.copy(alpha = .48f)))
                    Text(mode.title, Modifier.padding(start = 10.dp), fontFamily = SbSansTextMedium, color = FigmaInk)
                }
            }
            if (rules.mode != OverlayAppMode.EVERYWHERE) {
                OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (rules.packages.isEmpty()) "Выбрать приложения" else "Приложения · ${rules.packages.size}")
                }
                Text(when {
                    rules.packages.isEmpty() && rules.mode == OverlayAppMode.ONLY_SELECTED ->
                        "Выберите приложения, в которых герой будет появляться. Пока список пуст, он скрыт."
                    rules.packages.isEmpty() -> "Добавьте приложения, в которых герой не должен мешать."
                    else -> "Герой и подсказка переключаются автоматически. Свёрнутая карточка следует тому же правилу."
                }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!granted) {
                    Text("Для переключения разрешите «Доступ к данным об использовании». Пока доступа нет, герой в режиме с выбранными приложениями скрыт.",
                        fontSize = 13.sp)
                    Text("Используем только название открытого приложения. Содержимое экрана не читаем, историю на сервер не отправляем.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        if (onPermissions != null) { onPermissions(); return@TextButton }
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        runCatching { context.startActivity(intent) }.recoverCatching {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }.onFailure { error = "Откройте настройки Android → Специальный доступ → Доступ к данным об использовании → Mascots." }
                    }) { Text("Разрешить автоматическое переключение") }
                } else {
                    Text("Автопереключение доступно · обычно в течение секунды",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("В режиме нескольких окон ориентируемся на последнее активное приложение.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        }
    }
    if (choosing) {
        var selected by remember { mutableStateOf(rules.packages.toSet()) }
        var query by remember { mutableStateOf("") }
        var apps by remember { mutableStateOf<List<OverlayAppChoice>?>(null) }
        var loadError by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            runCatching { withContext(Dispatchers.IO) { overlayAppChoices(context) } }
                .onSuccess { apps = it }.onFailure { loadError = true }
        }
        AlertDialog(onDismissRequest = { choosing = false },
            title = { Text("Выберите приложения") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("Поиск") }, modifier = Modifier.fillMaxWidth())
                    Text("Выбрано: ${selected.size}", fontSize = 12.sp)
                    when {
                        loadError -> Text("Не удалось получить список. Закройте окно и попробуйте ещё раз.")
                        apps == null -> CircularProgressIndicator(Modifier.size(24.dp))
                        else -> {
                            // Preserve selections for temporarily unavailable / uninstalled apps,
                            // but still allow removing them by name instead of trapping the user.
                            val known = apps!!.map { it.packageName }.toSet()
                            val choices = apps!! + (selected - known).sorted().map { OverlayAppChoice(it, it) }
                            val filtered = choices.filter {
                                it.label.contains(query, true) || it.packageName.contains(query, true)
                            }
                            if (filtered.isEmpty()) Text("Ничего не найдено")
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                                items(filtered, key = { it.packageName }) { app ->
                                    val checked = app.packageName in selected
                                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                                        selected = if (checked) selected - app.packageName else selected + app.packageName
                                    }, verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked, onCheckedChange = {
                                            selected = if (it) selected + app.packageName else selected - app.packageName
                                        })
                                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                            Text(app.label, fontSize = 14.sp)
                                            if (app.label != app.packageName)
                                                Text(app.packageName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                rules = rules.copy(packages = selected); rules.save(context); choosing = false
            }, enabled = apps != null) { Text("Готово") } },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Отмена") } })
    }
}
