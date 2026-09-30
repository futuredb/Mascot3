package com.generativemascot.app.state

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.generativemascot.app.MascotApp
import com.generativemascot.app.ui.MascotTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

class BehaviorSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val behavior = (application as MascotApp).behavior
        setContent {
            MascotTheme {
                val scope = rememberCoroutineScope()
                var config by remember { mutableStateOf(behavior.config.get()) }
                var json by remember { mutableStateOf(behavior.config.text()) }
                var advanced by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf<String?>(null) }
                var goal by remember { mutableStateOf(config.activityGoalValue.toString()) }
                val supported = remember { HealthSignals.available(this) }
                val backgroundSupported = remember { HealthSignals.backgroundAvailable(this) }
                fun save(next: StateConfig) {
                    config = behavior.config.save(stateJson.encodeToString(next))
                    json = behavior.config.text()
                    scope.launch { behavior.configurationChanged() }
                }
                val permission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
                    message = if (granted.isEmpty()) "Без доступа к активности герой работает по времени и погоде."
                        else "Доступ сохранён. Используем только разрешённые данные."
                    scope.launch { behavior.configurationChanged() }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = { finish() }) { Text("Назад") }
                        Text("Поведение героя", style = MaterialTheme.typography.headlineMedium)
                        Text("Приложение и виджет показывают одно состояние. Готовые анимации переключаются бесплатно.")
                        Text("Сигналы активности", style = MaterialTheme.typography.titleLarge)
                        Text("Шаги, сон и тренировки влияют только на поведение героя. Данные остаются на устройстве и не используются для медицинских выводов.")
                        FlagRow("Использовать Health Connect", config.healthTriggersEnabled) { save(config.copy(healthTriggersEnabled = it)) }
                        if (config.healthTriggersEnabled) {
                            FlagRow("Сон и пробуждение", config.sleepTriggersEnabled) { save(config.copy(sleepTriggersEnabled = it)) }
                            FlagRow("Тренировки", config.exerciseTriggersEnabled) { save(config.copy(exerciseTriggersEnabled = it)) }
                            FlagRow("Шаги", config.stepTriggersEnabled) { save(config.copy(stepTriggersEnabled = it)) }
                            if (backgroundSupported) {
                                FlagRow("Активность для виджета в фоне", config.healthBackgroundReadEnabled) {
                                    save(config.copy(healthBackgroundReadEnabled = it))
                                }
                            }
                            FlagRow("Игровая цель", config.activityGoalTriggerEnabled) { save(config.copy(activityGoalTriggerEnabled = it)) }
                            FlagRow("Персональные достижения", config.personalMilestoneTriggerEnabled) { save(config.copy(personalMilestoneTriggerEnabled = it)) }
                            OutlinedTextField(goal, { goal = it.filter(Char::isDigit).take(6) }, label = { Text("Игровая цель: шагов в день") }, singleLine = true)
                            TextButton(onClick = {
                                val count = goal.toLongOrNull()
                                if (count == null || count !in 1..100000) message = "Укажите число от 1 до 100000."
                                else { save(config.copy(activityGoalValue = count)); message = "Игровая цель сохранена. Это не медицинская норма." }
                            }) { Text("Сохранить цель") }
                            Button(onClick = {
                                val required = HealthSignals.requiredPermissions(config).toMutableSet()
                                if (config.healthBackgroundReadEnabled && HealthSignals.backgroundAvailable(this@BehaviorSettingsActivity))
                                    required += androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
                                permission.launch(required)
                            },
                                enabled = supported && HealthSignals.requiredPermissions(config).isNotEmpty()) {
                                Text("Разрешить доступ к активности")
                            }
                            if (!supported) Text("Health Connect недоступен. Время, погода, сеть и зарядка продолжают работать.")
                        }
                        message?.let { Text(it) }
                        TextButton(onClick = { advanced = !advanced }) { Text("Расширенные правила") }
                        if (advanced) {
                            Text("Локальный JSON-конфиг: длительности, веса, ограничения повторов и пороги. Ошибочный конфиг не сохраняется.")
                            OutlinedTextField(json, { json = it.take(100000) }, Modifier.fillMaxWidth().heightIn(min = 240.dp), label = { Text("Конфиг v0.3") })
                            Button(onClick = {
                                runCatching { behavior.config.save(json) }.onSuccess {
                                    config = it; goal = it.activityGoalValue.toString(); json = behavior.config.text()
                                    message = "Правила сохранены."
                                    scope.launch { behavior.configurationChanged() }
                                }.onFailure { message = "Конфиг некорректен. Действующие правила не изменены." }
                            }) { Text("Сохранить правила") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlagRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 12.dp))
        Switch(checked, onChange)
    }
}

/** Required Health Connect rationale entry point; no data requests from this screen. */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MascotTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Доступ к активности", style = MaterialTheme.typography.headlineMedium)
                    Text("Приложение читает разрешённые вами шаги, записи сна и тренировок из Health Connect, чтобы выбирать анимацию героя.")
                    Text("Данные хранятся только на устройстве. Мы не отправляем их на сервер, не включаем значения в аналитику и не делаем медицинских выводов.")
                    Text("Можно отказать в доступе или отозвать его в Health Connect. Основное приложение и виджет продолжат работать без этих данных.")
                    Button(onClick = { finish() }) { Text("Понятно") }
                }
            }
        } }
    }
}
