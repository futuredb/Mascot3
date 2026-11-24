package com.generativemascot.app.ui

import android.Manifest
import com.generativemascot.app.data.CommunicationTrait
import com.generativemascot.app.data.chatEmotionPrompts
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.Context
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.R
import com.generativemascot.app.data.resolveMediaUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

@Composable
internal fun MascotChatScreen(mascotId: String, heroName: String, previewUrl: String?, animationVideoUrl: String?,
    animationFrames: List<String>, animationFps: Int,
    animationVideoUrls: Map<String, String>, neutralFrameUrl: String?, active: Boolean = true,
    portraitVisible: Boolean = active,
    openCapabilities: Boolean = false, onCapabilitiesOpened: () -> Unit = {},
    openBehavior: Boolean = false, onBehaviorOpened: () -> Unit = {},
    chatOverride: DirectMascotChat? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val historyStore = remember { ChatHistoryStore(context) }
    val profanityStore = remember { ChatProfanityStore(context) }
    val interactionPreferences = remember { context.getSharedPreferences("mascot_chat_interaction", Context.MODE_PRIVATE) }
    val moodPreferences = remember(context) { context.getSharedPreferences(CHAT_MOOD_PREFERENCES, Context.MODE_PRIVATE) }
    var portraitExpanded by remember(mascotId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var followLatest by remember(mascotId) { mutableStateOf(true) }
    val chatScroll = remember(listState, mascotId) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    followLatest = !listState.canScrollBackward
                }
                return Offset.Zero
            }
        }
    }
    val setPortraitExpanded: (Boolean) -> Unit = { portraitExpanded = it }
    BackHandler(enabled = portraitExpanded && active) { setPortraitExpanded(false) }
    val streamPreferences = remember { context.getSharedPreferences("mascot_stream", Context.MODE_PRIVATE) }
    var streamEnabled by remember { mutableStateOf(streamPreferences.getBoolean("enabled", true)) }
    val messages = remember(mascotId) { mutableStateListOf<ChatMessage>().apply {
        addAll(initialChatMessages(historyStore.read(mascotId), interactionPreferences.contains(mascotId), heroName))
    } }
    var profanityEnabled by remember(mascotId) {
        mutableStateOf(profanityStore.read(mascotId) ?: messages
            .filter { it.user && !it.capabilities && !it.interactionSetup }
            .fold(false) { mode, message -> requestedChatProfanity(message.text) ?: mode })
    }
    LaunchedEffect(mascotId, active) {
        if (active && messages.any { it.interactionSetup } && historyStore.read(mascotId).isEmpty()) historyStore.write(mascotId, messages)
    }
    LaunchedEffect(active, openCapabilities, mascotId) {
        if (active && openCapabilities) {
            if (messages.lastOrNull()?.capabilities != true) {
                messages.add(ChatMessage(false, "", capabilities = true))
                historyStore.write(mascotId, messages)
            }
            onCapabilitiesOpened()
        }
    }
    val openRouterApiKey = (context.applicationContext as? com.generativemascot.app.MascotApp)
        ?.openRouterSettings?.apiKey().orEmpty()
    LaunchedEffect(mascotId, portraitVisible) {
        if (portraitVisible) HeroLocalStore.current?.communication(mascotId)?.let {
            profanityEnabled = it[CommunicationTrait.PROFANITY] > 0
        }
    }
    val service = chatOverride ?: remember(mascotId, heroName, openRouterApiKey) {
        val store = HeroLocalStore.current
        val tone = store?.chatCharacterTone(mascotId)
        DirectMascotChat(
            heroName, tone, messages.toList(), store?.baseFile(mascotId),
            apiKey = openRouterApiKey,
            initialProfanityMode = profanityEnabled,
            onProfanityModeChanged = { enabled ->
                profanityEnabled = enabled
                profanityStore.write(mascotId, enabled)
                store?.communication(mascotId)?.let { profile ->
                    store.saveCommunication(mascotId, profile.withLevel(
                        CommunicationTrait.PROFANITY, if (enabled) 100 else 0,
                    ))
                }
            },
            moodPreferences = moodPreferences,
            moodKey = mascotId,
            communication = { store?.communication(mascotId) },
        )
    }
    val setProfanity: (Boolean) -> Unit = { enabled ->
        if (!service.setProfanityEnabled(enabled)) {
            Toast.makeText(context, "Эта настройка временно недоступна: герой обижен", Toast.LENGTH_SHORT).show()
        }
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var interactionId by remember(mascotId) { mutableStateOf(interactionPreferences.getString(mascotId, "request_guidance") ?: "request_guidance") }
    var onboardingChoice by remember(mascotId) { mutableIntStateOf(1) }
    val choosingInteraction = messages.any { it.interactionSetup }
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
    DisposableEffect(calendarPreferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "enabled") calendarEnabled = prefs.getBoolean("enabled", false) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        }
        calendarPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { calendarPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(active, mascotId, interactionId, calendarEnabled) {
        if (active) com.generativemascot.app.worker.configureCalendarReminders(context, calendarEnabled, interactionId)
    }
    var pendingCalendarEvent by remember(mascotId) { mutableStateOf<CalendarEventRequest?>(null) }
    var calendarChoices by remember(mascotId) { mutableStateOf<List<WritableGoogleCalendar>>(emptyList()) }
    val writeCalendarEvent: (CalendarEventRequest, Long) -> Unit = { event, selectedCalendarId ->
        scope.launch {
            val result = try { createGoogleCalendarEvent(context, event, selectedCalendarId) }
                catch (_: Exception) { "Не удалось создать событие. Проверь разрешения и подключение Google Calendar." }
            messages.add(ChatMessage(false, result))
            historyStore.write(mascotId, messages)
        }
    }
    val chooseCalendar: (CalendarEventRequest) -> Unit = { event ->
        scope.launch {
            val calendars = try { writableGoogleCalendars(context) } catch (_: Exception) { emptyList() }
            val saved = calendarPreferences.getLong("write_calendar_id", -1)
            val selected = calendars.singleOrNull { it.id == saved } ?: calendars.singleOrNull()
            when {
                selected != null -> writeCalendarEvent(event, selected.id)
                calendars.isNotEmpty() -> { pendingCalendarEvent = event; calendarChoices = calendars }
                else -> {
                    messages.add(ChatMessage(false, "Не нашёл доступный для записи Google-календарь. Проверь подключение и синхронизацию."))
                    historyStore.write(mascotId, messages)
                }
            }
        }
    }
    val calendarWritePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val event = pendingCalendarEvent
        pendingCalendarEvent = null
        if (granted && event != null && calendarEnabled && interactionId == "proactive_solutions") chooseCalendar(event)
        else if (event != null) {
            messages.add(ChatMessage(false, "Для создания события нужен доступ к записи в календарь. Событие не создано."))
            historyStore.write(mascotId, messages)
        }
    }
    if (calendarChoices.isNotEmpty()) AlertDialog(
        onDismissRequest = { calendarChoices = emptyList(); pendingCalendarEvent = null },
        title = { Text("Выбери календарь", fontFamily = SbSansDisplayMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                calendarChoices.forEach { calendar ->
                    Column(Modifier.fillMaxWidth().clickable {
                        val event = pendingCalendarEvent
                        pendingCalendarEvent = null; calendarChoices = emptyList()
                        if (event != null && calendarEnabled && interactionId == "proactive_solutions") {
                            calendarPreferences.edit().putLong("write_calendar_id", calendar.id).apply()
                            writeCalendarEvent(event, calendar.id)
                        }
                    }.padding(vertical = 10.dp)) {
                        Text(calendar.name, fontFamily = SbSansDisplayMedium)
                        if (calendar.account.isNotBlank()) Text(calendar.account, fontFamily = SbSansTextRegular, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { calendarChoices = emptyList(); pendingCalendarEvent = null }) { Text("Отмена") } },
    )
    var showCalendarConsent by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val enableReminders: (String) -> Unit = { mode ->
        if (calendarEnabled && mode in setOf("proactive_solutions", "proactive_guidance") && android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarEnabled = granted
        calendarPreferences.edit().putBoolean("enabled", granted).apply()
        if (granted) enableReminders(interactionId)
        if (!granted) Toast.makeText(context, "Доступ к календарю не предоставлен", Toast.LENGTH_SHORT).show()
    }
    if (showCalendarConsent) MascotConfirmationSheet(
        title = "Подключить Google Calendar?",
        message = "Персонаж сможет учитывать ближайшие события. В режиме «На шаг впереди» он сам добавит встречу с напоминанием за час, когда в сообщении указаны дата и время.",
        confirmLabel = "Подключить",
        onConfirm = {
            showCalendarConsent = false
            calendarPermission.launch(Manifest.permission.READ_CALENDAR)
        },
        onDismiss = { showCalendarConsent = false },
    )
    val interactionModes = com.generativemascot.app.data.interactionPersonalities
    if (showInteractionSettings) {
        val dismiss = { showInteractionSettings = false }
        MascotModalSheet("Как мне отвечать?", onDismiss = dismiss) {
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                interactionModes.forEachIndexed { index, mode ->
                    val selected = mode.id == interactionId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp))
                            .background(Color.White)
                            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton) {
                                interactionId = mode.id
                                interactionPreferences.edit().putString(mascotId, mode.id).apply()
                                enableReminders(mode.id)
                                dismiss()
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column {
                            Text(interactionDisplayTitles[index], fontFamily = SbSansDisplayMedium,
                                fontSize = 16.sp, color = FigmaInk)
                            Text(interactionDescriptions[index].replace('\n', ' '), fontFamily = SbSansTextRegular,
                                fontSize = 13.sp, lineHeight = 18.sp, color = FigmaInk.copy(alpha = .7f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            MascotSheetAction("Закрыть", onClick = dismiss, secondary = true)
        }
    }
    var draft by remember(mascotId) { mutableStateOf("") }
    var typingRevision by remember(mascotId) { mutableIntStateOf(0) }
    var userTyping by remember(mascotId) { mutableStateOf(false) }
    LaunchedEffect(typingRevision) {
        if (userTyping) {
            delay(1_600)
            userTyping = false
        }
    }
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
    var pendingReply by remember(mascotId) { mutableStateOf(false) }
    var streamingTarget by remember(mascotId) { mutableStateOf("") }
    var streamingVisible by remember(mascotId) { mutableStateOf("") }
    fun commitReply(text: String) {
        Snapshot.withMutableSnapshot {
            messages.add(ChatMessage(false, text))
            pendingReply = false
            streamingTarget = ""
            streamingVisible = ""
        }
        historyStore.write(mascotId, messages)
    }
    LaunchedEffect(busy, mascotId) {
        while (busy) {
            val target = streamingTarget
            if (target.startsWith(streamingVisible) && streamingVisible.length < target.length) {
                val remaining = target.codePointCount(streamingVisible.length, target.length)
                val count = (2 + remaining / 45).coerceAtMost(8).coerceAtMost(remaining)
                val end = target.offsetByCodePoints(streamingVisible.length, count)
                streamingVisible = target.substring(0, end)
            }
            delay(24)
        }
    }
    val foreground = rememberAppResumed()
    val chatBackground = Color(0xFFEAEBEC)
    val helios = remember(mascotId) { HeliosPlayerController() }
    val heroReference = remember(mascotId) { ChatHeroReferenceCache() }
    var heliosRequestActive by remember(mascotId) { mutableStateOf(false) }
    var heliosRequestSerial by remember(mascotId) { mutableIntStateOf(0) }
    var heliosReplayReady by remember(mascotId) { mutableStateOf(false) }
    var heliosVisible by remember(mascotId) { mutableStateOf(false) }
    var restartingPortrait by remember(mascotId) { mutableStateOf(false) }
    var initialPortraitLoading by remember(mascotId) { mutableStateOf(false) }
    var heliosRunning by remember(mascotId) { mutableStateOf(false) }
    // Helios mounts only for explicit custom animation requests, then shuts down.
    var heliosFailures by remember(mascotId) { mutableIntStateOf(0) }
    var heliosReady by remember(mascotId) { mutableStateOf(false) }
    var speakingStartedAt by remember(mascotId) { mutableStateOf<Long?>(null) }
    var speakingUntil by remember(mascotId) { mutableStateOf(0L) }
    LaunchedEffect(speakingUntil) {
        if (speakingUntil > 0L) {
            delay((speakingUntil - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            speakingUntil = 0L
        }
    }
    var waitingAnimation by remember(mascotId) { mutableStateOf<String?>(null) }
    var showWaitingAnimation by remember(mascotId) { mutableStateOf(false) }
    LaunchedEffect(busy, streamingTarget.isNotEmpty()) {
        showWaitingAnimation = false
        if (busy && streamingTarget.isEmpty()) {
            delay(320)
            showWaitingAnimation = true
        }
    }
    val dialogueVideos = remember(mascotId, animationVideoUrls) {
        // Modern full animation packs are higher resolution than the legacy
        // 480px dialogue clips. Prefer their expressive idle/watching/thinking
        // states instead of enlarging a soft chat-only asset in the portrait.
        if ("idle" in animationVideoUrls) emptyMap() else {
            HeroLocalStore.current?.chatActivityVideos(mascotId).orEmpty()
                .filterKeys { it == "speaking" || it == "listening" || (it == "thinking" && it !in animationVideoUrls) }
                .let { clips ->
                    val sway = HeroLocalStore.current?.chatActivityVideos(mascotId)?.get("idle-sway")
                    if (sway == null) clips else clips + ("chat_idle" to sway)
                }
        }
    }
    val chatVideoUrls = remember(animationVideoUrls, dialogueVideos) { animationVideoUrls + dialogueVideos }
    val availableChatActions = remember(chatVideoUrls) {
        chatVideoUrls.keys.map(HeroLocalStore::normalizeVideoAction).toSet()
    }
    val defaultChatAnimationAction = if ("chat_idle" in availableChatActions) "chat_idle"
        else defaultHomeVideoAction(animationVideoUrls.keys)
    val savedEmotions = availableChatActions.intersect(
        chatEmotionPrompts.keys + chatMoodReactionActions + setOf("curious", "greeting", "dancing"),
    )
    val hasThinkingClip = "thinking" in availableChatActions
    val usePreparedChatAnimations = "idle" in animationVideoUrls && (
        "typing_reaction" in availableChatActions ||
            availableChatActions.any(chatEmotionPrompts::containsKey) ||
            (HeroLocalStore.current?.characterChoice(mascotId) != null && (hasThinkingClip || savedEmotions.isNotEmpty()))
        )
    val availableEmotions = if (usePreparedChatAnimations) savedEmotions else availableChatActions.intersect(chatMoodReactionActions)
    var preparedEmotion by remember(mascotId) { mutableStateOf<String?>(null) }
    var preparedReactionRequestedAt by remember(mascotId) { mutableStateOf<Long?>(null) }
    var preparedReactionVisible by remember(mascotId) { mutableStateOf(false) }
    var typingReactionRequested by remember(mascotId) { mutableStateOf(false) }
    var typingGlitchTarget by remember(mascotId) { mutableStateOf(false) }
    val typingReactionAction = listOf("typing_reaction", "thinking", "listening", "watching")
        .firstOrNull { it in availableChatActions && it != defaultChatAnimationAction }
    var typingActionSerial by remember(mascotId) { mutableIntStateOf(0) }
    var typingGlitchPlayed by remember(mascotId) { mutableStateOf(false) }
    LaunchedEffect(busy, streamingTarget.isNotEmpty(), usePreparedChatAnimations, hasThinkingClip) {
        if (busy && streamingTarget.isEmpty() && usePreparedChatAnimations && hasThinkingClip) {
            delay(1_100)
            if (preparedReactionRequestedAt == null && preparedEmotion == null) {
                preparedReactionRequestedAt = android.os.SystemClock.elapsedRealtime()
            }
        }
    }
    val chatAnimationAction = when {
        typingReactionRequested -> typingReactionAction
        preparedEmotion != null -> preparedEmotion
        usePreparedChatAnimations && preparedReactionRequestedAt != null -> "thinking"
        usePreparedChatAnimations -> "idle"
        (busy && streamingTarget.isNotEmpty()) || speakingUntil > 0L ->
            listOf("speaking", "watching", "idle").firstOrNull { it in availableChatActions }
        busy && showWaitingAnimation -> listOf("thinking", waitingAnimation, "watching", "idle")
            .filterNotNull().firstOrNull { it in availableChatActions }
        else -> null
    } ?: defaultChatAnimationAction
    val keepHeliosWarm = streamEnabled && foreground && heliosRequestActive
    val glitchPalette = remember(mascotId) {
        val savedPalette = HeroLocalStore.current?.creativeContract(mascotId)?.glitchPalette
        when (mascotId) {
            "6dccb53c-6086-4e07-b387-a36d2335844d" -> com.generativemascot.app.data.CharacterGlitchPalette("#EC8B36", "#FFD25B") // Рико
            "716cdb43-703b-4d7e-8a79-27f03d92b99c" -> savedPalette ?: com.generativemascot.app.data.CharacterGlitchPalette("#F05C78", "#9655A8")
            "9f226b89-df35-4d3a-9b36-96c28ea1bb62" -> savedPalette ?: com.generativemascot.app.data.CharacterGlitchPalette("#E65362", "#A99CFF")
            "a83e9d30-1270-40e6-902c-b44774e84c17" -> savedPalette ?: com.generativemascot.app.data.CharacterGlitchPalette("#FFD34E", "#F28F3B")
            else -> savedPalette ?: com.generativemascot.app.data.CharacterGlitchPalette("#FF806B", "#AAA0FF")
        }
    }
    val expansion by animateFloatAsState(
        targetValue = if (portraitExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = MascotEase),
        label = "chat portrait expansion",
    )
    val showPortraitGlitch = portraitExpanded && expansion == 1f && portraitVisible && foreground
    val typingTransition = rememberChatGlitchTransition(
        "$mascotId:typing", typingGlitchTarget, showPortraitGlitch, glitchPalette,
        typingCue = true,
    )
    val portraitTransition = rememberChatGlitchTransition(mascotId, heliosVisible, showPortraitGlitch, glitchPalette)
    // Keep the outgoing frame until the glitch reaches its fully covered midpoint.
    val displayedHelios = if (showPortraitGlitch) portraitTransition.displayedHelios else heliosVisible
    val mountHelios = keepHeliosWarm || (foreground && portraitVisible && displayedHelios)
    val drawHelios = mountHelios && portraitVisible && !showProfile && !showInteractionScreen
    val referenceFile = HeroLocalStore.current?.baseFile(mascotId)
    val face by produceState(chatFaceFraming(mascotId), mascotId, referenceFile, neutralFrameUrl, chatVideoUrls) {
        value = chatFaceFraming(mascotId)
        value = withContext(Dispatchers.IO) {
            estimateChatFaceFraming(referenceFile, neutralFrameUrl, chatVideoUrls["idle"] ?: animationVideoUrl, value)
        }
    }
    var persistentMood by remember(mascotId, moodPreferences) {
        mutableStateOf(moodPreferences.readPersistentChatMood(mascotId, System.currentTimeMillis()))
    }
    var lastVisibleEmotion by remember(mascotId) { mutableStateOf<String?>(null) }
    DisposableEffect(mascotId, moodPreferences) {
        fun refreshMood() {
            persistentMood = moodPreferences.readPersistentChatMood(mascotId, System.currentTimeMillis())
        }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == mascotId || key == "$mascotId.expires" || key == "$mascotId.home.at") refreshMood()
        }
        moodPreferences.registerOnSharedPreferenceChangeListener(listener)
        refreshMood()
        onDispose { moodPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(persistentMood, foreground) {
        val mood = persistentMood ?: return@LaunchedEffect
        if (!foreground) return@LaunchedEffect
        delay((mood.expires - System.currentTimeMillis()).coerceAtLeast(1L))
        persistentMood = moodPreferences.readPersistentChatMood(mascotId, System.currentTimeMillis())
    }
    val canPlayMood = active && portraitVisible && foreground && !choosingInteraction &&
        !showProfile && !showInteractionScreen && !busy && !mountHelios && draft.isBlank() &&
        pendingImage == null && !userTyping && !typingReactionRequested && preparedReactionRequestedAt == null &&
        preparedEmotion == null
    LaunchedEffect(persistentMood, availableEmotions, canPlayMood, lastVisibleEmotion) {
        if (!canPlayMood) return@LaunchedEffect
        val mood = persistentMood ?: return@LaunchedEffect
        val actions = mood.compatibleActions(availableEmotions)
        if (actions.isEmpty()) return@LaunchedEffect
        delay(if (lastVisibleEmotion == null) 900L else 3_500L)
        if (System.currentTimeMillis() < mood.expires) {
            preparedEmotion = actions[(actions.indexOf(lastVisibleEmotion) + 1) % actions.size]
        }
    }
    LaunchedEffect(helios, drawHelios) {
        helios.setPortraitVisible(drawHelios)
    }
    val newestMessageKey = if (pendingReply) messages.size else messages.lastIndex
    LaunchedEffect(listState, mascotId, choosingInteraction) {
        if (choosingInteraction) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress to listState.canScrollBackward }.collect { (scrolling, canScrollBackward) ->
            if (!scrolling && !canScrollBackward) followLatest = true
        }
    }
    LaunchedEffect(newestMessageKey, mascotId, choosingInteraction) {
        if (newestMessageKey >= 0 && !choosingInteraction && followLatest && !listState.isScrollInProgress) {
            listState.requestScrollToItem(0)
        }
    }
    var localFrameVisible by remember(mascotId, animationVideoUrl, chatVideoUrls) { mutableStateOf(false) }
    val canShowLocalVideo = portraitVisible && foreground &&
        (chatVideoUrls.isNotEmpty() || !animationVideoUrl.isNullOrBlank())
    var localVideoStartupFailed by remember(mascotId, animationVideoUrl, chatVideoUrls, canShowLocalVideo) {
        mutableStateOf(false)
    }
    val showLocalVideo = canShowLocalVideo && !localVideoStartupFailed
    LaunchedEffect(showLocalVideo) { if (!showLocalVideo) localFrameVisible = false }
    val localPortraitOpenedAt = remember(mascotId, showLocalVideo) {
        android.os.SystemClock.elapsedRealtime()
    }
    val useNeutralEntry = usePreparedChatAnimations && neutralFrameUrl != null && showLocalVideo
    val entryGlitchVisible = usePreparedChatAnimations && showLocalVideo &&
        !localFrameVisible && !displayedHelios

    if (portraitVisible && !showProfile && !showInteractionScreen) MascotSystemBars(chatBackground, chatBackground)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(chatBackground)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
        .safeDrawingPadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val figureSize = MascotChatFigureSize.coerceAtMost(minOf(maxWidth, maxHeight * .58f))
        val portraitSize = lerp(figureSize, MascotChatIdentitySize, expansion)
        val portraitTop = MascotChatIdentityTop * expansion
        val messagesTop = portraitTop + portraitSize
        val portraitModifier = Modifier.align(Alignment.TopStart)
            .offset(x = lerp(MascotChatFigureStart, MascotChatIdentityStart, expansion), y = portraitTop)
            .size(portraitSize)
        MascotChatIdentity(heroName, portraitModifier.zIndex(1f),
            circleBackground = chatBackground,
            expansion = expansion,
            onLongClick = if (portraitExpanded) null else ({
                focusManager.clearFocus(force = true)
                keyboard?.hide()
                setPortraitExpanded(true)
            }),
            onClickLabel = if (portraitExpanded) "Свернуть персонажа" else "На главный экран",
            overlay = {
                if (!showPortraitGlitch) return@MascotChatIdentity
                val glitchModifier = Modifier.align(Alignment.Center)
                    .fillMaxWidth(1f - .52f * expansion)
                    .fillMaxHeight(1f - .45f * expansion)
                val softenGlitch = expansion > 0f
                val entryGlitchAlpha by animateFloatAsState(
                    targetValue = if (entryGlitchVisible) 1f else 0f,
                    animationSpec = tween(if (entryGlitchVisible) 0 else 160),
                    label = "portrait entry glitch",
                )
                if (entryGlitchVisible || entryGlitchAlpha > 0f) {
                    ChatGlitchOverlay(
                        portraitTransition, glitchModifier.graphicsLayer {
                            alpha = if (entryGlitchVisible) 1f else entryGlitchAlpha
                        },
                        loading = true, softenEdges = softenGlitch,
                    )
                }
                ChatGlitchOverlay(
                    typingTransition,
                    Modifier.align(Alignment.Center)
                        .fillMaxWidth(.42f + .36f * expansion)
                        .fillMaxHeight(.46f + .16f * expansion),
                    softenEdges = true,
                    typingCue = true,
                )
                ChatGlitchOverlay(
                    portraitTransition,
                    glitchModifier,
                    loading = streamEnabled && portraitVisible && foreground && (initialPortraitLoading || restartingPortrait),
                    softenEdges = softenGlitch,
                )
            },
            onClick = {
                if (portraitExpanded) {
                    setPortraitExpanded(false)
                } else {
                    focusManager.clearFocus(force = true)
                    keyboard?.hide()
                    onBack()
                }
            }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = if (showPortraitGlitch) portraitTransition.alpha * typingTransition.alpha else 1f
            },
                contentAlignment = Alignment.Center) {
            val stillAlpha by animateFloatAsState(
                targetValue = if (displayedHelios || (showLocalVideo && localFrameVisible)) 0f else 1f,
                animationSpec = tween(if (usePreparedChatAnimations) 0 else 220), label = "chat still transition",
            )
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = if (displayedHelios) 0f else 1f
            }, contentAlignment = Alignment.Center) {
                // Keep the portrait under the entry glitch until the decoder can draw.
                // Draw video above the still so the handoff preserves its opacity.
                if (!useNeutralEntry && stillAlpha > 0f) {
                    AsyncImage(model = referenceFile ?: previewUrl, contentDescription = heroName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val scale = 1f + (face.stillScale - 1f) * expansion
                            scaleX = scale
                            scaleY = scale
                            translationX = (.5f - face.stillX) * scale * size.width * expansion
                            translationY = ((.5f - face.stillCenter) * scale + .09f) * size.height * expansion
                            alpha = stillAlpha
                        })
                }
                if (showLocalVideo) {
                    key(mascotId, animationVideoUrl, chatVideoUrls) {
                        SoraMascotVideo(
                            videoUrl = animationVideoUrl, videoUrls = chatVideoUrls,
                            neutralFrameUrl = neutralFrameUrl,
                            action = chatAnimationAction,
                            interaction = PuppetInteraction(
                                actionName = typingReactionAction.takeIf { typingReactionRequested },
                                actionSerial = typingActionSerial,
                            ),
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                val scale = 1f + (face.videoScale - 1f) * expansion
                                scaleX = scale
                                scaleY = scale
                                translationX = (.5f - face.videoX) * scale * size.width * expansion
                                translationY = ((.5f - face.videoCenter) * scale + .09f) * size.height * expansion
                            },
                            onFirstFrame = {
                                if (!localFrameVisible && usePreparedChatAnimations) {
                                    val elapsed = android.os.SystemClock.elapsedRealtime() - localPortraitOpenedAt
                                    android.util.Log.i("MascotPlayback", "Prepared chat portrait visible after ${elapsed}ms")
                                }
                                localFrameVisible = true
                            },
                            onPlaybackError = if (usePreparedChatAnimations) ({ localVideoStartupFailed = true }) else null,
                            playOnceActions = availableEmotions + setOfNotNull(typingReactionAction, "thinking".takeIf { usePreparedChatAnimations }),
                            onOneShotFinished = { action ->
                                if (action == preparedEmotion) preparedEmotion = null
                                if (action == typingReactionAction && preparedReactionRequestedAt == null) typingReactionRequested = false
                                if (usePreparedChatAnimations && action == "thinking") {
                                    preparedReactionRequestedAt = null
                                    preparedReactionVisible = false
                                }
                            },
                            onAnimationVisible = { action ->
                                if (action in availableEmotions) lastVisibleEmotion = action
                                if (usePreparedChatAnimations && action == "thinking" && !preparedReactionVisible) {
                                    preparedReactionRequestedAt?.let { requestedAt ->
                                        val elapsed = android.os.SystemClock.elapsedRealtime() - requestedAt
                                        android.util.Log.i("MascotPlayback", "Prepared chat reaction visible after ${elapsed}ms")
                                        preparedReactionVisible = true
                                    }
                                }
                            },
                            useNeutralTransitions = false,
                            showNeutralOnStartup = useNeutralEntry,
                            preloadIdleReaction = typingReactionAction,
                            interruptOneShotForAction = typingReactionAction.takeIf { typingReactionRequested },
                            onTypingCutReady = {
                                if (showPortraitGlitch && typingReactionRequested && !typingGlitchPlayed) {
                                    typingGlitchPlayed = true
                                    typingGlitchTarget = !typingGlitchTarget
                                    withTimeoutOrNull(280) {
                                        snapshotFlow { typingTransition.progress.value }.first { it in .44f.. .68f }
                                    }
                                }
                            },
                            switchActionsPromptly = true,
                            independentMatteActions = dialogueVideos.keys,
                        )
                    }
                }
            }
            if (mountHelios) {
                HeliosPlayer(helios, mascotId, glitchPalette = glitchPalette, glitchEnabled = showPortraitGlitch, onEvent = { type, detail ->
                    when (type) {
                        "ready" -> heliosReady = true
                        "idle_complete" -> heliosRunning = false
                        "frame" -> if (!restartingPortrait) { heliosVisible = true; initialPortraitLoading = false; heliosFailures = 0; status = "" }
                        "restart-frame" -> { heliosVisible = true; initialPortraitLoading = false; restartingPortrait = false; heliosFailures = 0; if (!busy) status = "" }
                        "replay" -> { heliosReplayReady = true; heliosVisible = true; restartingPortrait = false; initialPortraitLoading = false }
                        // Provider progress is not useful in the chat. Keep it
                        // in diagnostics rather than showing transport details.
                        "status" -> Unit
                        "error" -> {
                            helios.invalidate(); heliosRequestActive = false; status = "Не удалось оживить персонажа. Проверь интернет и попробуй ещё раз."; heliosVisible = false; restartingPortrait = false; initialPortraitLoading = false
                            if (heliosRunning) heliosFailures++
                            heliosRunning = false
                        }
                        "archive-error" -> { status = "Не удалось сохранить анимацию." }
                        "stopped" -> {
                            helios.invalidate(); heliosRequestActive = false; status = if (detail.isBlank()) "" else "Не удалось оживить персонажа. Попробуй ещё раз."; restartingPortrait = false; initialPortraitLoading = false
                            if (heliosRunning) heliosFailures++
                            heliosRunning = false
                            heliosVisible = false
                        }
                    }
                }, modifier = Modifier.fillMaxSize().graphicsLayer {
                    val scale = 1f + 2.8f * expansion
                    scaleX = scale
                    scaleY = scale
                    translationY = (.12f * scale + .09f) * size.height * expansion
                    alpha = if (displayedHelios) 1f else 0f
                })
            }
            }
        }
        LaunchedEffect(mountHelios) {
            if (!mountHelios) {
                heliosRequestActive = false
                helios.shutdown(); heliosReady = false; heliosVisible = false; heliosRunning = false; restartingPortrait = false; initialPortraitLoading = false
                heliosFailures = 0
            } else if (!heliosVisible) initialPortraitLoading = true
        }
        LaunchedEffect(heliosRequestSerial, heliosReplayReady) {
            if (heliosRequestSerial == 0 || heliosReplayReady) return@LaunchedEffect
            val request = heliosRequestSerial
            delay(45_000)
            if (request == heliosRequestSerial && heliosRequestActive) {
                helios.shutdown()
                heliosRequestActive = false
                heliosReady = false
                heliosVisible = false
                heliosRunning = false
                restartingPortrait = false
                initialPortraitLoading = false
            }
        }
        LaunchedEffect(restartingPortrait) {
            if (restartingPortrait) {
                delay(45_000)
                helios.shutdown(); restartingPortrait = false; heliosVisible = false; heliosRunning = false
                heliosFailures++; status = "Не удалось запустить новую анимацию."
            }
        }
        LaunchedEffect(initialPortraitLoading) {
            if (initialPortraitLoading) {
                delay(45_000)
                initialPortraitLoading = false
            }
        }
        if (choosingInteraction) {
            ChatInteractionOnboarding(heroName = heroName, choice = onboardingChoice,
                onChoice = { onboardingChoice = it }, onSave = {
                    val index = onboardingChoice.coerceIn(interactionModes.indices)
                    interactionId = interactionModes[index].id
                    interactionPreferences.edit().putString(mascotId, interactionId).apply()
                    enableReminders(interactionId)
                    val position = messages.indexOfFirst { it.interactionSetup }
                    if (position >= 0) messages[position] = ChatMessage(false,
                        "Ты выбрал настройку «${interactionDisplayTitles[index]}». Её всегда можно изменить в разделе «Поведение и доступы» в настройках на главном экране.")
                    historyStore.write(mascotId, messages)
                }, modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp,
                    top = (messagesTop + 20.dp).coerceAtMost(maxHeight * .37f), bottom = 20.dp))
        } else LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize()
                .nestedScroll(chatScroll)
                .drawWithContent {
                    drawContent()
                    val fadeHeight = 28.dp.toPx().coerceAtMost(size.height)
                    if (fadeHeight > 0f) drawRect(
                        brush = Brush.verticalGradient(listOf(chatBackground, Color.Transparent), endY = fadeHeight),
                        size = Size(size.width, fadeHeight),
                    )
                },
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = messagesTop + 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top),
        ) {
            if (messages.isEmpty() && !pendingReply) item(key = "welcome") {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Привет, рад знакомству!", fontFamily = SbSansDisplayMedium, fontSize = 18.sp, color = FigmaInk)
                    Text("$heroName неторопливо осматривается вокруг.", fontFamily = SbSansTextRegular, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.35).sp, color = FigmaInk)
                }
            }
            // The pending reply and the saved reply use the same row and key.
            items(count = newestMessageKey + 1, key = { index -> newestMessageKey - index }) { index ->
                val item = messages.getOrNull(newestMessageKey - index)
                if (item?.capabilities == true) {
                    ChatCapabilitiesCard(calendarEnabled, onCalendarChange = { enabled ->
                        if (enabled) showCalendarConsent = true else { calendarEnabled = false; calendarPreferences.edit().putBoolean("enabled", false).apply() }
                    }, profanityEnabled = profanityEnabled, onProfanityChange = setProfanity)
                } else {
                    val userMessage = item?.user == true
                    val waiting = item == null && streamingVisible.isEmpty()
                    val text = item?.text ?: streamingVisible.ifEmpty { "Думает…" }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (userMessage) Arrangement.End else Arrangement.Start) {
                        val messageModifier = if (userMessage) Modifier.widthIn(max = 280.dp)
                            .background(Color(0xFFDEDDE1), RoundedCornerShape(20.dp)).padding(horizontal = 15.dp, vertical = 12.dp)
                        else Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        Column(messageModifier) {
                            item?.imagePath?.let { path -> AsyncImage(model = java.io.File(path), contentDescription = "Прикреплённое изображение", contentScale = ContentScale.Crop, modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp))) }
                            if (text.isNotBlank()) Text(text, fontFamily = SbSansTextRegular, fontSize = 14.sp,
                                lineHeight = 20.sp, letterSpacing = (-0.35).sp,
                                color = FigmaInk.copy(alpha = if (waiting) .62f else 1f))
                            if (item == null) Spacer(Modifier.height(36.dp))
                            else if (!userMessage) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(onClick = { clipboard.setText(AnnotatedString(text)); Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.ContentCopy, "Скопировать ответ", modifier = Modifier.size(16.dp), tint = FigmaInk.copy(alpha = .45f))
                                }
                                IconButton(onClick = {
                                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
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
        val visibleStatus = if (keepHeliosWarm && !heliosReplayReady && status.isBlank()) {
            if (heliosVisible) "Записывает анимацию…" else "Готовит анимацию…"
        } else status.takeUnless { it == "Думает" }.orEmpty()
        if (!choosingInteraction && visibleStatus.isNotBlank()) {
            Text(visibleStatus, fontFamily = SbSansDisplayMedium, fontSize = 12.sp,
                color = FigmaInk.copy(alpha = .72f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        pendingImage?.let { path ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = java.io.File(path), contentDescription = "Выбранное изображение", contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                TextButton(onClick = { pendingImage = null }) { Text("Убрать", color = FigmaInk) }
            }
        }
        if (!choosingInteraction) MascotChatComposer(value = draft, onValueChange = { next ->
            if (next.length <= 1500 && next != draft) {
                if (next.isNotBlank() && !userTyping &&
                    preparedReactionRequestedAt == null) {
                    typingGlitchPlayed = false
                    if (typingReactionAction == null || !showLocalVideo) {
                        if (showPortraitGlitch) typingGlitchTarget = !typingGlitchTarget
                    } else {
                        preparedEmotion = null
                        typingReactionRequested = true
                        typingActionSerial += 1
                    }
                }
                draft = next
                userTyping = next.isNotBlank()
                typingRevision++
            }
        }, busy = busy || restartingPortrait || loadingImage, hasImage = pendingImage != null,
            onAttachment = { imagePicker.launch("image/*") },
            onVoice = {
                try {
                    speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                } catch (_: Exception) { Toast.makeText(context, "Голосовой ввод недоступен", Toast.LENGTH_SHORT).show() }
            }, onSend = send@{
                val text = draft.trim()
                val selectedImage = pendingImage
                if (choosingInteraction || busy || restartingPortrait || loadingImage || (text.isEmpty() && selectedImage == null)) return@send
                speakingStartedAt = null
                speakingUntil = 0L
                waitingAnimation = chatAnimationForMessage(text, selectedImage != null, chatVideoUrls.keys)
                heliosReplayReady = false
                heliosVisible = false
                heliosRequestActive = false
                heliosRequestSerial++
                userTyping = false
                typingReactionRequested = false
                followLatest = true
                listState.requestScrollToItem(0)
                draft = ""; pendingImage = null; messages.add(ChatMessage(true, text, selectedImage)); historyStore.write(mascotId, messages)
                if (selectedImage == null && isCalendarConnectionRequest(text)) {
                    messages.add(ChatMessage(false, "", capabilities = true)); historyStore.write(mascotId, messages); return@send
                }
                streamingTarget = ""; streamingVisible = ""
                pendingReply = true
                if (usePreparedChatAnimations && hasThinkingClip && availableEmotions.isEmpty() && preparedReactionRequestedAt == null) {
                    preparedReactionRequestedAt = android.os.SystemClock.elapsedRealtime()
                }
                busy = true; restartingPortrait = false; status = "Думает"
                android.util.Log.i("MascotHeliosTiming", "request-sent")
                scope.launch {
                    var answered = false
                    try {
                        supervisorScope {
                            val answer = async {
                                val calendar = if (calendarEnabled && isCalendarQuestion(text) &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
                                    readUpcomingGoogleCalendar(context) else null
                                service.send(text, interactionModes.firstOrNull { it.id == interactionId }?.tone, calendar, selectedImage, interactionId, availableEmotions) { partial ->
                                    withContext(Dispatchers.Main.immediate) {
                                        if (partial.isNotEmpty() && speakingStartedAt == null) {
                                            speakingStartedAt = android.os.SystemClock.elapsedRealtime()
                                        }
                                        if (partial.startsWith(streamingTarget)) streamingTarget = partial
                                        if (partial.isNotEmpty() && status == "Думает") status = ""
                                    }
                                }
                            }
                            val result = answer.await()
                            val customAnimation = result.optBoolean("custom_animation")
                            val reaction = result.optString("local_reaction").takeIf { it in availableEmotions }
                                ?: savedMessageReaction(text, selectedImage != null, availableEmotions).takeUnless { customAnimation }
                            if (reaction != null) preparedEmotion = reaction
                            if (!preparedReactionVisible) preparedReactionRequestedAt = null
                            val reply = result.getString("reply")
                            if (!usePreparedChatAnimations && streamEnabled && customAnimation) launch {
                                try {
                                    val file = HeroLocalStore.current?.baseFile(mascotId) ?: return@launch
                                    val reference = heroReference.read(file)
                                    val contextText = text.ifBlank { "Custom mascot reaction" }
                                    heliosRequestActive = true
                                    restartingPortrait = true
                                    heliosRequestSerial++
                                    var waitedMs = 0
                                    while (heliosRequestActive && !helios.ready && waitedMs < 6_000) {
                                        delay(40)
                                        waitedMs += 40
                                    }
                                    if (heliosRequestActive && helios.ready) {
                                        heliosRunning = helios.restart(
                                            reference.image, result.getString("reaction"), contextText,
                                            result.optString("appearance").ifBlank { reference.appearance }, reply,
                                        )
                                    } else {
                                        heliosRequestActive = false
                                        restartingPortrait = false
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    heliosRequestActive = false
                                    restartingPortrait = false
                                    status = "Не удалось оживить персонажа. Попробуй ещё раз."
                                }
                            }
                            val characters = reply.codePointCount(0, reply.length)
                            val speechDuration = (characters * 1_000L / 18).coerceIn(1_200L, 30_000L)
                            speakingUntil = (speakingStartedAt ?: android.os.SystemClock.elapsedRealtime()) + speechDuration
                            android.util.Log.i("MascotPlayback", "speech characters=$characters plannedMs=$speechDuration")
                            streamingTarget = reply
                            // Final processing can replace words already shown. Swap that
                            // reply in place instead of replaying the typewriter from zero.
                            if (reply.startsWith(streamingVisible)) {
                                while (streamingVisible != reply) delay(24)
                            }
                            commitReply(reply)
                            answered = true
                            if (status == "Думает") status = ""
                            result.optJSONObject("calendar_event")?.let { raw ->
                                val event = parseCalendarEvent(raw)
                                when {
                                    interactionId != "proactive_solutions" -> {
                                        messages.add(ChatMessage(false, "Режим общения изменился, поэтому событие не создано."))
                                        historyStore.write(mascotId, messages)
                                    }
                                    event == null -> {
                                        messages.add(ChatMessage(false, "Не удалось определить точное время события. Уточни дату и время."))
                                        historyStore.write(mascotId, messages)
                                    }
                                    !calendarEnabled -> {
                                        messages.add(ChatMessage(false, "Чтобы создать событие, сначала подключи Google Calendar, затем повтори просьбу."))
                                        messages.add(ChatMessage(false, "", capabilities = true))
                                        historyStore.write(mascotId, messages)
                                    }
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED -> chooseCalendar(event)
                                    else -> {
                                        pendingCalendarEvent = event
                                        calendarWritePermission.launch(Manifest.permission.WRITE_CALENDAR)
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        restartingPortrait = false
                        if (!answered && streamingVisible.isNotBlank()) {
                            commitReply(streamingVisible + "\n\nОтвет прерван.")
                            answered = true
                        }
                        if (!answered) {
                            if (messages.lastOrNull()?.let { it.user && it.text == text } == true) messages.removeAt(messages.lastIndex)
                            historyStore.write(mascotId, messages)
                            if (draft.isBlank()) draft = text
                            if (pendingImage == null) pendingImage = selectedImage
                        }
                        status = friendlyChatError(e.message)
                    }
                    finally {
                        busy = false
                        pendingReply = false
                        if (!preparedReactionVisible) preparedReactionRequestedAt = null
                        streamingVisible = ""; streamingTarget = ""
                        if (status == "Думает") status = ""
                    }
                }
            })
    }
    AnimatedVisibility(visible = showInteractionScreen, modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
        ChatInteractionScreen(selectedIndex = interactionModes.indexOfFirst { it.id == interactionId }.coerceAtLeast(0),
            calendarEnabled = calendarEnabled,
            profanityEnabled = profanityEnabled,
            onProfanityChange = setProfanity,
            overlayStreamEnabled = context.getSharedPreferences("mascot_overlay", Context.MODE_PRIVATE).getBoolean("stream_enabled", false),
            streamEnabled = streamEnabled,
            onCalendarChange = { enabled ->
                if (enabled) showCalendarConsent = true
                else { calendarEnabled = false; calendarPreferences.edit().putBoolean("enabled", false).apply() }
            },
            onSave = { index, overlayStream, stream ->
                streamPreferences.edit().putBoolean("enabled", stream).apply()
                streamEnabled = stream
                if (!stream) { heliosRequestActive = false; helios.shutdown(); heliosVisible = false; heliosRunning = false; heliosReady = false; restartingPortrait = false; initialPortraitLoading = false }
                context.getSharedPreferences("mascot_overlay", Context.MODE_PRIVATE).edit().putBoolean("stream_enabled", overlayStream).apply()
                val id = interactionModes[index].id
                if (index == 0 || index == 2) {
                    calendarEnabled = false
                    calendarPreferences.edit().putBoolean("enabled", false).apply()
                }
                if (id != interactionId) {
                    interactionId = id
                    interactionPreferences.edit().putString(mascotId, id).apply()
                    enableReminders(id)
                }
                showInteractionScreen = false
                onBack()
            }, onBack = {
                showInteractionScreen = false
                onBack()
            })
    }
    if (showProfile) {
        val mode = interactionModes.firstOrNull { it.id == interactionId } ?: interactionModes.first()
        ChatProfileScreen(mascotId = mascotId, heroName = heroName, previewUrl = previewUrl,
            modeTitle = mode.name, modeDescription = mode.description, onBack = { showProfile = false },
            onInteractionSettings = { showInteractionSettings = true })
    }
    }
}

private fun friendlyChatError(detail: String?): String = when {
    detail?.contains("недостаточно средств", ignoreCase = true) == true ->
        "Не удалось ответить. Проверь баланс и попробуй ещё раз."
    detail?.contains("ключ", ignoreCase = true) == true ->
        "Не удалось ответить. Проверь настройки и попробуй ещё раз."
    detail?.contains("слишком большое", ignoreCase = true) == true ->
        "Изображение слишком большое или недоступно. Выбери другое."
    detail?.contains("временно занят", ignoreCase = true) == true ->
        "Сейчас не получается ответить. Попробуй чуть позже."
    detail?.contains("прервал", ignoreCase = true) == true ->
        "Ответ прервался. Попробуй ещё раз."
    else -> "Не удалось получить ответ. Проверь интернет и попробуй ещё раз."
}

internal fun shouldAutoStartHelios(keepWarm: Boolean, ready: Boolean, running: Boolean): Boolean =
    keepWarm && ready && !running

/** Cap retry traffic and costs when the service or network is unavailable. */
internal fun heliosRetryDelayMillis(failures: Int): Long = when (failures.coerceAtLeast(0)) {
    0 -> 0L
    1 -> 5_000L
    2 -> 10_000L
    3 -> 20_000L
    4 -> 40_000L
    else -> 60_000L
}

@Composable
private fun ChatInteractionOnboarding(heroName: String, choice: Int, onChoice: (Int) -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val index = choice.coerceIn(0, 3)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Привет! Рад познакомиться. Я $heroName.", fontFamily = SbSansTextRegular,
            fontSize = 14.sp, lineHeight = 20.sp, color = FigmaInk)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Давай выберем, как будем общаться?", fontFamily = SbSansDisplayMedium,
                fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = (-0.45).sp, color = FigmaInk)
            Text("Я могу сам сообщать о важном или ждать, когда тебе понадобится помощь.",
                fontFamily = SbSansTextRegular, fontSize = 14.sp, lineHeight = 20.sp, color = FigmaInk)
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val fieldWidth = maxWidth.coerceAtMost(353.dp)
            val fieldHeight = (fieldWidth * (315f / 353f)).coerceAtMost(maxHeight)
            Box(Modifier.width(fieldWidth).height(fieldHeight)) {
                InteractionPersonalityField(index, onChoice, Modifier.fillMaxSize())
                Text("Готовлю решения", modifier = Modifier.align(Alignment.TopCenter).padding(top = 15.dp),
                    fontFamily = SbSansTextMedium, fontSize = 11.sp, color = Color.White.copy(alpha = .55f))
                Text("Проактивно", modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                    .graphicsLayer { rotationZ = -90f }, fontFamily = SbSansTextMedium, fontSize = 11.sp,
                    maxLines = 1, color = Color.White.copy(alpha = .55f))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(interactionDisplayTitles[index], fontFamily = SbSansDisplayMedium, fontSize = 18.sp, color = FigmaInk)
            Text(interactionDescriptions[index].replace('\n', ' '), fontFamily = SbSansTextRegular,
                fontSize = 14.sp, lineHeight = 20.sp, color = FigmaInk)
        }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = FigmaInk)) {
            Text("Сохранить", fontFamily = SbSansDisplayMedium, fontSize = 15.sp, color = Color.White)
        }
    }
}

@Composable
internal fun ChatCapabilitiesCard(calendarEnabled: Boolean, onCalendarChange: (Boolean) -> Unit,
    profanityEnabled: Boolean, onProfanityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, controlsEnabled: Boolean = true) {
    val controlsAlpha by animateFloatAsState(if (controlsEnabled) 1f else .36f,
        animationSpec = tween(160), label = "integration controls")
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Давай расширим мои возможности", fontFamily = SbSansDisplayMedium, fontSize = 18.sp, color = FigmaInk)
            Text("Подключи календарь, чтобы я мог помогать планировать твой день", fontFamily = SbSansTextRegular, fontSize = 14.sp, lineHeight = 20.sp, color = FigmaInk)
            if (!controlsEnabled) Text("Доступно в режимах, где герой действует проактивно.",
                fontFamily = SbSansTextMedium, fontSize = 12.sp, lineHeight = 16.sp,
                color = FigmaInk.copy(alpha = .5f))
        }
        Column(Modifier.fillMaxWidth().graphicsLayer { alpha = controlsAlpha }
            .border(1.dp, Color(0xFFE0E1E5), RoundedCornerShape(30.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ChatCapability(R.drawable.chat_google_calendar, "Google Calendar", "Поможет планировать дела",
                calendarEnabled && controlsEnabled, if (controlsEnabled) onCalendarChange else null)
        }
        Row(Modifier.fillMaxWidth().border(1.dp, Color(0xFFE0E1E5), RoundedCornerShape(24.dp))
            .toggleable(value = profanityEnabled, role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = onProfanityChange)
            .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("+18 ответы", fontFamily = SbSansDisplayMedium, fontSize = 15.sp, color = FigmaInk)
                Text("Маскот будет использовать мат в ответах", fontFamily = SbSansTextMedium,
                    fontSize = 12.sp, lineHeight = 16.sp, color = FigmaInk.copy(alpha = .55f))
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(width = 46.dp, height = 20.dp).clip(RoundedCornerShape(50))
                .background(if (profanityEnabled) Color.Black else Color(0xFFB3B3B6)).padding(2.dp),
                contentAlignment = if (profanityEnabled) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(width = 28.dp, height = 16.dp).clip(RoundedCornerShape(50)).background(Color.White))
            }
        }
    }
}

@Composable
internal fun ChatCapability(icon: Int, title: String, description: String, enabled: Boolean, onChange: ((Boolean) -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(32.dp).clip(CircleShape))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontFamily = SbSansDisplayMedium, fontSize = 15.sp, color = FigmaInk)
            Text(description, fontFamily = SbSansTextMedium, fontSize = 12.sp, color = FigmaInk.copy(alpha = .5f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        MascotSettingsSwitch(title, enabled, onChange)
    }
}

@Composable
private fun MascotChatComposer(value: String, onValueChange: (String) -> Unit, busy: Boolean, hasImage: Boolean,
    onAttachment: () -> Unit, onVoice: () -> Unit, onSend: () -> Unit) {
    val canSend = !busy && (value.isNotBlank() || hasImage)
    var focused by remember { mutableStateOf(false) }
    val expanded = focused || value.isNotBlank() || hasImage
    val shape = RoundedCornerShape(if (expanded) 20.dp else 26.dp)
    val addButton: @Composable () -> Unit = {
        IconButton(onClick = onAttachment, enabled = !busy, modifier = Modifier.size(36.dp)) {
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
            BasicTextField(value = value, onValueChange = onValueChange, maxLines = 4,
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
