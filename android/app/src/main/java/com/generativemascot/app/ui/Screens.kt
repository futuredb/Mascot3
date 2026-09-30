package com.generativemascot.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import android.widget.VideoView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.generativemascot.app.data.ContextDto
import com.generativemascot.app.data.GenerationRoute
import com.generativemascot.app.data.MascotDto
import com.generativemascot.app.data.resolveMediaUrl
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Cream = Color(0xFFFFF6F0)
private val Ink = Color(0xFF2B2430)
private val Blush = Color(0xFFF48CA8)
private val Lilac = Color(0xFF7C5CFF)

@Composable
private fun MascotImage(
    url: String?,
    description: String,
    modifier: Modifier,
    playbackMode: String = "idle_pulse",
    motionMs: Int = 1600,
    pauseMs: Int = 0,
    animationUrl: String? = null,
) {
    val media = resolveMediaUrl(animationUrl)
    val isVideo = media != null && (media.endsWith(".mp4") || media.endsWith(".webm"))
    val isLoopImage = media != null && (media.endsWith(".gif") || media.endsWith(".webp"))
    if (isVideo && media != null) {
        AndroidView(
            modifier = modifier.background(Cream),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(Uri.parse(media))
                    setOnPreparedListener { player ->
                        player.isLooping = true
                        player.start()
                    }
                }
            },
            update = { view ->
                if (view.tag != media) {
                    view.tag = media
                    view.setVideoURI(Uri.parse(media))
                }
            },
        )
        return
    }
    if (isLoopImage && media != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(media)
                .crossfade(false)
                .build(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = modifier.background(Cream),
        )
        return
    }
    val bob = remember(url, playbackMode, motionMs) { Animatable(0f) }
    val breathe = remember(url, playbackMode, motionMs) { Animatable(1f) }
    LaunchedEffect(url, playbackMode, motionMs, pauseMs) {
        bob.snapTo(0f)
        breathe.snapTo(1f)
        val half = (motionMs / 2).coerceIn(400, 1200)
        suspend fun pulse() {
            coroutineScope {
                launch {
                    bob.animateTo(10f, tween(half, easing = EaseInOutSine))
                    bob.animateTo(0f, tween(half, easing = EaseInOutSine))
                }
                launch {
                    breathe.animateTo(1.03f, tween(half, easing = EaseInOutSine))
                    breathe.animateTo(1f, tween(half, easing = EaseInOutSine))
                }
            }
        }
        if (playbackMode == "clip") {
            pulse()
            return@LaunchedEffect
        }
        while (true) {
            pulse()
            if (pauseMs > 0) delay(pauseMs.toLong())
        }
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(resolveMediaUrl(url))
            .crossfade(true)
            .build(),
        contentDescription = description,
        modifier = modifier
            .graphicsLayer {
                translationY = -bob.value
                scaleX = breathe.value
                scaleY = breathe.value
            }
            .background(Cream),
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun MascotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Lilac,
            onPrimary = Color.White,
            background = Cream,
            surface = Color.White,
            onBackground = Ink,
            onSurface = Ink,
        ),
        content = content,
    )
}

@Composable
private fun Screen(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(24.dp)
    ) { content() }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Lilac, disabledContainerColor = Lilac.copy(0.4f)),
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    Screen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Spacer(Modifier.height(48.dp))
                Box(
                    Modifier.size(88.dp).clip(CircleShape).background(Blush.copy(0.4f)),
                    contentAlignment = Alignment.Center,
                ) { Text("◕‿◕", fontSize = 28.sp) }
                Spacer(Modifier.height(28.dp))
                Text("Ваш герой живёт на домашнем экране", fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Один раз создайте уникального маскота. Дальше он сам знает, когда знакомиться, спать, быть довольным и когда стать ночной сущностью. Эмоцию выбирать не нужно.",
                    fontSize = 17.sp,
                    color = Ink.copy(0.72f),
                    lineHeight = 24.sp,
                )
            }
            PrimaryButton("Продолжить", onClick = onContinue)
        }
    }
}

@Composable
fun CreateScreen(loading: Boolean, error: String?, onCreate: () -> Unit) {
    Screen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Spacer(Modifier.height(36.dp))
                Text("Создать моего героя", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Параметры внешности задавать не нужно. Герой будет случайным и больше никому не повторится. Погода берётся по Москве.",
                    color = Ink.copy(0.7f),
                    fontSize = 17.sp,
                )
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(error, color = Color(0xFFB42318))
                }
            }
            PrimaryButton("Создать моего героя", enabled = !loading, onClick = onCreate)
        }
    }
}

@Composable
fun ProgressScreen(status: String, onPoll: () -> Unit = {}) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            onPoll()
        }
    }
    Screen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Lilac, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(24.dp))
            Text("Герой собирается", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(statusLabel(status), color = Ink.copy(0.65f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun MeetScreen(
    mascot: MascotDto,
    name: String,
    onName: (String) -> Unit,
    onAccept: () -> Unit,
    onReroll: () -> Unit,
    canReroll: Boolean,
    busy: Boolean,
) {
    Screen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(12.dp))
                MascotImage(
                    url = mascot.previewUrl,
                    description = "Превью героя",
                    modifier = Modifier.size(260.dp).clip(RoundedCornerShape(40.dp)),
                    animationUrl = mascot.previewAnimationUrl,
                )
                Spacer(Modifier.height(20.dp))
                Text("Это ваш герой", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Имя, необязательно") },
                    shape = RoundedCornerShape(20.dp),
                )
            }
            Column {
                PrimaryButton("Это мой герой", enabled = !busy, onClick = onAccept)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onReroll,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                ) { Text("Сгенерировать снова") }
            }
        }
    }
}

@Composable
fun HeroScreen(
    mascot: MascotDto,
    context: ContextDto?,
    replacing: Boolean,
    onWidget: () -> Unit,
    onReplace: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit = {},
) {
    LaunchedEffect(mascot.id) {
        while (true) {
            delay(5_000)
            onRefresh()
        }
    }
    Screen {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(mascot.name ?: "Герой", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                MascotImage(
                    url = context?.stillUrl ?: mascot.previewUrl,
                    description = "Текущее состояние героя",
                    modifier = Modifier.size(280.dp).clip(RoundedCornerShape(44.dp)),
                    playbackMode = context?.playbackMode ?: "idle_pulse",
                    motionMs = 1600,
                    pauseMs = 0,
                    animationUrl = context?.animationUrl ?: mascot.previewAnimationUrl,
                )
                Spacer(Modifier.height(20.dp))
                Text(stateTitle(context?.stateKey), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(context?.reasonText ?: "Состояние обновится само", textAlign = TextAlign.Center, color = Ink.copy(0.7f))
                Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull(context?.cityName, context?.weatherClass, context?.temperature?.let { "${it.toInt()}°" }).joinToString(" · "),
                    color = Ink.copy(0.5f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onReplace,
                    enabled = !replacing,
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Ink.copy(0.08f)),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Ink),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Заменить героя", modifier = Modifier.size(26.dp))
                }
                IconButton(
                    onClick = onWidget,
                    enabled = !replacing,
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(Ink),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                ) {
                    Icon(Icons.Outlined.Widgets, contentDescription = "Установить виджет", modifier = Modifier.size(28.dp))
                }
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Ink.copy(0.08f)),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Ink),
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Настройки", modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    generationRoute: GenerationRoute,
    openRouterKeyPresent: Boolean,
    openRouterKeyDraft: String,
    openRouterChecking: Boolean,
    openRouterMessage: String?,
    generationLocked: Boolean,
    onOpenRouterKey: (String) -> Unit,
    onUseTeamServer: () -> Unit,
    onUseOpenRouter: () -> Unit,
    onBack: () -> Unit,
    onBehaviorSettings: () -> Unit = {},
) {
    var openRouterChosen by rememberSaveable {
        mutableStateOf(generationRoute == GenerationRoute.OPENROUTER_DIRECT)
    }
    Screen {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                }
                Text("Оплата генерации", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text("Выберите один вариант", color = Ink.copy(alpha = .62f))
            TextButton(onClick = onBehaviorSettings) { Text("Поведение героя") }
            Spacer(Modifier.height(20.dp))
            GenerationOption(
                title = "Наш сервер",
                description = "Всё уже настроено. Генерацию оплачивает команда.",
                selected = !openRouterChosen,
                enabled = !generationLocked && !openRouterChecking,
                onClick = {
                    openRouterChosen = false
                    onUseTeamServer()
                },
            )
            Spacer(Modifier.height(12.dp))
            GenerationOption(
                title = "Свой OpenRouter",
                description = "Вставьте свой ключ — оплата пойдёт с вашего баланса.",
                selected = openRouterChosen,
                enabled = !generationLocked && !openRouterChecking,
                onClick = { openRouterChosen = true },
            )

            if (openRouterChosen) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = openRouterKeyDraft,
                    onValueChange = onOpenRouterKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (openRouterKeyPresent) "Новый ключ, если хотите заменить" else "Ключ OpenRouter") },
                    placeholder = { Text("sk-or-v1-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !generationLocked && !openRouterChecking,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = when {
                        openRouterChecking -> "Проверяем ключ…"
                        generationRoute == GenerationRoute.OPENROUTER_DIRECT && openRouterKeyDraft.isBlank() ->
                            "OpenRouter подключён"
                        openRouterKeyPresent && openRouterKeyDraft.isBlank() -> "Использовать сохранённый ключ"
                        else -> "Подключить OpenRouter"
                    },
                    enabled = !generationLocked && !openRouterChecking &&
                        (openRouterKeyDraft.isNotBlank() ||
                            openRouterKeyPresent && generationRoute != GenerationRoute.OPENROUTER_DIRECT),
                    onClick = onUseOpenRouter,
                )
                openRouterMessage?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(message, color = Ink.copy(alpha = .68f), fontSize = 14.sp)
                }
            }
            if (generationLocked) {
                Spacer(Modifier.height(16.dp))
                Text("Сейчас идёт генерация. Выбор можно поменять после её завершения.", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GenerationOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Lilac else Ink.copy(alpha = .14f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Lilac.copy(alpha = .10f) else Color.White,
            contentColor = Ink,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (selected) Text("✓", color = Lilac, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(description, color = Ink.copy(alpha = .62f), fontSize = 14.sp, textAlign = TextAlign.Start)
        }
    }
}

fun statusLabel(status: String) = when (status) {
    "BASE_GENERATING", "DRAFT", "BASE_QA", "UNIQUE_CHECK" -> "Рисуем героя и проверяем, что он уникальный"
    "AWAITING_ACCEPTANCE" -> "Герой готов к знакомству"
    "POSES_GENERATING", "ANIMATIONS_GENERATING", "ASSET_QA", "PACKAGING" -> "Собираем состояния"
    "READY" -> "Пакет готов"
    "FAILED_FINAL" -> "Не получилось. Можно попробовать ещё раз"
    else -> "Работает автоматически"
}

fun stateTitle(key: String?) = when (key) {
    "idle" -> "Живёт"
    "rest", "resting" -> "Отдыхает"
    "eating" -> "Ест"
    "sad" -> "Грустит"
    "dancing", "playful" -> "Играет"
    "acquaintance" -> "Знакомится"
    "working" -> "Работает"
    "sleep", "sleeping", "sleep_loop" -> "Спит"
    "happy", "joyful" -> "Радуется"
    "thinking" -> "Думает"
    "at_glass" -> "У стекла"
    "watching" -> "Наблюдает"
    "bored" -> "Скучает"
    "angry" -> "Злится"
    "refusal" -> "Отказывается"
    "frightened" -> "Пугается"
    "curious" -> "Любопытствует"
    "tender" -> "Нежничает"
    "stretch", "stretching" -> "Потягивается"
    "greeting" -> "Приветствует"
    "signature_move" -> "Фирменный жест"
    "surprised" -> "Удивляется"
    "rainy" -> "С зонтиком"
    "cool" -> "Прохладно"
    "cold" -> "Холодно"
    "birthday" -> "Празднует"
    "night_entity" -> "Ночная сущность"
    else -> "Доволен"
}
