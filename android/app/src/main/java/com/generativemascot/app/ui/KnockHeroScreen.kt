package com.generativemascot.app.ui

import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.SentimentSatisfiedAlt
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.painterResource
import com.generativemascot.app.R
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.resolveMediaUrl
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import com.generativemascot.app.data.characterPerformanceStyle
import kotlin.random.Random
import kotlinx.coroutines.delay

private val KnockBg = Color(0xFFE8F4FC)
private val KnockInk = Color(0xFF5B160F)
private const val RippleMs = 760f

private const val AMBIENT_MIN_DELAY_MS = 12_000L
private const val AMBIENT_MAX_DELAY_MS = 19_000L

private val greetingLoadingLines = listOf(
    "Создаём первое приветствие персонажа.",
    "Скоро персонаж оживёт и поздоровается с тобой.",
    "Готовим первые движения — это может занять несколько минут.",
    "Всё ещё работаем над приветствием.",
    "Персонаж готовится к вашей первой встрече.",
)

/**
 * Keep baseline, chat input, and quiet-time reactions out of the tap deck.
 * App entry reuses the existing greeting.
 */
internal fun mainScreenActionPool(availableActions: Set<String>): List<String> {
    val normalized = availableActions.map(HeroLocalStore::normalizeVideoAction).toSet()
    return HeroLocalStore.LIBRARY_VIDEO_ACTIONS.filter { action ->
        action !in setOf("idle", "sleeping", "welcome", "typing_reaction", "bored", "sleepy") && action in normalized
    }
}

internal fun quietHomeReaction(localHour: Int, available: Set<String>): String? = when {
    localHour in 21..22 && "sleepy" in available -> "sleepy"
    localHour in 7..20 && "bored" in available -> "bored"
    else -> null
}

private fun nextAmbientDelayMs(): Long = Random.nextLong(
    AMBIENT_MIN_DELAY_MS,
    AMBIENT_MAX_DELAY_MS + 1L,
)

private fun shuffledActionDeck(actions: List<String>, previousAction: String?): List<String> {
    if (actions.size < 2) return actions
    val shuffled = actions.shuffled().toMutableList()
    if (shuffled.firstOrNull() == previousAction) {
        val replacement = shuffled.indexOfFirst { it != previousAction }
        if (replacement > 0) {
            val first = shuffled[0]
            shuffled[0] = shuffled[replacement]
            shuffled[replacement] = first
        }
    }
    return shuffled
}

private data class KnockRipple(
    val x: Float,
    val y: Float,
    val startedAt: Long,
    val reply: Boolean,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KnockHeroScreen(
    generating: Boolean,
    previewUrl: String?,
    animationFrames: List<String> = emptyList(),
    animationVideoUrl: String? = null,
    animationVideoUrls: Map<String, String> = emptyMap(),
    neutralVideoFrameUrl: String? = null,
    animationFps: Int = 12,
    error: String?,
    accepted: Boolean,
    heroLoading: Boolean = false,
    greetingInterrupted: Boolean = false,
    greetingCanResume: Boolean = true,
    onResumeGreeting: () -> Unit = {},
    onDeferGreeting: () -> Unit = {},
    mascotId: String? = null,
    heroName: String? = null,
    welcomeRequestId: Long? = null,
    onConsumeWelcome: (Long) -> Boolean = { false },
    heroLibrary: List<HeroLibraryItem> = emptyList(),
    stateKey: String? = null,
    stateLabel: String? = null,
    generationLabel: String? = null,
    generationSourceLabel: String = "Командный сервер",
    onKnockComplete: () -> Unit,
    onAccept: () -> Unit,
    onSelectHero: (String) -> Unit = {},
    onDeleteHero: () -> Unit = {},
    onRenameHero: (String, String) -> Unit = { _, _ -> },
    onNewHero: () -> Unit = {},
    onAnimations: (String?) -> Unit = {},
    onChat: () -> Unit = {},
    onCapabilities: () -> Unit = onChat,
    onBehavior: () -> Unit = onChat,
    onCharacterSettings: (() -> Unit)? = null,
    onGenerate: () -> Unit = {},
    onPoll: () -> Unit,
    onRefresh: () -> Unit = {},
    onRefreshHeroes: () -> Unit = {},
    onGenerationSettings: () -> Unit = {},
    onApiKeys: () -> Unit = {},
    onExportHeroes: () -> Unit = {},
    onOverlaySettings: () -> Unit = {},
    onPinWidget: () -> Unit = {},
    screenActive: Boolean = true,
    acceptEnabled: Boolean,
    onStateGesture: ((com.generativemascot.app.state.StateEvent) -> Unit)? = null,
    onAnimationVisible: ((String) -> Unit)? = null,
    onOneShotFinished: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val foreground = rememberAppResumed()
    val visible = screenActive && foreground
    var tapCount by remember { mutableIntStateOf(0) }
    var revealAt by remember { mutableLongStateOf(if (generating) 1L else 0L) }
    var blinkAt by remember { mutableLongStateOf(-1L) }
    var waiting by remember { mutableStateOf(generating) }
    var puppetAction by remember(mascotId) { mutableStateOf<String?>(null) }
    var puppetActionSerial by remember(mascotId) { mutableIntStateOf(0) }
    var actionDeck by remember(mascotId) { mutableStateOf(emptyList<String>()) }
    var previousDeckAction by remember(mascotId) { mutableStateOf<String?>(null) }
    var sleepLocked by remember(mascotId) { mutableStateOf(false) }
    var nextAmbientAt by remember(mascotId) {
        mutableLongStateOf(SystemClock.elapsedRealtime() + nextAmbientDelayMs())
    }
    var quietReactionAt by remember(mascotId, visible) { mutableLongStateOf(SystemClock.elapsedRealtime() + 60_000L) }
    var heroPressed by remember { mutableStateOf(false) }
    var rawDragX by remember { mutableStateOf(0f) }
    var rawDragY by remember { mutableStateOf(0f) }
    var gestureDownX by remember { mutableStateOf(0f) }
    var gestureDownY by remember { mutableStateOf(0f) }
    var gestureDownAt by remember { mutableLongStateOf(0L) }
    var gestureTravel by remember { mutableStateOf(0f) }
    val moodPreferences = remember(context) {
        context.getSharedPreferences(CHAT_MOOD_PREFERENCES, android.content.Context.MODE_PRIVATE)
    }
    val bedtimePreferences = remember(context) {
        context.getSharedPreferences("hero_bedtime_reaction", android.content.Context.MODE_PRIVATE)
    }
    val profanityStore = remember(context) { ChatProfanityStore(context) }
    var homeMood by remember(mascotId) {
        mutableStateOf(mascotId?.let { moodPreferences.readHomeChatMood(it, System.currentTimeMillis()) })
    }
    var moodProfanity by remember(mascotId) { mutableStateOf(false) }
    var lastMoodAction by remember(mascotId) { mutableStateOf<String?>(null) }
    var playedMood by remember(mascotId) { mutableStateOf<HomeChatMood?>(null) }
    DisposableEffect(mascotId, visible, moodPreferences) {
        val id = mascotId
        if (id == null || !visible) return@DisposableEffect onDispose {}
        fun refreshMood() {
            homeMood = moodPreferences.readHomeChatMood(id, System.currentTimeMillis())
            moodProfanity = profanityStore.read(id) == true
        }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "$id.home.at" || key == null) refreshMood()
        }
        moodPreferences.registerOnSharedPreferenceChangeListener(listener)
        refreshMood()
        onDispose { moodPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(mascotId, homeMood, visible) {
        val id = mascotId ?: return@LaunchedEffect
        val mood = homeMood ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        delay((mood.expires - System.currentTimeMillis()).coerceAtLeast(1))
        homeMood = moodPreferences.readHomeChatMood(id, System.currentTimeMillis())
    }
    var greetingLoadingLineIndex by remember(mascotId) { mutableIntStateOf(0) }
    var navigationReady by remember(mascotId) { mutableStateOf(false) }
    var heroPickerExpanded by remember { mutableStateOf(false) }
    var showHomeSettings by remember { mutableStateOf(false) }
    val menuScope = rememberCoroutineScope()
    val menuBlur by animateDpAsState(
        targetValue = if (showHomeSettings) 20.dp else 0.dp,
        animationSpec = tween(if (showHomeSettings) 220 else 180),
        label = "menu background blur",
    )
    val menuSystemBarAlpha by animateFloatAsState(
        targetValue = if (showHomeSettings) 1f else 0f,
        animationSpec = tween(if (showHomeSettings) 220 else 180),
        label = "menu system bars",
    )
    fun closeHomeSettings(afterClose: (() -> Unit)? = null) {
        if (!showHomeSettings) return
        showHomeSettings = false
        if (afterClose != null) menuScope.launch { delay(180); afterClose() }
    }
    val offerPreferences = remember { context.getSharedPreferences("home_capabilities_offer", android.content.Context.MODE_PRIVATE) }
    var offerDismissed by remember(mascotId) {
        mutableStateOf(offerPreferences.getBoolean(mascotId.orEmpty(), false))
    }
    // A picker preview belongs to the active hero it was opened for.
    var pickerSelectedId by remember(mascotId) { mutableStateOf(mascotId) }
    var previousHeroPainter by remember { mutableStateOf<Painter?>(null) }
    var editingHeroName by remember { mutableStateOf(false) }
    var heroNameDraft by remember { mutableStateOf(TextFieldValue()) }
    val heroNameFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(enabled = editingHeroName) {
        editingHeroName = false
        keyboard?.hide()
    }
    BackHandler(enabled = heroPickerExpanded && !editingHeroName) {
        pickerSelectedId = mascotId
        heroPickerExpanded = false
    }
    val heroStrip = rememberLazyListState()
    val dragX by animateFloatAsState(rawDragX, spring(dampingRatio = .68f, stiffness = 360f), label = "puppet-x")
    val dragY by animateFloatAsState(rawDragY, spring(dampingRatio = .68f, stiffness = 360f), label = "puppet-y")
    val heroPressScale by animateFloatAsState(
        targetValue = if (heroPressed) .985f else 1f,
        animationSpec = tween(if (heroPressed) 80 else 140, easing = MascotEase),
        label = "hero press response",
    )
    val ripples = remember { mutableStateListOf<KnockRipple>() }
    val pickerHero = heroLibrary.firstOrNull { it.id == pickerSelectedId }
    val visualMascotId = pickerHero?.id ?: mascotId
    val visualHeroLoading = heroLoading && visualMascotId == mascotId
    val visualPreviewUrl = pickerHero?.baseStill ?: pickerHero?.baseFrames?.firstOrNull() ?: previewUrl
    val visualVideoUrls = if (visualMascotId == mascotId) animationVideoUrls else {
        visualMascotId?.let { HeroLocalStore.current?.actionVideoUrls(it) }.orEmpty()
    }
    val visualNeutralFrameUrl = if (visualMascotId == mascotId) neutralVideoFrameUrl else {
        visualMascotId?.let { HeroLocalStore.current?.neutralVideoFrameFile(it)?.toURI()?.toString() }
    }
    val visualFrames = if (visualMascotId == mascotId) animationFrames else pickerHero?.baseFrames.orEmpty()
    val visualAnimationVideoUrl = if (visualMascotId == mascotId) animationVideoUrl else {
        visualMascotId?.let { HeroLocalStore.current?.performanceVideoFile(it)?.toURI()?.toString() }
    }
    val mainActionPool = remember(animationVideoUrls.keys) {
        mainScreenActionPool(animationVideoUrls.keys)
    }
    val homeReactionActions = remember(animationVideoUrls.keys, stateKey) {
        if ("idle" in animationVideoUrls) {
            com.generativemascot.app.data.chatReactionActions
                .filterNot { it == "idle" || it == "typing_reaction" || it == stateKey }.toSet()
        } else emptySet()
    }
    val moodActions = homeMood?.compatibleActions(animationVideoUrls.keys).orEmpty()
    val moodAnimation = moodActions.firstOrNull()
    val oneShotActions = homeReactionActions + listOfNotNull(lastMoodAction, moodAnimation)
    val showWaiting = !accepted && generating && visualPreviewUrl.isNullOrBlank()
    val heroReady = visualHeroLoading || (!visualPreviewUrl.isNullOrBlank() && !showWaiting)
    var hasPreparedPhysics by remember(visualMascotId) { mutableStateOf(false) }
    val displayName = remember(visualMascotId, heroName, pickerHero?.name) {
        heroHeadingName(
            visualMascotId?.let { HeroLocalStore.current?.mascotName(it) },
            heroName.takeIf { visualMascotId == mascotId },
            pickerHero?.name,
        )
    }
    fun beginNameEditing() {
        heroNameDraft = TextFieldValue(displayName, selection = TextRange(displayName.length))
        editingHeroName = true
    }
    fun saveEditedName() {
        val id = visualMascotId ?: return
        val name = heroNameDraft.text.trim()
        if (name.isBlank()) return
        onRenameHero(id, name)
        editingHeroName = false
        keyboard?.hide()
    }
    LaunchedEffect(editingHeroName) {
        if (editingHeroName) { heroNameFocus.requestFocus(); keyboard?.show() }
    }
    val palette = heroPalette(visualPreviewUrl)
    val menuTopBar = lerp(palette.top, FigmaCanvas, .90f)
    val menuBottomBar = lerp(palette.end, FigmaCanvas, .90f)
    MascotSystemBars(
        if (showHomeSettings) menuTopBar else if (accepted) palette.top else FigmaCanvas,
        if (showHomeSettings) menuBottomBar else if (accepted) palette.end else FigmaCanvas,
    )
    val boldMood = remember(mascotId, visible) {
        mascotId?.let { HeroLocalStore.current?.chatCharacterTone(it) }
            ?.let(::characterPerformanceStyle) == com.generativemascot.app.data.CharacterPerformanceStyle.BOLD
    }
    val currentLine = if (visualHeroLoading) greetingLoadingLines[greetingLoadingLineIndex % greetingLoadingLines.size]
        else homeMood?.line(boldMood, moodProfanity, moodAnimation) ?: "Я в порядке. Можем поболтать."

    fun postponeAmbientAction() {
        nextAmbientAt = SystemClock.elapsedRealtime() + nextAmbientDelayMs()
        quietReactionAt = SystemClock.elapsedRealtime() + 60_000L
    }

    fun takeNextMainAction(): String? {
        val validDeck = actionDeck.filter { it in mainActionPool }
        val deck = if (validDeck.isEmpty()) {
            shuffledActionDeck(mainActionPool, previousDeckAction)
        } else {
            validDeck
        }
        val next = deck.firstOrNull() ?: return null
        actionDeck = deck.drop(1)
        previousDeckAction = next
        return next
    }

    fun playMainAction(action: String) {
        val playable = resolveAvailableHomeAction(action, animationVideoUrls.keys)
        if (playable != "sleeping" && playable != "sleep_loop") sleepLocked = false
        puppetAction = playable
        puppetActionSerial += 1
    }

    val phoneInteraction = rememberHeroPhoneInteraction(
        visualMascotId, enabled = visible && accepted && heroReady && !visualHeroLoading && !heroPickerExpanded && !generating,
    ) {
        postponeAmbientAction()
        if (onStateGesture != null) onStateGesture(com.generativemascot.app.state.StateEvent.SHAKE)
        else listOf("frightened", "curious", "joyful").firstOrNull { it in animationVideoUrls }?.let(::playMainAction)
    }

    LaunchedEffect(welcomeRequestId, mascotId, accepted, heroReady, heroPickerExpanded, visualHeroLoading) {
        if (onStateGesture != null) return@LaunchedEffect
        val request = welcomeRequestId ?: return@LaunchedEffect
        if (!accepted || !heroReady || visualHeroLoading || heroPickerExpanded || visualMascotId != mascotId) return@LaunchedEffect
        // Consume even when no greeting is saved: later downloads/recompositions must not
        // interrupt a user who is already playing. Opening the app never creates a paid job.
        onConsumeWelcome(request)
    }

    var entryAnimationStarted by remember(mascotId) { mutableStateOf(false) }
    LaunchedEffect(mascotId, accepted, heroReady, visualHeroLoading, heroPickerExpanded, visible, animationVideoUrls.keys) {
        if (!entryAnimationStarted && visible && accepted && heroReady && !visualHeroLoading && !heroPickerExpanded && visualMascotId == mascotId) {
            val firstAction = entryWelcomeAction(animationVideoUrls.keys) ?: takeNextMainAction()
            if (firstAction != null) {
                entryAnimationStarted = true
                playMainAction(firstAction)
                postponeAmbientAction()
            }
        }
    }

    LaunchedEffect(heroReady) {
        if (heroReady) tapCount = 0
    }
    LaunchedEffect(generating) {
        if (generating) {
            waiting = true
            revealAt = if (now > 0L) now else 1L
        } else {
            waiting = false
        }
    }
    LaunchedEffect(generating) {
        if (!generating) return@LaunchedEffect
        while (true) {
            delay(2_000)
            onPoll()
        }
    }
    LaunchedEffect(accepted, visible) {
        if (!accepted || !visible) return@LaunchedEffect
        onRefreshHeroes()
        onRefresh()
        while (true) {
            // Ready assets and interactions are local. A slow manifest hash or
            // context request every five seconds competed with video decoding
            // and showed up as a periodic hitch on the carousel.
            delay(60_000)
            onRefresh()
        }
    }
    LaunchedEffect(mascotId, heroPickerExpanded, heroLibrary.map { it.id }) {
        // After an explicit choice, keep its preview on screen while activation
        // is saved. Resetting to the old mascot as soon as the strip collapsed
        // caused a visible back-and-forth flicker.
        if (
            !heroPickerExpanded &&
            (pickerSelectedId == null || heroLibrary.none { it.id == pickerSelectedId })
        ) {
            pickerSelectedId = mascotId
        }
    }
    LaunchedEffect(mascotId) {
        // Route transitions briefly place the new controls under the finger
        // that closed the previous screen. Ignore that same gesture so one tap
        // can never close the gallery and immediately open the hero picker.
        delay(320)
        navigationReady = true
    }
    LaunchedEffect(homeMood, visible, accepted, heroReady, heroPickerExpanded, visualHeroLoading,
        visualMascotId, moodActions, puppetAction, heroPressed, sleepLocked, showHomeSettings, stateKey) {
        if (!visible || !accepted || !heroReady || heroPickerExpanded || visualHeroLoading || visualMascotId != mascotId ||
            heroPressed || sleepLocked || showHomeSettings ||
            stateKey == "sleeping" || stateKey == "sleep_loop" || puppetAction in oneShotActions) return@LaunchedEffect
        val mood = homeMood ?: return@LaunchedEffect
        if (moodActions.isEmpty()) return@LaunchedEffect
        val continuing = playedMood == mood
        if (continuing) delay(3_500L)
        if (System.currentTimeMillis() >= mood.expires) return@LaunchedEffect
        val index = if (continuing) (moodActions.indexOf(lastMoodAction) + 1) % moodActions.size else 0
        val action = moodActions[index]
        lastMoodAction = action
        playedMood = mood
        playMainAction(action)
        postponeAmbientAction()
    }
    LaunchedEffect(mascotId, visualHeroLoading, visible) {
        if (!visualHeroLoading) { greetingLoadingLineIndex = 0; return@LaunchedEffect }
        if (!visible) return@LaunchedEffect
        while (true) {
            delay(9_000)
            greetingLoadingLineIndex = (greetingLoadingLineIndex + 1) % greetingLoadingLines.size
        }
    }
    LaunchedEffect(accepted, mascotId, heroPickerExpanded, mainActionPool, visualHeroLoading, visible) {
        if (onStateGesture != null) return@LaunchedEffect
        if (!visible || !accepted || visualHeroLoading || heroPickerExpanded || mainActionPool.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(500)
            val elapsedNow = SystemClock.elapsedRealtime()
            if (homeMood == null && !sleepLocked && elapsedNow >= nextAmbientAt) {
                takeNextMainAction()?.let(::playMainAction)
                nextAmbientAt = elapsedNow + nextAmbientDelayMs()
            }
        }
    }
    LaunchedEffect(mascotId, visible, accepted, heroReady, heroPickerExpanded, visualHeroLoading, showHomeSettings,
        stateKey, animationVideoUrls.keys) {
        if (!visible || !accepted || !heroReady || heroPickerExpanded || visualHeroLoading || showHomeSettings ||
            stateKey in setOf("sleeping", "sleep_loop")) return@LaunchedEffect
        while (true) {
            delay(1_000)
            val elapsed = SystemClock.elapsedRealtime()
            if (elapsed < quietReactionAt || heroPressed || sleepLocked || puppetAction in homeReactionActions ||
                moodActions.isNotEmpty()) continue
            val time = java.time.LocalDateTime.now()
            val action = quietHomeReaction(time.hour, animationVideoUrls.keys)
            quietReactionAt = elapsed + 5 * 60_000L
            val day = time.toLocalDate().toString()
            if (action == null) continue
            if (action == "sleepy") {
                val id = mascotId ?: continue
                if (bedtimePreferences.getString(id, null) == day) continue
                bedtimePreferences.edit().putString(id, day).apply()
            }
            playMainAction(action)
        }
    }
    LaunchedEffect(heroPickerExpanded) {
        if (accepted) postponeAmbientAction()
    }
    LaunchedEffect(visible, showWaiting, ripples.size) {
        if (!visible) return@LaunchedEffect
        while (showWaiting || ripples.isNotEmpty()) {
            now = withInfiniteAnimationFrameMillis { it }
            ripples.removeAll { now - it.startedAt > RippleMs }
        }
    }

    fun buzz(ms: Long, amp: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(ms, amp.coerceIn(1, 255)))
    }

    fun knock(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        if (showWaiting) {
            ripples += KnockRipple(x, y, t, false)
            blinkAt = t + 80
            buzz(18, 70)
            return
        }
        ripples += KnockRipple(x, y, t, false)
        buzz(20, 90)
        if (accepted) {
            return
        }
        if (heroReady) {
            return
        }
        val next = tapCount + 1
        tapCount = next
        if (next >= 3) {
            tapCount = 0
            waiting = true
            revealAt = t
            ripples += KnockRipple(x, y, t + 210, true)
            onKnockComplete()
        }
    }
    val onKnock = rememberUpdatedState { x: Float, y: Float -> knock(x, y) }

    val systemDensity = LocalDensity.current
    val statusBarHeight = WindowInsets.statusBars.getTop(systemDensity).toFloat()
    val navigationBarHeight = WindowInsets.navigationBars.getBottom(systemDensity).toFloat()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(if (accepted) heroGradient(palette) else Brush.linearGradient(listOf(KnockBg, KnockBg)))
            .drawWithContent {
                drawContent()
                if (menuSystemBarAlpha > 0f) {
                    drawRect(menuTopBar.copy(alpha = menuSystemBarAlpha), size = Size(size.width, statusBarHeight))
                    drawRect(menuBottomBar.copy(alpha = menuSystemBarAlpha), topLeft = Offset(0f, size.height - navigationBarHeight),
                        size = Size(size.width, navigationBarHeight))
                }
            }
            .safeDrawingPadding(),
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        var originalHeight by remember { mutableStateOf(maxHeight) }
        val keyboardVisible = WindowInsets.ime.getBottom(systemDensity) > 0
        // The home screen remains composed behind chat. Keep its layout stable
        // while the chat keyboard resizes the window and while it dismisses.
        if (screenActive && !editingHeroName && !keyboardVisible && originalHeight != maxHeight) {
            SideEffect { originalHeight = maxHeight }
        }
        val layoutHeight = if (editingHeroName || !screenActive || keyboardVisible) originalHeight else screenHeight
        val contentWidth = (screenWidth - 48.dp).coerceAtLeast(1.dp)
        val heroTop by animateDpAsState(layoutHeight * if (heroPickerExpanded) .235f else .15f,
            tween(240, easing = MascotEase), label = "hero screen top")
        val heroHeight by animateDpAsState((layoutHeight * if (heroPickerExpanded) .50f else .60f).coerceAtLeast(1.dp),
            tween(240, easing = MascotEase), label = "hero screen height")
        val heroWidth by animateDpAsState(screenWidth * if (heroPickerExpanded) .99f else 1.22f,
            tween(240, easing = MascotEase), label = "hero screen width")
        val headingTop by animateDpAsState(layoutHeight * if (heroPickerExpanded) .115f else .155f,
            tween(240, easing = MascotEase), label = "hero heading top")
        val chatBottom = screenHeight * .041f
        val quoteBottom = chatBottom + 50.dp + screenHeight * .06f
        Box(Modifier.fillMaxSize().blur(menuBlur)) {
        if (!accepted) {
            Image(
                painter = painterResource(if (showWaiting) R.drawable.figma_waiting_bg else R.drawable.figma_mascot_bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showWaiting) {
            Text(
                "ТАМ\nКТО-ТО\nЕСТЬ",
                color = Color.Black,
                fontSize = 37.sp,
                lineHeight = 45.sp,
                fontFamily = RubikOne,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 126.dp, start = 24.dp, end = 24.dp),
            )
            Text(
                generationLabel ?: "ждём персонажа",
                color = Color.Black.copy(alpha = .22f),
                fontSize = 12.sp,
                fontFamily = SbSansDisplayMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 44.dp, start = 32.dp, end = 32.dp),
            )
        } else if (!accepted) {
            Text(
                if (heroReady) displayName.uppercase() else "ПОСТУЧИ",
                color = if (heroReady) Color.White.copy(alpha = .50f) else Color.Black,
                fontSize = if (heroReady) 64.sp else 37.sp,
                fontFamily = RubikOne,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (heroReady) 78.dp else 180.dp, start = 24.dp, end = 24.dp),
            )
        } else {
            val nameModifier = Modifier.align(Alignment.TopCenter).zIndex(2f)
                .padding(top = headingTop, start = 24.dp, end = 24.dp).fillMaxWidth()
            if (editingHeroName) HomeNameEditor(heroNameDraft, { heroNameDraft = it }, ::saveEditedName, heroNameFocus, nameModifier)
            else HomeHeading(displayName, nameModifier.then(if (heroPickerExpanded) Modifier.mascotClickable(
                enabled = visualMascotId != null, onClickLabel = "Изменить имя", onClick = ::beginNameEditing) else Modifier))
            AnimatedVisibility(visible = !heroPickerExpanded,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 28.dp).zIndex(2f),
                enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                HomeSettingsButton(onClick = { showHomeSettings = true }, enabled = navigationReady)
            }
            AnimatedVisibility(visible = heroPickerExpanded && !editingHeroName,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 15.dp, top = 33.dp).zIndex(2f),
                enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                MascotPickerBackButton(onBack = { pickerSelectedId = mascotId; heroPickerExpanded = false })
            }
            AnimatedVisibility(visible = heroPickerExpanded && !editingHeroName,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 28.dp).zIndex(2f),
                enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                Box(Modifier.size(50.dp).clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .06f), Color.White.copy(alpha = .4f))))
                    .border(2.dp, Color.White.copy(alpha = .16f), CircleShape)
                    .mascotClickable(enabled = navigationReady && !heroLoading, onClickLabel = "Анимации героя") {
                        heroPickerExpanded = false; onAnimations(visualMascotId)
                    }, contentAlignment = Alignment.Center) { HomeFigmaIcon("hero-grid", null, size = 24.dp) }
            }
            AnimatedVisibility(visible = heroPickerExpanded,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = layoutHeight * .19f).zIndex(3f),
                enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xff222222))
                    .mascotClickable(enabled = visualMascotId != null && (!editingHeroName || heroNameDraft.text.isNotBlank()),
                        onClickLabel = if (editingHeroName) "Сохранить имя" else "Изменить имя") {
                        if (editingHeroName) saveEditedName() else beginNameEditing()
                    }, contentAlignment = Alignment.Center) {
                    HomeFigmaIcon(if (editingHeroName) "hero-confirm" else "hero-edit", null, size = if (editingHeroName) 18.dp else 19.dp)
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val t = now
            if (!showWaiting && !accepted) {
                val marksY = size.height * if (heroReady) 0.79f else 0.31f
                val gap = 27.dp.toPx()
                val startX = size.width / 2f - gap
                repeat(3) { i ->
                    drawCircle(
                        color = Color.Black.copy(alpha = if (i <= tapCount) 1f else 0.20f),
                        radius = 7.dp.toPx(),
                        center = Offset(startX + i * gap, marksY),
                    )
                }
            }
            if (showWaiting) {
                val glowCx = size.width * 0.5f
                val glowCy = size.height * 0.53f
                val pulse = ((sin(t / 900.0) + 1.0) * 0.5).toFloat()
                val glowR = min(size.width, size.height) * (pulse * 0.012f + 0.21f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color(0x224A90C8),
                            0.55f to Color(0x0A7EB6D9),
                            1f to Color(0x00E8F4FC),
                        ),
                        center = Offset(glowCx, glowCy),
                        radius = glowR,
                    ),
                    radius = glowR,
                    center = Offset(glowCx, glowCy),
                )
            }
            ripples.toList().forEach { ripple ->
                val age = t - ripple.startedAt
                if (age < 0) return@forEach
                val p = (age / RippleMs).coerceIn(0f, 1f)
                val fade = 1f - p
                val ease = 1f - fade * fade
                val span = min(size.width, size.height)
                val radius = ease * span * (if (ripple.reply) 0.12f else 0.16f) + 12f
                drawCircle(
                    color = KnockInk.copy(alpha = fade * 0.85f),
                    radius = radius,
                    center = Offset(ripple.x, ripple.y),
                    style = Stroke(width = if (ripple.reply) 4f else 3f),
                )
            }
            if (showWaiting) {
                val rx = min(140f, size.width * 0.2f)
                val cx = size.width / 2f
                val cy = size.height * 0.53f
                val bob = (sin((t - revealAt).coerceAtLeast(0L) / 650.0) * 1.5).toFloat()
                val blink = blinkAmount(t, blinkAt, revealAt)
                if (blinkAt >= 0L && t - blinkAt > 220L) blinkAt = -1L
                val eyeR = max(8f, rx * 0.12f)
                val eyeDx = rx * 0.22f
                val eyeY = cy + bob
                val lid = max(0.12f, 1f - blink)
                val leftX = cx - eyeDx
                val rightX = cx + eyeDx
                val pupil = KnockInk
                val shine = max(2f, eyeR * 0.28f)
                listOf(leftX, rightX).forEach { ex ->
                    scale(1f, lid, Offset(ex, eyeY)) {
                        drawCircle(color = pupil, radius = eyeR, center = Offset(ex, eyeY))
                        drawCircle(
                            color = Color.White.copy(alpha = 0.82f),
                            radius = shine,
                            center = Offset(ex - eyeR * 0.28f, eyeY - eyeR * 0.3f),
                        )
                    }
                }
            }
        }
        if (heroReady) {
            val heroModifier = Modifier
                .testTag("hero-interaction")
                .align(if (accepted) Alignment.TopCenter else Alignment.Center)
                .then(if (accepted) Modifier.padding(top = heroTop) else Modifier)
                .then(if (accepted) Modifier.requiredSize(width = heroWidth, height = heroHeight)
                    else Modifier.size(width = contentWidth, height = screenHeight * .52f))
                .graphicsLayer {
                    val movement = phoneInteraction.pose.artwork(hasPreparedPhysics)
                    scaleX = heroPressScale * movement.scaleX
                    scaleY = heroPressScale * movement.scaleY
                    // Prepared packs own all tilt in GL. Only shake feedback is
                    // shared here, so the outer view cannot double the tilt.
                    translationX = movement.translationX.dp.toPx()
                    translationY = movement.translationY.dp.toPx()
                    rotationY = movement.rotationY
                    rotationX = movement.rotationX
                    cameraDistance = size.width.coerceAtLeast(1f) * 2.5f
                    rotationZ = movement.rotationZ
                    transformOrigin = TransformOrigin(.5f, .8f)
                }
                .pointerInteropFilter { event ->
                    if (visualHeroLoading) return@pointerInteropFilter true
                    if (heroPickerExpanded && onStateGesture != null) return@pointerInteropFilter true
                    if (!accepted) return@pointerInteropFilter false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            welcomeRequestId?.let(onConsumeWelcome)
                            heroPressed = true
                            sleepLocked = false
                            postponeAmbientAction()
                            gestureDownX = event.x
                            gestureDownY = event.y
                            gestureDownAt = event.eventTime
                            gestureTravel = 0f
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.x - gestureDownX
                            val dy = event.y - gestureDownY
                            gestureTravel = max(gestureTravel, kotlin.math.abs(dx) + kotlin.math.abs(dy))
                            rawDragX = dx.coerceIn(-240f, 240f)
                            rawDragY = dy.coerceIn(-150f, 150f)
                        }
                        MotionEvent.ACTION_UP -> {
                            heroPressed = false
                            val dx = event.x - gestureDownX
                            val dy = event.y - gestureDownY
                            val travel = max(gestureTravel, kotlin.math.abs(dx) + kotlin.math.abs(dy))
                            val heldMs = event.eventTime - gestureDownAt
                            when {
                                heldMs >= 500L && travel < 70f -> {
                                    if (onStateGesture != null) onStateGesture(com.generativemascot.app.state.StateEvent.HOLD)
                                    else { sleepLocked = true; playMainAction("sleeping") }
                                    buzz(45, 50)
                                }
                                -dy > 90f && -dy > kotlin.math.abs(dx) * 1.2f -> {
                                    if (onStateGesture != null) onStateGesture(com.generativemascot.app.state.StateEvent.JOY)
                                    else playMainAction("joyful")
                                    buzz(48, 110)
                                }
                                else -> {
                                    playMainAction(takeNextMainAction() ?: "joyful")
                                    buzz(24, 85)
                                }
                            }
                            rawDragX = 0f
                            rawDragY = 0f
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            heroPressed = false
                            rawDragX = 0f
                            rawDragY = 0f
                        }
                    }
                    true
                }
            val interaction = PuppetInteraction(
                actionName = puppetAction,
                actionSerial = puppetActionSerial,
                dragX = dragX,
                dragY = dragY,
            )
            if (visualHeroLoading && !visualPreviewUrl.isNullOrBlank()) {
                Box(heroModifier) {
                    GroundedHeroArtwork(visualPreviewUrl, Modifier.fillMaxSize()) {
                        AsyncImage(model = resolveMediaUrl(visualPreviewUrl), contentDescription = "Герой",
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                    CircularProgressIndicator(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(20.dp),
                        color = Color.White.copy(alpha = .75f), strokeWidth = 2.dp)
                }
            } else if (visualHeroLoading) {
                HeroAnimationLoader(modifier = heroModifier)
            } else if (accepted && visualMascotId != mascotId) {
                GroundedHeroArtwork(visualPreviewUrl, heroModifier) {
                    AsyncImage(
                        model = resolveMediaUrl(visualPreviewUrl),
                        contentDescription = "Предпросмотр $displayName", contentScale = ContentScale.Fit,
                        placeholder = previousHeroPainter,
                        onSuccess = { previousHeroPainter = it.painter },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else if (accepted) {
                // Recreate the complete player subtree when the carousel changes
                // hero. Reusing AndroidView here leaves the previous neutral image
                // attached while the new decoder is prepared, so two characters
                // can be visible at once.
                key(visualMascotId) {
                    Box(heroModifier) {
                        var videoReady by remember(visualMascotId) { mutableStateOf(false) }
                        if (visualVideoUrls.isNotEmpty() || !visualAnimationVideoUrl.isNullOrBlank()) {
                            GroundedHeroArtwork(visualNeutralFrameUrl ?: visualPreviewUrl, Modifier.fillMaxSize()) {
                                SoraMascotVideo(
                                    videoUrl = visualAnimationVideoUrl, videoUrls = visualVideoUrls,
                                    neutralFrameUrl = visualNeutralFrameUrl, action = stateKey ?: "idle",
                                    interaction = interaction, lighting = phoneInteraction.lighting,
                                    onAnimationVisible = onAnimationVisible,
                                    playOnceActions = oneShotActions,
                                    onOneShotFinished = { finished ->
                                        onOneShotFinished?.invoke(finished)
                                        if (puppetAction == finished && finished in oneShotActions) {
                                            puppetAction = defaultHomeVideoAction(animationVideoUrls.keys)
                                            puppetActionSerial += 1
                                            nextAmbientAt = SystemClock.elapsedRealtime() + nextAmbientDelayMs()
                                        }
                                    },
                                    playbackEnabled = visible, useNeutralTransitions = false,
                                    onFirstFrame = { videoReady = true },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            if (!videoReady) {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center).size(30.dp),
                                    color = Color.White.copy(alpha = .8f), strokeWidth = 2.5.dp,
                                )
                            }
                        } else GroundedHeroArtwork(visualPreviewUrl, Modifier.fillMaxSize()) {
                            PngSequence(frames = visualFrames, fps = animationFps, decodeSize = 500,
                                fallback = visualPreviewUrl, mascotIdHint = visualMascotId,
                                puppetAction = stateKey ?: "content", interaction = interaction,
                                contentDescription = "Герой", modifier = Modifier.fillMaxSize(), playbackEnabled = visible)
                        }
                    }
                }
            } else GroundedHeroArtwork(visualPreviewUrl, heroModifier, enabled = accepted) { PngSequence(
                frames = if (accepted) visualFrames else emptyList(),
                fps = animationFps,
                decodeSize = 500,
                fallback = visualPreviewUrl,
                mascotIdHint = visualMascotId,
                puppetAction = stateKey ?: "content",
                interaction = interaction,
                contentDescription = "Герой",
                modifier = Modifier.fillMaxSize(),
                playbackEnabled = visible,
            ) }
        }
        if (!accepted) {
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    detectTapGestures { offset -> onKnock.value(offset.x, offset.y) }
                },
            )
        }
        if (accepted && heroPickerExpanded && !editingHeroName && heroLibrary.size > 1) {
            // Use the same carousel state as the thumbnails, so dragging the
            // character and dragging the strip always select the same hero.
            Box(
                Modifier.align(Alignment.TopCenter)
                    .padding(top = layoutHeight * .21f)
                    .fillMaxWidth()
                    .height(layoutHeight * .55f)
                    .zIndex(1f)
                    .testTag("hero-picker-swipe-area")
                    .pointerInput(heroPickerExpanded, heroLibrary.map { it.id }) {
                        var startIndex = -1
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                startIndex = heroLibrary.indexOfFirst { it.id == pickerSelectedId }
                                totalDrag = 0f
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                totalDrag += amount
                                heroStrip.dispatchRawDelta(-amount)
                            },
                            onDragEnd = {
                                val info = heroStrip.layoutInfo
                                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                                val nearest = info.visibleItemsInfo
                                    .filter { item -> heroLibrary.any { it.id == item.key } }
                                    .minByOrNull { item -> kotlin.math.abs(item.offset + item.size / 2 - center) }
                                val nearestIndex = heroLibrary.indexOfFirst { it.id == nearest?.key }
                                val base = startIndex.coerceIn(0, heroLibrary.lastIndex)
                                val target = if (nearestIndex == base && kotlin.math.abs(totalDrag) >= 32.dp.toPx()) {
                                    base + if (totalDrag < 0f) 1 else -1
                                } else if (nearestIndex >= 0) nearestIndex else base
                                menuScope.launch { heroStrip.animateScrollToItem(target.coerceIn(0, heroLibrary.lastIndex) + 1) }
                            },
                            onDragCancel = {
                                val index = heroLibrary.indexOfFirst { it.id == pickerSelectedId }
                                if (index >= 0) menuScope.launch { heroStrip.animateScrollToItem(index + 1) }
                            },
                        )
                    },
            )
        }
        if (!accepted && !heroReady && !showWaiting && error == null) {
            Text(
                "чтобы сделать персонажа",
                color = Color.Black.copy(alpha = .20f),
                fontSize = 12.sp,
                fontFamily = SbSansDisplayMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
            )
        }
        if (heroReady && !accepted) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .padding(bottom = 72.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onGenerate,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = .58f)),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.regenerate_hero_icon),
                        contentDescription = "Другой герой",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .size(width = 155.dp, height = 50.dp)
                        .clip(CircleShape)
                        .background(if (acceptEnabled) FigmaInk else FigmaInk.copy(alpha = .40f))
                        .clickable(enabled = acceptEnabled, onClick = onAccept),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.keep_hero_check_icon),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("ОСТАВИМ", color = Color.White, fontFamily = RubikOne, fontSize = 13.sp)
                }
            }
        }
        if (accepted) {
            val showOffer = homeMood == null && !offerDismissed && !visualHeroLoading && visualMascotId == mascotId
            fun dismissOffer() {
                offerDismissed = true
                offerPreferences.edit().putBoolean(mascotId.orEmpty(), true).apply()
            }
            AnimatedVisibility(visible = !heroPickerExpanded,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = quoteBottom),
                enter = fadeIn(tween(200)) + slideInVertically(tween(240, easing = MascotEase)) { it / 6 },
                exit = fadeOut(tween(120))) {
                if (error != null && !greetingInterrupted) {
                    Text(
                        error,
                        color = KnockInk,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(screenWidth * .817f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.White.copy(alpha = .88f))
                            .heightIn(max = 138.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("hero-error"),
                    )
                } else HomeQuoteCard(
                    line = if (showOffer) "Давай расширим мои возможности" else currentLine,
                    palette = palette, offer = showOffer,
                    onAcceptOffer = { dismissOffer(); onCapabilities() },
                    onDismissOffer = ::dismissOffer,
                    modifier = Modifier.size(width = screenWidth * .817f,
                        height = if (showOffer) (screenHeight * .18f).coerceIn(120.dp, 144.dp)
                        else (screenHeight * .14f).coerceIn(96.dp, 112.dp)).testTag("hero-quote"),
                )
            }
            AnimatedVisibility(
                visible = heroPickerExpanded && !editingHeroName,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = screenHeight * .153f)
                    .width(contentWidth)
                    .height(80.dp),
                enter = fadeIn(tween(150)) + slideInVertically(tween(220, easing = MascotEase)) { it / 3 },
                exit = fadeOut(tween(100)) + slideOutVertically(tween(160, easing = MascotEase)) { it / 4 },
            ) {
                val entries = listOf(MascotPickerItem("add-new-hero", "Создать нового героя", selectable = false)) +
                    heroLibrary.map { MascotPickerItem(it.id, "Выбрать ${it.name ?: "героя"}") }
                MascotPickerCarousel(
                    items = entries, selectedId = pickerSelectedId, onSelected = { pickerSelectedId = it },
                    state = heroStrip, enabled = heroPickerExpanded && !editingHeroName,
                    modifier = Modifier.fillMaxSize().testTag("hero-picker-carousel"),
                    onExtraClick = { if (navigationReady && !heroLoading) onNewHero() },
                ) { item, selected, itemModifier ->
                    if (!item.selectable) {
                        Box(itemModifier.clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .06f), Color.White.copy(alpha = .4f))))
                            .border(2.dp, Color.White.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
                            HomeFigmaIcon("hero-plus", null, size = 24.dp)
                        }
                    } else {
                        val hero = heroLibrary.firstOrNull { it.id == item.id }
                        if (hero != null) HeroThumbnail(hero, itemModifier.border(
                            2.dp, if (selected) Color.White.copy(alpha = .92f) else Color.Transparent, CircleShape))
                    }
                }
            }
            AnimatedVisibility(visible = !heroPickerExpanded,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = chatBottom),
                enter = fadeIn(tween(200)) + slideInVertically(tween(240, easing = MascotEase)) { it / 6 },
                exit = fadeOut(tween(120))) {
                HomeChatButton(onClick = onChat, enabled = navigationReady)
            }
            AnimatedVisibility(visible = heroPickerExpanded && !editingHeroName,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = screenHeight * .066f),
                enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                Box(Modifier.size(50.dp).clip(CircleShape).background(Color(0xff222222))
                    .mascotClickable(enabled = navigationReady && !heroLoading, onClickLabel = "Выбрать героя") {
                        pickerSelectedId?.takeIf { it != mascotId }?.let(onSelectHero)
                        heroPickerExpanded = false
                    }, contentAlignment = Alignment.Center) { HomeFigmaIcon("hero-confirm", null, size = 18.dp) }
            }
        }
        if (!accepted && error != null && !greetingInterrupted) {
            Text(
                error,
                color = KnockInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 96.dp),
            )
        }
        if (!accepted && !showWaiting && !heroReady) {
            androidx.compose.material3.TextButton(
                onClick = onGenerationSettings,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            ) {
                Text(
                    "Источник: $generationSourceLabel · изменить",
                    color = KnockInk.copy(alpha = .58f),
                    fontSize = 13.sp,
                )
            }
        }
        }
        val menuEase = CubicBezierEasing(.23f, 1f, .32f, 1f)
        AnimatedVisibility(
            visible = accepted && showHomeSettings,
            enter = fadeIn(tween(220, easing = menuEase)),
            exit = fadeOut(tween(180, easing = menuEase)),
            modifier = Modifier.fillMaxSize().zIndex(3f),
        ) {
            HomeSettingsMenu(
                onClose = { closeHomeSettings() },
                onHeroes = { closeHomeSettings {
                    if (onCharacterSettings != null) onCharacterSettings()
                    else { pickerSelectedId = mascotId; heroPickerExpanded = true }
                } },
                onAnimations = { closeHomeSettings { onAnimations(mascotId) } },
                onOverlay = { closeHomeSettings(onOverlaySettings) },
                onBehavior = { closeHomeSettings(onBehavior) },
                onCapabilities = { closeHomeSettings(onCapabilities) },
                onApiKeys = { closeHomeSettings(onApiKeys) },
                onExportHeroes = { closeHomeSettings(onExportHeroes) },
                onPinWidget = { closeHomeSettings(onPinWidget) },
                onDelete = { closeHomeSettings(onDeleteHero) },
                contentModifier = Modifier,
            )
        }
    }
    if (greetingInterrupted) {
        MascotConfirmationSheet(
            title = "Приветствие прервалось",
            message = error ?: "Герой и готовые результаты сохранены. Можно продолжить прежнее задание.",
            confirmLabel = "Продолжить",
            dismissLabel = "Пока без приветствия",
            dismissible = false,
            enabled = greetingCanResume,
            onConfirm = onResumeGreeting,
            onDismiss = onDeferGreeting,
        )
    }

}

private fun blinkAmount(now: Long, blinkAt: Long, revealAt: Long): Float {
    if (blinkAt < 0L) {
        val since = now - revealAt
        if (since > 200L) {
            val phase = since % 1800L
            if (phase < 170L) {
                return sin(phase / 170.0 * Math.PI).toFloat()
            }
        }
        return 0f
    }
    val dt = now - blinkAt
    if (dt < 0L || dt > 220L) return 0f
    return sin(dt / 220.0 * Math.PI).toFloat()
}
