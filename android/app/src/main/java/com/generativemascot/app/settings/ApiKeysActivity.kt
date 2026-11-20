package com.generativemascot.app.settings

import android.os.Bundle
import com.generativemascot.app.ui.FadeSettingsActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.generativemascot.app.MascotApp
import com.generativemascot.app.data.GenerationRoute
import com.generativemascot.app.data.OpenRouterKeyVerifier
import com.generativemascot.app.ui.FigmaCanvas
import com.generativemascot.app.ui.FigmaInk
import com.generativemascot.app.ui.MascotSettingsHeader
import com.generativemascot.app.ui.MascotSystemBars
import com.generativemascot.app.ui.MascotTheme
import com.generativemascot.app.ui.SbSansDisplayMedium
import com.generativemascot.app.ui.SbSansTextMedium
import com.generativemascot.app.ui.SbSansTextRegular
import kotlinx.coroutines.launch

class ApiKeysActivity : FadeSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MascotApp
        setContent {
            MascotTheme {
                ApiKeysScreen(
                    openRouterCustomInitially = app.openRouterSettings.hasUserApiKey(),
                    reactorCustomInitially = app.reactorSettings.hasUserApiKey(),
                    falCustomInitially = app.falSettings.hasUserApiKey(),
                    onSaveOpenRouter = { raw ->
                        val check = OpenRouterKeyVerifier { app.falSettings.apiKey() != null }.verify(raw)
                        app.openRouterSettings.saveApiKey(raw)
                        app.openRouterSettings.setRoute(GenerationRoute.OPENROUTER_DIRECT)
                        check.remainingUsd?.let { "Ключ подключён · доступно $%.2f".format(it) } ?: "Ключ подключён"
                    },
                    onRemoveOpenRouter = { app.openRouterSettings.removeApiKey() },
                    onSaveReactor = { raw -> app.reactorSettings.saveApiKey(raw) },
                    onRemoveReactor = { app.reactorSettings.removeApiKey() },
                    onSaveFal = { raw -> app.falSettings.saveApiKey(raw) },
                    onRemoveFal = { app.falSettings.removeApiKey() },
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
private fun ApiKeysScreen(
    openRouterCustomInitially: Boolean,
    reactorCustomInitially: Boolean,
    falCustomInitially: Boolean,
    onSaveOpenRouter: suspend (String) -> String,
    onRemoveOpenRouter: () -> Unit,
    onSaveReactor: (String) -> Unit,
    onRemoveReactor: () -> Unit,
    onSaveFal: (String) -> Unit,
    onRemoveFal: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    MascotSystemBars(FigmaCanvas, FigmaCanvas)
    var openRouterCustom by rememberSaveable { mutableStateOf(openRouterCustomInitially) }
    var reactorCustom by rememberSaveable { mutableStateOf(reactorCustomInitially) }
    var falCustom by rememberSaveable { mutableStateOf(falCustomInitially) }
    var falDraft by remember { mutableStateOf("") }
    var falMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var openRouterDraft by rememberSaveable { mutableStateOf("") }
    var reactorDraft by rememberSaveable { mutableStateOf("") }
    var openRouterMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var reactorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var checking by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(FigmaCanvas).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            MascotSettingsHeader("API-ключи", onBack = onBack)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Можно подключить свои ключи. Если их нет, приложение продолжит работать со встроенными.",
                    fontFamily = SbSansTextRegular, fontSize = 14.sp, lineHeight = 20.sp,
                    color = FigmaInk.copy(alpha = .64f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                ApiKeyCard(
                    provider = "OpenRouter",
                    description = "Чат и создание персонажей",
                    placeholder = "sk-or-v1-…",
                    value = openRouterDraft,
                    custom = openRouterCustom,
                    busy = checking,
                    message = openRouterMessage,
                    onValueChange = { openRouterDraft = it.trim().take(256); openRouterMessage = null },
                    onSave = saveOpenRouter@{
                        if (openRouterDraft.isBlank()) return@saveOpenRouter
                        checking = true
                        scope.launch {
                            runCatching { onSaveOpenRouter(openRouterDraft) }
                                .onSuccess { message -> openRouterCustom = true; openRouterDraft = ""; openRouterMessage = message }
                                .onFailure { openRouterMessage = it.message ?: "Не удалось проверить ключ" }
                            checking = false
                        }
                    },
                    onRemove = if (openRouterCustom) {{
                        onRemoveOpenRouter(); openRouterCustom = false; openRouterDraft = ""
                        openRouterMessage = "Снова используется встроенный ключ"
                    }} else null,
                )
                ApiKeyCard(
                    provider = "Reactor",
                    description = "Живые реакции Helios",
                    placeholder = "rk_…",
                    value = reactorDraft,
                    custom = reactorCustom,
                    busy = false,
                    message = reactorMessage,
                    onValueChange = { reactorDraft = it.trim().take(256); reactorMessage = null },
                    onSave = {
                        runCatching { onSaveReactor(reactorDraft) }
                            .onSuccess { reactorCustom = true; reactorDraft = ""; reactorMessage = "Ключ сохранён" }
                            .onFailure { reactorMessage = it.message ?: "Не удалось сохранить ключ" }
                    },
                    onRemove = if (reactorCustom) {{
                        onRemoveReactor(); reactorCustom = false; reactorDraft = ""
                        reactorMessage = "Снова используется встроенный ключ"
                    }} else null,
                )
                ApiKeyCard(
                    provider = "fal.ai",
                    description = "Анимация персонажа · MiniMax H3 Max Turbo",
                    placeholder = "Ключ fal.ai",
                    value = falDraft,
                    custom = falCustom,
                    busy = false,
                    message = falMessage,
                    onValueChange = { falDraft = it.trim().take(256); falMessage = null },
                    onSave = {
                        runCatching { onSaveFal(falDraft) }
                            .onSuccess { falCustom = true; falDraft = ""; falMessage = "Ключ сохранён. Доступ к fal.ai не проверялся" }
                            .onFailure { falMessage = it.message ?: "Не удалось сохранить ключ" }
                    },
                    onRemove = if (falCustom) {{
                        onRemoveFal(); falCustom = false; falDraft = ""
                        falMessage = "Снова используется встроенный ключ"
                    }} else null,
                )
                Text("Ключи шифруются Android Keystore и не попадают в резервную копию.",
                    fontFamily = SbSansTextRegular, fontSize = 12.sp, lineHeight = 17.sp,
                    color = FigmaInk.copy(alpha = .44f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun ApiKeyCard(
    provider: String,
    description: String,
    placeholder: String,
    value: String,
    custom: Boolean,
    busy: Boolean,
    message: String?,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, Color(0xFFE0E1E5), RoundedCornerShape(30.dp))
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(FigmaInk), contentAlignment = Alignment.Center) {
                Text(provider.take(1), color = Color.White, fontFamily = SbSansDisplayMedium, fontSize = 18.sp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(provider, fontFamily = SbSansDisplayMedium, fontSize = 18.sp, color = FigmaInk)
                Text(description, fontFamily = SbSansTextRegular, fontSize = 12.sp, color = FigmaInk.copy(alpha = .5f))
            }
            Box(Modifier.size(8.dp).clip(CircleShape)
                .background(if (custom) Color(0xFF3D9B67) else FigmaInk.copy(alpha = .22f)))
        }
        Text(if (custom) "Подключён свой ключ" else "Используется встроенный ключ",
            fontFamily = SbSansTextMedium, fontSize = 13.sp, color = FigmaInk.copy(alpha = .58f))
        Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = .72f)).border(1.dp, Color(0xFFD9DADE), RoundedCornerShape(18.dp))
            .padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = value, onValueChange = onValueChange, singleLine = true,
                enabled = !busy, modifier = Modifier.weight(1f),
                textStyle = TextStyle(fontFamily = SbSansTextRegular, fontSize = 14.sp, color = FigmaInk),
                cursorBrush = SolidColor(FigmaInk),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                decorationBox = { field -> if (value.isBlank()) Text(placeholder, fontFamily = SbSansTextRegular,
                    fontSize = 14.sp, color = FigmaInk.copy(alpha = .34f)); field() })
            TextButton(onClick = { visible = !visible }, enabled = !busy) {
                Text(if (visible) "Скрыть" else "Показать", fontFamily = SbSansTextMedium,
                    fontSize = 12.sp, color = FigmaInk.copy(alpha = .62f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onRemove != null) Box(Modifier.height(44.dp).clip(CircleShape)
                .border(1.dp, FigmaInk.copy(alpha = .18f), CircleShape).clickable(enabled = !busy, onClick = onRemove)
                .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text("Сбросить", fontFamily = SbSansDisplayMedium, fontSize = 13.sp,
                    color = FigmaInk.copy(alpha = if (busy) .3f else .72f))
            }
            Box(Modifier.weight(1f).height(44.dp).clip(CircleShape)
                .background(if (value.isNotBlank() && !busy) FigmaInk else FigmaInk.copy(alpha = .12f))
                .clickable(enabled = value.isNotBlank() && !busy, onClick = onSave), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = FigmaInk, strokeWidth = 2.dp)
                else Text("Сохранить", fontFamily = SbSansDisplayMedium, fontSize = 13.sp,
                    color = if (value.isNotBlank()) Color.White else FigmaInk.copy(alpha = .34f))
            }
        }
        message?.let { Text(it, fontFamily = SbSansTextRegular, fontSize = 12.sp, lineHeight = 16.sp,
            color = FigmaInk.copy(alpha = .62f)) }
    }
}
