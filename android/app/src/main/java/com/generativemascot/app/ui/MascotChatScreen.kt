package com.generativemascot.app.ui

import android.util.Base64
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.Context
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.R
import com.generativemascot.app.data.resolveMediaUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

@Composable
fun MascotChatScreen(mascotId: String, heroName: String, previewUrl: String?, animationVideoUrl: String?,
    animationFrames: List<String>, animationFps: Int,
    animationVideoUrls: Map<String, String>, neutralFrameUrl: String?, active: Boolean = true,
    openCapabilities: Boolean = false, onCapabilitiesOpened: () -> Unit = {},
    openBehavior: Boolean = false, onBehaviorOpened: () -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    val historyStore = remember { ChatHistoryStore(context) }
    val messages = remember(mascotId) { mutableStateListOf<ChatMessage>().apply { addAll(historyStore.read(mascotId)) } }
    LaunchedEffect(active, openCapabilities, mascotId) {
        if (active && openCapabilities) {
            if (messages.lastOrNull()?.capabilities != true) {
                messages.add(ChatMessage(false, "", capabilities = true))
                historyStore.write(mascotId, messages)
            }
            onCapabilitiesOpened()
        }
    }
    val service = remember(mascotId, heroName) { DirectMascotChat(heroName, com.generativemascot.app.data.HeroLocalStore.current?.characterChoice(mascotId)?.let { choice ->
        com.generativemascot.app.data.characterPersonalityById(choice.style)?.tone
    }, messages.toList(), com.generativemascot.app.data.HeroLocalStore.current?.baseFile(mascotId)) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val interactionPreferences = remember { context.getSharedPreferences("mascot_chat_interaction", Context.MODE_PRIVATE) }
    var interactionId by remember(mascotId) { mutableStateOf(interactionPreferences.getString(mascotId, "request_guidance") ?: "request_guidance") }
    var showInteractionSettings by remember { mutableStateOf(false) }
    var showInteractionScreen by remember(mascotId) { mutableStateOf(false) }
    LaunchedEffect(active, openBehavior, mascotId) {
        if (active && openBehavior) {
            showInteractionScreen = true
            onBehaviorOpened()
        }
    }
    var showProfile by remember(mascotId) { mutableStateOf(false) }
    val calendarPreferences = remember { context.getSharedPreferences("mascot_calendar", Context.MODE_PRIVATE) }
    var calendarEnabled by remember { mutableStateOf(calendarPreferences.getBoolean("enabled", false) &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) }
    var showCalendarConsent by remember { mutableStateOf(false) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarEnabled = granted
        calendarPreferences.edit().putBoolean("enabled", granted).apply()
        if (!granted) Toast.makeText(context, "Доступ к календарю не предоставлен", Toast.LENGTH_SHORT).show()
    }
    if (showCalendarConsent) AlertDialog(onDismissRequest = { showCalendarConsent = false },
        title = { Text("Подключить Google Calendar?", fontFamily = SbSansDisplayMedium) },
        text = { Text("Приложение сможет читать Google-календари, синхронизированные на телефоне. При вопросах о календаре до 30 ближайших событий за 14 дней будут передаваться в OpenRouter для ответа. Другие календари не используются.") },
        confirmButton = { TextButton(onClick = { showCalendarConsent = false; calendarPermission.launch(Manifest.permission.READ_CALENDAR) }) { Text("Продолжить") } },
        dismissButton = { TextButton(onClick = { showCalendarConsent = false }) { Text("Отмена") } })
    val interactionModes = com.generativemascot.app.data.interactionPersonalities
    if (showInteractionSettings) {
        AlertDialog(onDismissRequest = { showInteractionSettings = false },
            title = { Text("Как мне отвечать?", fontFamily = SbSansDisplayMedium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    interactionModes.forEach { mode ->
                        val title = when (mode.id) {
                            "request_solutions" -> "Готовлю решения, когда попросите"
                            "proactive_solutions" -> "На шаг впереди"
                            "request_guidance" -> "Рядом, когда нужен"
                            else -> "Замечаю и подсказываю"
                        }
                        Row(Modifier.fillMaxWidth().clickable {
                            interactionId = mode.id
                            interactionPreferences.edit().putString(mascotId, mode.id).apply()
                            showInteractionSettings = false
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode.id == interactionId, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(title, fontFamily = SbSansDisplayMedium, fontSize = 16.sp, color = FigmaInk)
                                Text(mode.description, fontSize = 13.sp, lineHeight = 18.sp, color = FigmaInk.copy(alpha = .7f))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInteractionSettings = false }) { Text("Закрыть") } })
    }
    var draft by remember(mascotId) { mutableStateOf("") }
    var pendingImage by remember(mascotId) { mutableStateOf<String?>(null) }
    var loadingImage by remember(mascotId) { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            loadingImage = true
            scope.launch {
                try { pendingImage = withContext(Dispatchers.IO) { saveChatImage(context, uri) } }
                catch (_: Exception) { Toast.makeText(context, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show() }
                finally { loadingImage = false }
            }
        }
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
            draft = listOf(draft.trim(), spoken).filter { it.isNotBlank() }.joinToString(" ").take(1500)
        }
    }
    var busy by remember(mascotId) { mutableStateOf(false) }
    var status by remember(mascotId) { mutableStateOf("") }
    var video by remember(mascotId) { mutableStateOf<String?>(null) }
    var incomingVideo by remember(mascotId) { mutableStateOf<String?>(null) }
    val foreground = rememberAppResumed()
    LaunchedEffect(incomingVideo, active, foreground) {
        val pending = incomingVideo ?: return@LaunchedEffect
        if (!active || !foreground) return@LaunchedEffect
        delay(30_000)
        if (incomingVideo == pending) { incomingVideo = null; status = "Не удалось загрузить реакцию. Повторной генерации нет." }
    }
    val chatBackground = Color(0xFFEAEBEC)
    val helios = remember(mascotId) { HeliosPlayerController() }
    var heliosVisible by remember(mascotId) { mutableStateOf(false) }
    var heliosRunning by remember(mascotId) { mutableStateOf(false) }
    var heliosReady by remember(mascotId) { mutableStateOf(false) }
    // A return from an in-chat settings screen must not start a new paid session.
    var idleSessionStarted by remember(mascotId) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) idleSessionStarted = false }
    val activities = remember(mascotId) { HeroLocalStore.current?.chatActivityVideos(mascotId).orEmpty() }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    if (active && !showProfile && !showInteractionScreen) MascotSystemBars(chatBackground, chatBackground)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(chatBackground)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
        .safeDrawingPadding().imePadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val messagesTop = MascotChatIdentityTop + MascotChatIdentitySize
        MascotChatIdentity(heroName, Modifier.padding(start = 20.dp, top = MascotChatIdentityTop).size(MascotChatIdentitySize)
            .align(Alignment.TopStart).zIndex(1f), circleBackground = chatBackground,
            onClick = { showInteractionScreen = true }) {
            // The live source is a wide frame with generous margins around the mascot.
            // Crop the same way for the still and the live player to avoid a size jump.
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 2.1f; scaleY = 2.1f }, contentAlignment = Alignment.Center) {
            // Match the same framing used when the reference is sent to Helios.
            val heroFile = remember(mascotId) { HeroLocalStore.current?.baseFile(mascotId) }
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val frameHeight = minOf(maxHeight, maxWidth * (576f / 1024f))
                AsyncImage(model = heroFile ?: previewUrl, contentDescription = heroName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(frameHeight * (460f / 576f)))
            }
            if (active && foreground && !showProfile) {
                HeliosPlayer(helios, mascotId, onEvent = { type, detail ->
                    when (type) {
                        "ready" -> { heliosReady = true }
                        "frame" -> { heliosVisible = true; status = "" }
                        "replay" -> { heliosVisible = true }
                        "status" -> status = detail
                        "error" -> { status = detail; heliosVisible = false; heliosRunning = false }
                        "archive-error" -> { status = detail }
                        "stopped" -> { status = detail; heliosVisible = false; heliosRunning = false }
                    }
                }, modifier = Modifier.fillMaxSize())
            }
            }
        }
        LaunchedEffect(active, foreground, showProfile) {
            if (!active || !foreground || showProfile) { helios.shutdown(); heliosReady = false; heliosVisible = false; heliosRunning = false }
        }
        LaunchedEffect(showInteractionScreen) {
            // Settings are still inside the chat: stop billing but keep the recorded replay/player alive.
            if (showInteractionScreen && heliosRunning) helios.stop()
        }
        LaunchedEffect(active, foreground, heliosReady, showProfile, showInteractionScreen) {
            if (active && foreground && !showProfile && !showInteractionScreen && heliosReady && !heliosRunning && !idleSessionStarted) {
                try {
                    val file = HeroLocalStore.current?.baseFile(mascotId) ?: return@LaunchedEffect
                    val image = withContext(Dispatchers.IO) { "data:image/png;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }
                    val appearance = withContext(Dispatchers.IO) { com.generativemascot.app.data.CharacterAppearance.read(file).orEmpty() }
                    if (helios.react(image, "The character is calmly breathing, blinking, and gently looking around while waiting.", appearance = appearance)) {
                        heliosRunning = true
                        idleSessionStarted = true
                    }
                } catch (e: Exception) { status = e.message ?: "Не удалось подготовить персонажа." }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fadeHeight = 28.dp.toPx().coerceAtMost(size.height)
                    if (fadeHeight > 0f) drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = fadeHeight),
                        size = Size(size.width, fadeHeight), blendMode = BlendMode.DstIn,
                    )
                },
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = messagesTop + 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Привет, рад знакомству!", fontFamily = SbSansDisplayMedium, fontSize = 18.sp, color = FigmaInk)
                    Text("$heroName неторопливо осматривается вокруг.", fontFamily = SbSansTextRegular, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.35).sp, color = FigmaInk)
                }
            }
            items(messages) { item ->
                if (item.capabilities) {
                    ChatCapabilitiesCard(calendarEnabled, onCalendarChange = { enabled ->
                        if (enabled) showCalendarConsent = true else { calendarEnabled = false; calendarPreferences.edit().putBoolean("enabled", false).apply() }
                    })
                } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (item.user) Arrangement.End else Arrangement.Start) {
                    val messageModifier = if (item.user) Modifier.widthIn(max = 280.dp)
                        .background(Color(0xFFDEDDE1), RoundedCornerShape(20.dp)).padding(horizontal = 15.dp, vertical = 12.dp)
                    else Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    Column(messageModifier) {
                        item.imagePath?.let { path -> AsyncImage(model = java.io.File(path), contentDescription = "Прикреплённое изображение", contentScale = ContentScale.Crop, modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp))) }
                        if (item.text.isNotBlank()) Text(item.text, fontFamily = SbSansTextRegular, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.35).sp, color = FigmaInk)
                        if (!item.user) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(item.text)); Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.ContentCopy, "Скопировать ответ", modifier = Modifier.size(16.dp), tint = FigmaInk.copy(alpha = .45f))
                            }
                            IconButton(onClick = {
                                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.text)
                                context.startActivity(Intent.createChooser(intent, "Поделиться ответом"))
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.Share, "Поделиться ответом", modifier = Modifier.size(16.dp), tint = FigmaInk.copy(alpha = .45f))
                            }
                        }
                    }
                }
                }
            }
        }
        }
        if (heliosRunning) TextButton(onClick = { helios.stop(); heliosVisible = false; heliosRunning = false; status = "Останавливаем поток…" }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Остановить Helios", color = FigmaInk) }
        if (status.isNotBlank()) Text(status, fontFamily = SbSansDisplayMedium, fontSize = 12.sp, color = FigmaInk.copy(alpha = .72f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        pendingImage?.let { path ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = java.io.File(path), contentDescription = "Выбранное изображение", contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                TextButton(onClick = { pendingImage = null }) { Text("Убрать", color = FigmaInk) }
            }
        }
        MascotChatComposer(value = draft, onValueChange = { if (it.length <= 1500) draft = it }, busy = busy || incomingVideo != null || loadingImage, hasImage = pendingImage != null,
            onAttachment = { imagePicker.launch("image/*") },
            onVoice = {
                try {
                    speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                } catch (_: Exception) { Toast.makeText(context, "Голосовой ввод недоступен", Toast.LENGTH_SHORT).show() }
            }, onSend = send@{
                val text = draft.trim()
                val selectedImage = pendingImage
                if (busy || loadingImage || incomingVideo != null || (text.isEmpty() && selectedImage == null)) return@send
                draft = ""; pendingImage = null; messages.add(ChatMessage(true, text, selectedImage)); historyStore.write(mascotId, messages)
                if (selectedImage == null && isCalendarConnectionRequest(text)) {
                    messages.add(ChatMessage(false, "", capabilities = true)); historyStore.write(mascotId, messages); return@send
                }
                busy = true; status = "ИИ готовит ответ…"
                scope.launch {
                    var answered = false
                    try {
                        val calendar = if (calendarEnabled && isCalendarQuestion(text) &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
                            readUpcomingGoogleCalendar(context) else null
                        val result = service.send(text, interactionModes.firstOrNull { it.id == interactionId }?.tone, calendar, selectedImage)
                        messages.add(ChatMessage(false, result.getString("reply")))
                        historyStore.write(mascotId, messages)
                        answered = true
                        val file = HeroLocalStore.current?.baseFile(mascotId) ?: throw IllegalStateException("Не найдено изображение персонажа.")
                        val image = withContext(Dispatchers.IO) { "data:image/png;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }
                        if (!active || !foreground) throw IllegalStateException("Чат закрыт до запуска реакции.")
                        if (!helios.react(image, result.getString("reaction"), text, result.optString("appearance"))) throw IllegalStateException("Плеер Helios ещё не готов. Попробуй ещё раз.")
                        heliosRunning = true
                        status = "Подключаем Helios…"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (!answered) {
                            if (messages.lastOrNull()?.let { it.user && it.text == text } == true) messages.removeAt(messages.lastIndex)
                            historyStore.write(mascotId, messages)
                            if (draft.isBlank()) draft = text
                            if (pendingImage == null) pendingImage = selectedImage
                        }
                        status = e.message ?: "Нет связи с сервером. Автоповтора нет."
                    }
                    finally { busy = false }
                }
            })
    }
    AnimatedVisibility(visible = showInteractionScreen, modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
        ChatInteractionScreen(mascotId = mascotId, heroName = heroName, previewUrl = previewUrl,
            selectedIndex = interactionModes.indexOfFirst { it.id == interactionId }.coerceAtLeast(0),
            onSave = { index ->
                val id = interactionModes[index].id
                if (id != interactionId) {
                    interactionId = id
                    interactionPreferences.edit().putString(mascotId, id).apply()
                }
                showInteractionScreen = false
            }, onBack = { showInteractionScreen = false })
    }
    if (showProfile) {
        val mode = interactionModes.firstOrNull { it.id == interactionId } ?: interactionModes.first()
        ChatProfileScreen(mascotId = mascotId, heroName = heroName, previewUrl = previewUrl,
            modeTitle = mode.name, modeDescription = mode.description, onBack = { showProfile = false },
            onInteractionSettings = { showInteractionSettings = true })
    }
    }
}

@Composable
private fun ChatCapabilitiesCard(calendarEnabled: Boolean, onCalendarChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Давай расширим мои возможности", fontFamily = SbSansDisplayMedium, fontSize = 18.sp, color = FigmaInk)
            Text("Выбери, к чему дать доступ, и я смогу следить за здоровьем и планировать твой день", fontFamily = SbSansTextRegular, fontSize = 14.sp, lineHeight = 20.sp, color = FigmaInk)
        }
        Column(Modifier.fillMaxWidth().border(1.dp, Color(0xFFE0E1E5), RoundedCornerShape(30.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ChatCapability(R.drawable.chat_whoop, "Whoop", "Подключение пока недоступно", false, null)
            ChatCapability(R.drawable.chat_google_calendar, "Google Calendar", "Поможет планировать дела", calendarEnabled, onCalendarChange)
        }
    }
}

@Composable
private fun ChatCapability(icon: Int, title: String, description: String, enabled: Boolean, onChange: ((Boolean) -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(32.dp).clip(CircleShape))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontFamily = SbSansDisplayMedium, fontSize = 15.sp, color = FigmaInk)
            Text(description, fontFamily = SbSansTextMedium, fontSize = 12.sp, color = FigmaInk.copy(alpha = .5f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(width = 46.dp, height = 20.dp)
            .clip(RoundedCornerShape(50)).background(if (enabled) Color.Black else Color(0xFFB3B3B6))
            .then(if (onChange != null) Modifier.toggleable(value = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange) else Modifier)
            .padding(2.dp), contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(width = 28.dp, height = 16.dp).clip(RoundedCornerShape(50)).background(Color.White))
        }
    }
}

@Composable
private fun ChatIdleMascot(mascotId: String, heroName: String, previewUrl: String?, animationVideoUrl: String?,
    animationFrames: List<String>, animationFps: Int, animationVideoUrls: Map<String, String>, neutralFrameUrl: String?, active: Boolean) {
    if (animationVideoUrls.isNotEmpty() || animationVideoUrl != null) {
        val interaction = remember(mascotId) { PuppetInteraction() }
        PhysicsMascot(mascotId = mascotId, videoUrls = animationVideoUrls, neutralFrameUrl = neutralFrameUrl,
            action = "idle", interaction = interaction, onAnimationVisible = null, onOneShotFinished = null,
            modifier = Modifier.fillMaxSize().padding(10.dp)) {
            SoraMascotVideo(videoUrl = animationVideoUrl, videoUrls = animationVideoUrls,
                neutralFrameUrl = neutralFrameUrl, action = "idle", modifier = Modifier.fillMaxSize())
        }
    } else PngSequence(frames = animationFrames, fps = animationFps, decodeSize = 500, fallback = previewUrl,
        mascotIdHint = mascotId, puppetAction = "content", contentDescription = heroName,
        modifier = Modifier.fillMaxSize().padding(10.dp), playbackEnabled = active)
}

@Composable
private fun MascotChatComposer(value: String, onValueChange: (String) -> Unit, busy: Boolean, hasImage: Boolean,
    onAttachment: () -> Unit, onVoice: () -> Unit, onSend: () -> Unit) {
    val canSend = !busy && (value.isNotBlank() || hasImage)
    var focused by remember { mutableStateOf(false) }
    val expanded = focused || value.isNotBlank() || hasImage
    val shape = RoundedCornerShape(if (expanded) 20.dp else 26.dp)
    val addButton: @Composable () -> Unit = {
        IconButton(onClick = onAttachment, modifier = Modifier.size(36.dp)) {
            Icon(painterResource(R.drawable.ic_chat_plus_figma), "Прикрепить изображение", tint = FigmaInk, modifier = Modifier.size(24.dp))
        }
    }
    val voiceButton: @Composable (Boolean) -> Unit = { dark ->
        IconButton(onClick = onVoice, enabled = !busy, modifier = Modifier.size(36.dp).clip(CircleShape)
            .background(if (dark) FigmaInk else Color.Transparent)) {
            Icon(painterResource(R.drawable.ic_chat_mic_figma), "Голосовой ввод", tint = if (dark) Color.White else FigmaInk, modifier = Modifier.size(24.dp))
        }
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
        .clip(shape).background(Color.White.copy(alpha = .7f))
        .border(1.dp, Color.White.copy(alpha = .6f), shape)
        .padding(horizontal = 9.dp, vertical = if (expanded) 9.dp else 6.dp)) {
        Column(Modifier.fillMaxWidth()) {
            // The same text-field node remains mounted when the composer expands,
            // so requesting focus does not discard the field and hide the keyboard.
            BasicTextField(value = value, onValueChange = onValueChange, enabled = !busy, maxLines = 4,
                textStyle = TextStyle(color = FigmaInk, fontSize = 15.sp, fontFamily = SbSansTextMedium, letterSpacing = (-0.345).sp),
                cursorBrush = SolidColor(FigmaInk), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                modifier = Modifier.fillMaxWidth().padding(start = if (expanded) 8.dp else 48.dp, end = if (expanded) 8.dp else 48.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .heightIn(min = if (expanded) 44.dp else 40.dp)
                    .wrapContentHeight(if (expanded) Alignment.Top else Alignment.CenterVertically)
                    .padding(top = if (expanded) 5.dp else 0.dp),
                decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("Спросить персонажа", fontSize = 15.sp, fontFamily = SbSansTextMedium,
                        color = FigmaInk.copy(alpha = .38f), maxLines = 1)
                    inner()
                } })
            if (expanded) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                addButton()
                Spacer(Modifier.weight(1f))
                voiceButton(false)
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(if (canSend) Color(0xFF120F21) else FigmaInk.copy(alpha = .15f))) {
                    Icon(painterResource(R.drawable.ic_chat_send_figma), "Отправить сообщение", tint = Color.Unspecified, modifier = Modifier.size(24.dp))
                }
            }
        }
        if (!expanded) {
            Box(Modifier.align(Alignment.CenterStart)) { addButton() }
            Box(Modifier.align(Alignment.CenterEnd)) { voiceButton(true) }
        }
    }
    }
}
