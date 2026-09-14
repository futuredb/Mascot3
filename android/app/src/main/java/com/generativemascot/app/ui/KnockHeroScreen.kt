package com.generativemascot.app.ui

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.SentimentSatisfiedAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

private val KnockBg = Color(0xFFE8F4FC)
private val KnockInk = Color(0xFF5B160F)
private const val RippleMs = 760f

private val heroLines = mapOf(
    "idle" to listOf(
        "Я тут. Просто наблюдаю за твоим днём.",
        "Кажется, у меня созрел отличный план. Но чуть позже.",
        "Тихий режим тоже считается приключением.",
        "Иногда приятно просто побыть рядом.",
    ),
    "joyful" to listOf(
        "О, вот теперь день стал заметно лучше!",
        "Ещё раз! Кажется, мне это понравилось.",
        "Ты вернулся — можно снова радоваться.",
    ),
    "sleeping" to listOf(
        "Пять минут тишины… и я снова герой.",
        "Не буди. Я сохраняю энергию для приключений.",
        "Даже маскотам иногда нужен тихий час.",
    ),
    "sleep_loop" to listOf(
        "Тс-с… мне снится что-то очень хорошее.",
        "Заряжаюсь спокойствием. Почти готов.",
    ),
    "dancing" to listOf(
        "Если есть ритм, повод уже не нужен.",
        "Смотри, это мой фирменный танец!",
        "Серьёзность отменяется на несколько секунд.",
    ),
)

internal fun heroLine(action: String?, index: Int): String {
    val normalized = HeroLocalStore.normalizeVideoAction(action ?: "idle")
    val lines = heroLines[normalized] ?: heroLines.getValue("idle")
    return lines[Math.floorMod(index, lines.size)]
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
    mascotId: String? = null,
    heroName: String? = null,
    heroLibrary: List<HeroLibraryItem> = emptyList(),
    stateKey: String? = null,
    stateLabel: String? = null,
    generationLabel: String? = null,
    onKnockComplete: () -> Unit,
    onAccept: () -> Unit,
    onSelectHero: (String) -> Unit = {},
    onNewHero: () -> Unit = {},
    onAnimations: () -> Unit = {},
    onGenerate: () -> Unit = {},
    onPoll: () -> Unit,
    onRefresh: () -> Unit = {},
    onRefreshHeroes: () -> Unit = {},
    acceptEnabled: Boolean,
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
    var now by remember { mutableLongStateOf(0L) }
    var tapCount by remember { mutableIntStateOf(0) }
    var revealAt by remember { mutableLongStateOf(if (generating) 1L else 0L) }
    var blinkAt by remember { mutableLongStateOf(-1L) }
    var waiting by remember { mutableStateOf(generating) }
    var puppetTap by remember { mutableIntStateOf(0) }
    var puppetPet by remember { mutableIntStateOf(0) }
    var puppetPlay by remember { mutableIntStateOf(0) }
    var puppetSpin by remember { mutableIntStateOf(0) }
    var puppetDance by remember { mutableIntStateOf(0) }
    var puppetSleep by remember { mutableIntStateOf(0) }
    var heroPressed by remember { mutableStateOf(false) }
    var rawDragX by remember { mutableStateOf(0f) }
    var rawDragY by remember { mutableStateOf(0f) }
    var gestureDownX by remember { mutableStateOf(0f) }
    var gestureDownY by remember { mutableStateOf(0f) }
    var gestureDownAt by remember { mutableLongStateOf(0L) }
    var gestureTravel by remember { mutableStateOf(0f) }
    var quoteAction by remember(mascotId) {
        mutableStateOf(HeroLocalStore.normalizeVideoAction(stateKey ?: "idle"))
    }
    var quoteIndex by remember(mascotId) { mutableIntStateOf(0) }
    var navigationReady by remember(mascotId) { mutableStateOf(false) }
    var heroPickerExpanded by remember { mutableStateOf(false) }
    var pickerSelectedId by remember { mutableStateOf<String?>(null) }
    var confirmNewHero by remember { mutableStateOf(false) }
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
    val showWaiting = generating && visualPreviewUrl.isNullOrBlank()
    val heroReady = !visualPreviewUrl.isNullOrBlank() && !showWaiting
    val displayName = pickerHero?.name?.takeIf { it.isNotBlank() }
        ?: heroName?.takeIf { it.isNotBlank() }
        ?: "ГЕРОЙ"
    val palette = heroPalette(visualPreviewUrl)
    MascotSystemBars(if (accepted) palette.top else FigmaCanvas, if (accepted) palette.end else FigmaCanvas)
    val currentLine = heroLine(quoteAction, quoteIndex)
    val quoteBottom by animateDpAsState(
        targetValue = if (heroPickerExpanded) 222.dp else 128.dp,
        animationSpec = tween(220, easing = MascotEase),
        label = "quote lift",
    )

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
    LaunchedEffect(accepted) {
        if (!accepted) return@LaunchedEffect
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
    LaunchedEffect(heroLibrary.map { it.id }, heroPickerExpanded) {
        if (heroPickerExpanded) {
            val index = heroLibrary.indexOfFirst { it.id == pickerSelectedId }
            if (index >= 0) heroStrip.scrollToItem(index)
        }
    }
    LaunchedEffect(heroStrip, heroPickerExpanded, heroLibrary) {
        if (!heroPickerExpanded) return@LaunchedEffect
        snapshotFlow {
            val info = heroStrip.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { item ->
                kotlin.math.abs(item.offset + item.size / 2 - viewportCenter)
            }?.key as? String
        }.filterNotNull().distinctUntilChanged().collect { centeredId ->
            if (heroLibrary.any { it.id == centeredId }) pickerSelectedId = centeredId
        }
    }
    LaunchedEffect(mascotId) {
        // Route transitions briefly place the new controls under the finger
        // that closed the previous screen. Ignore that same gesture so one tap
        // can never close the gallery and immediately open the hero picker.
        delay(320)
        navigationReady = true
    }
    LaunchedEffect(mascotId, stateKey) {
        quoteAction = HeroLocalStore.normalizeVideoAction(stateKey ?: "idle")
        quoteIndex = 0
    }
    LaunchedEffect(mascotId, accepted, quoteAction) {
        if (!accepted) return@LaunchedEffect
        while (true) {
            delay(if (quoteAction == "idle" || quoteAction == "sleep_loop" || quoteAction == "sleeping") 9_000 else 7_000)
            if (quoteAction == "joyful" || quoteAction == "dancing") {
                quoteAction = "idle"
                quoteIndex = 0
                return@LaunchedEffect
            }
            quoteIndex += 1
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = withInfiniteAnimationFrameMillis { it }
        }
    }

    fun buzz(ms: Long, amp: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(ms, amp.coerceIn(1, 255)))
    }

    fun knock(x: Float, y: Float) {
        val t = now
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(if (accepted) heroGradient(palette) else Brush.linearGradient(listOf(KnockBg, KnockBg)))
            .safeDrawingPadding(),
    ) {
        val contentWidth = (maxWidth - 48.dp).coerceAtLeast(1.dp)
        val heroTop = 104.dp
        val heroHeight = (maxHeight - heroTop - 240.dp).coerceAtLeast(1.dp)
        val density = LocalDensity.current
        val heroWidthPx = with(density) { contentWidth.toPx() }
        val heroHeightPx = with(density) { heroHeight.toPx() }
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
            Text(
                displayName.uppercase(),
                fontSize = 48.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = RubikBubbles,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = .50f), Color.Transparent),
                    ),
                ),
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 24.dp, start = 72.dp, end = 72.dp)
                    .fillMaxWidth()
                    .mascotClickable(
                        enabled = navigationReady,
                        onClickLabel = "Эмоции и имя героя",
                        onClick = onAnimations,
                    ),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val t = now
            ripples.removeAll { t - it.startedAt > RippleMs }
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
                .align(if (accepted) Alignment.TopCenter else Alignment.Center)
                .then(if (accepted) Modifier.padding(top = heroTop) else Modifier)
                .size(width = contentWidth, height = if (accepted) heroHeight else maxHeight * .52f)
                .graphicsLayer {
                    scaleX = heroPressScale
                    scaleY = heroPressScale
                }
                .pointerInteropFilter { event ->
                    if (!accepted) return@pointerInteropFilter false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            heroPressed = true
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
                                    puppetSleep += 1
                                    quoteAction = "sleeping"
                                    quoteIndex += 1
                                    buzz(45, 50)
                                }
                                -dy > 90f && -dy > kotlin.math.abs(dx) * 1.2f -> {
                                    puppetDance += 1
                                    quoteAction = "dancing"
                                    quoteIndex += 1
                                    buzz(48, 110)
                                }
                                kotlin.math.abs(dx) > 90f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f -> {
                                    puppetSpin += 1
                                    quoteAction = "dancing"
                                    quoteIndex += 1
                                    buzz(38, 85)
                                }
                                travel > 70f -> {
                                    puppetPet += 1
                                    quoteAction = "joyful"
                                    quoteIndex += 1
                                    buzz(35, 60)
                                }
                                event.y < heroHeightPx * .46f -> {
                                    puppetPet += 1
                                    quoteAction = "joyful"
                                    quoteIndex += 1
                                    buzz(28, 55)
                                }
                                event.x > heroWidthPx * .68f && event.y < heroHeightPx * .78f -> {
                                    puppetTap += 1
                                    quoteAction = "joyful"
                                    quoteIndex += 1
                                    buzz(28, 100)
                                }
                                else -> {
                                    puppetPlay += 1
                                    quoteAction = "joyful"
                                    quoteIndex += 1
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
                tapSerial = puppetTap,
                petSerial = puppetPet,
                playSerial = puppetPlay,
                spinSerial = puppetSpin,
                danceSerial = puppetDance,
                sleepSerial = puppetSleep,
                dragX = dragX,
                dragY = dragY,
            )
            if (accepted && visualMascotId != mascotId) {
                // Carousel previews switch immediately, but stay as one still.
                // Starting a decoder for every item crossed during a swipe made
                // old and new Android video surfaces overlap and feel stuck.
                AsyncImage(
                    model = resolveMediaUrl(visualPreviewUrl),
                    contentDescription = "Предпросмотр $displayName",
                    contentScale = ContentScale.Fit,
                    modifier = heroModifier,
                )
            } else if (accepted && (visualVideoUrls.isNotEmpty() || !visualAnimationVideoUrl.isNullOrBlank())) {
                // Recreate the complete player subtree when the carousel changes
                // hero. Reusing AndroidView here leaves the previous neutral image
                // attached while the new decoder is prepared, so two characters
                // can be visible at once.
                key(visualMascotId) {
                    Box(heroModifier) {
                        // Multi-action packs already carry a keyed neutral anchor
                        // inside SoraMascotVideo. Keeping the preview still beneath
                        // the transparent footage produces a second silhouette when
                        // the authored poses differ. Retain the fallback only for
                        // legacy single-video characters which have no anchor.
                        if (visualVideoUrls.isEmpty()) {
                            AsyncImage(
                                model = resolveMediaUrl(visualPreviewUrl),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        SoraMascotVideo(
                            videoUrl = visualAnimationVideoUrl,
                            videoUrls = visualVideoUrls,
                            neutralFrameUrl = visualNeutralFrameUrl,
                            action = stateKey ?: "idle",
                            interaction = interaction,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            } else PngSequence(
                frames = if (accepted) visualFrames else emptyList(),
                fps = animationFps,
                decodeSize = 500,
                fallback = visualPreviewUrl,
                mascotIdHint = visualMascotId,
                puppetAction = stateKey ?: "content",
                interaction = interaction,
                contentDescription = "Герой",
                modifier = heroModifier,
            )
        }
        if (!accepted) {
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    detectTapGestures { offset -> onKnock.value(offset.x, offset.y) }
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
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = quoteBottom)
                    .size(width = contentWidth, height = 112.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.White.copy(alpha = .32f))
                    .border(2.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(32.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.quote_mark_vector),
                    contentDescription = null,
                    tint = palette.top,
                    modifier = Modifier.size(width = 22.dp, height = 13.dp),
                )
                Text(
                    currentLine,
                    color = palette.top.copy(alpha = .88f),
                    fontFamily = SbSansDisplayMedium,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }
            AnimatedVisibility(
                visible = heroPickerExpanded,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 94.dp)
                    .width(contentWidth)
                    .height(112.dp),
                enter = fadeIn(tween(150)) + slideInVertically(tween(220, easing = MascotEase)) { it / 3 },
                exit = fadeOut(tween(100)) + slideOutVertically(tween(160, easing = MascotEase)) { it / 4 },
            ) {
                val itemWidth = 76.dp
                val sidePadding = ((contentWidth - itemWidth) / 2).coerceAtLeast(0.dp)
                LazyRow(
                    state = heroStrip,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = sidePadding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = heroStrip),
                ) {
                    items(heroLibrary, key = { it.id }) { hero ->
                        val layoutInfo = heroStrip.layoutInfo
                        val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == hero.id }
                        val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                        val itemCenter = itemInfo?.let { it.offset + it.size / 2f } ?: viewportCenter
                        val halfViewport = ((layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2f)
                            .coerceAtLeast(1f)
                        val distance = ((itemCenter - viewportCenter) / halfViewport).coerceIn(-1f, 1f)
                        val focus = 1f - kotlin.math.abs(distance)
                        val depthScale by animateFloatAsState(
                            targetValue = .72f + focus * .28f,
                            animationSpec = tween(90, easing = MascotEase),
                            label = "hero carousel depth",
                        )
                        Box(
                            Modifier.width(itemWidth).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            HeroThumbnail(
                                hero,
                                Modifier.size(68.dp)
                                    .graphicsLayer {
                                        scaleX = depthScale
                                        scaleY = depthScale
                                        translationY = (1f - focus) * 15.dp.toPx()
                                        rotationY = -distance * 15f
                                        alpha = .44f + focus * .56f
                                    cameraDistance = 18f * density.density
                                    }
                                    .border(
                                        2.dp,
                                        if (hero.id == pickerSelectedId) Color.White.copy(alpha = .92f)
                                        else Color.Transparent,
                                        CircleShape,
                                    )
                                    .mascotClickable(onClickLabel = "Выбрать ${hero.name ?: "героя"}") {
                                        if (!heroStrip.isScrollInProgress) {
                                            pickerSelectedId = hero.id
                                            if (hero.id != mascotId) onSelectHero(hero.id)
                                            heroPickerExpanded = false
                                        }
                                    },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(visible = !heroPickerExpanded, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        Modifier.size(54.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = .30f))
                            .border(2.dp, Color.White.copy(alpha = .46f), CircleShape)
                            .mascotClickable(
                                enabled = navigationReady,
                                onClickLabel = "Эмоции героя",
                                onClick = onAnimations,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.SentimentSatisfiedAlt,
                            "Эмоции героя",
                            tint = palette.top.copy(alpha = .82f),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Box(
                    Modifier.size(62.dp).clip(CircleShape)
                        .background(
                            if (heroPickerExpanded) palette.top.copy(alpha = .82f)
                            else Color.White.copy(alpha = .30f),
                        )
                        .border(2.dp, Color.White.copy(alpha = .46f), CircleShape)
                        .mascotClickable(
                            enabled = navigationReady,
                            onClickLabel = if (heroPickerExpanded) "Выбрать героя" else "Мои герои",
                            onClick = {
                                if (heroPickerExpanded) {
                                    pickerSelectedId?.takeIf { it != mascotId }?.let(onSelectHero)
                                    heroPickerExpanded = false
                                } else {
                                    pickerSelectedId = mascotId
                                    heroPickerExpanded = true
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (heroPickerExpanded) Icons.Rounded.Check else Icons.Rounded.Groups,
                        contentDescription = if (heroPickerExpanded) "Выбрать героя" else "Открыть моих героев",
                        tint = if (heroPickerExpanded) Color.White else palette.top.copy(alpha = .82f),
                        modifier = Modifier.size(28.dp),
                    )
                }
                AnimatedVisibility(visible = !heroPickerExpanded, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        Modifier.size(54.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = .30f))
                            .border(2.dp, Color.White.copy(alpha = .46f), CircleShape)
                            .mascotClickable(
                                enabled = navigationReady,
                                onClickLabel = "Создать нового героя",
                                onClick = { confirmNewHero = true },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            "Создать нового героя",
                            tint = palette.top.copy(alpha = .82f),
                            modifier = Modifier.size(27.dp),
                        )
                    }
                }
            }
        }
        if (error != null) {
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
    }
    if (confirmNewHero) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmNewHero = false },
            title = { Text("Создать нового героя?") },
            text = {
                Text(
                    "Это запустит отдельную платную генерацию внешности. Видео-анимации создаются позже и только после отдельного подтверждения.",
                )
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmNewHero = false }) {
                    Text("Отмена")
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { confirmNewHero = false; onNewHero() },
                ) {
                    Text("Создать")
                }
            },
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
