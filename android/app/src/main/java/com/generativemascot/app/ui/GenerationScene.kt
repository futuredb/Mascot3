package com.generativemascot.app.ui

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.generativemascot.app.R
import com.generativemascot.app.BuildConfig
import coil.compose.AsyncImage
import com.generativemascot.app.data.resolveMediaUrl
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

private val KnockBg = Color(0xFFE8F4FC)
private val KnockInk = Color(0xFF5B160F)
private const val RippleWaveDurationMs = 1_220f
private const val RippleWaveDelayMs = 170f
private const val RippleWaveCount = 2
private const val RippleMs = 1_560f
internal val GenerationRubikOne = FontFamily(Font(R.font.rubik_one))
private val GenerationSbSansDisplayMedium = FontFamily(Font(R.font.sb_sans_display_medium, FontWeight.Medium))

private data class GenerationRipple(
    val x: Float,
    val y: Float,
    val startedAt: Long,
)

@Composable
fun GenerationScene(
    generating: Boolean,
    previewUrl: String?,
    animationFrames: List<String> = emptyList(),
    animationFps: Int = 6,
    error: String?,
    heroName: String? = null,
    onKnockComplete: () -> Unit,
    onAccept: () -> Unit,
    onGenerate: () -> Unit = {},
    onPoll: () -> Unit,
    acceptEnabled: Boolean,
    greetingStage: Boolean = false,
    generationLabel: String? = null,
    onDeferGreeting: () -> Unit = {},
    onGenerationStartedSound: (() -> Unit)? = null,
) {
    MascotSystemBars(KnockBg, KnockBg)
    val context = LocalContext.current
    val playGenerationSound = onGenerationStartedSound ?: rememberGenerationStartSound()
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
    var knockSubmitted by remember { mutableStateOf(false) }
    var revealAt by remember { mutableLongStateOf(if (generating) 1L else 0L) }
    var blinkAt by remember { mutableLongStateOf(-1L) }
    val ripples = remember { mutableStateListOf<GenerationRipple>() }
    val rippleBlurLayer = rememberGraphicsLayer()
    // Acknowledge the third tap locally, without waiting for an asynchronous parent update.
    val showWaiting = (generating || knockSubmitted) && (greetingStage || previewUrl.isNullOrBlank())
    val animatedWaitingTransition by animateFloatAsState(
        targetValue = if (showWaiting) 1f else 0f,
        animationSpec = if (showWaiting) snap() else tween(durationMillis = 700),
        label = "waiting background fade",
    )
    // Even the first recomposition must show the waiting scene at full opacity.
    val waitingTransition = if (showWaiting) 1f else animatedWaitingTransition
    val heroReady = !previewUrl.isNullOrBlank() && !showWaiting

    LaunchedEffect(generating, previewUrl, error) {
        if (generating || !previewUrl.isNullOrBlank() || error != null) {
            knockSubmitted = false
            tapCount = 0
        }
    }

    LaunchedEffect(heroReady) {
        if (heroReady) tapCount = 0
    }

    LaunchedEffect(generating) {
        if (generating) {
            revealAt = if (now > 0L) now else 1L
        }
    }
    LaunchedEffect(generating) {
        if (!generating) return@LaunchedEffect
        while (true) {
            delay(2_000)
            onPoll()
        }
    }

    LaunchedEffect(showWaiting, ripples.isNotEmpty()) {
        if (!showWaiting && ripples.isEmpty()) return@LaunchedEffect
        while (true) {
            now = withInfiniteAnimationFrameMillis { it }
            if (ripples.any { now - it.startedAt > RippleMs }) {
                ripples.removeAll { now - it.startedAt > RippleMs }
            }
            if (blinkAt >= 0L && now - blinkAt > 220L) blinkAt = -1L
        }
    }

    fun buzz(ms: Long, amp: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(ms, amp.coerceIn(1, 255)))
    }

    fun knock(x: Float, y: Float) {
        val t = System.nanoTime() / 1_000_000L
        if (showWaiting) {
            ripples += GenerationRipple(x, y, t)
            blinkAt = t + 80
            buzz(18, 70)
            return
        }
        ripples += GenerationRipple(x, y, t)
        buzz(20, 90)
        // A preview tap must never buy another character; only the explicit button can.
        if (heroReady || greetingStage || generating) return
        val next = tapCount + 1
        tapCount = next
        if (next >= 3) {
            knockSubmitted = true
            revealAt = t
            onKnockComplete()
            playGenerationSound()
        }
    }
    val onKnock = rememberUpdatedState { x: Float, y: Float -> knock(x, y) }

    Box(
        Modifier
            .fillMaxSize()
            .background(KnockBg),
    ) {
        Box(
            Modifier.fillMaxSize(),
        ) {

            Box(Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(R.drawable.generation_mascot_bg),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Image(
                    painter = painterResource(R.drawable.generation_waiting_bg),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(waitingTransition),
                )
            }

        if (!heroReady && !showWaiting) {
            Text(
                "ПОСТУЧИ",
                color = Color.Black.copy(alpha = 1f - waitingTransition),
                fontSize = 37.4.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = GenerationRubikOne,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 204.dp, start = 24.dp, end = 24.dp),
            )
        }
        if (waitingTransition > 0.001f) {
            Text(
                "ТАМ\nКТО-ТО\nЕСТЬ",
                color = Color.Black.copy(alpha = waitingTransition),
                fontSize = 37.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = GenerationRubikOne,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 150.dp, start = 24.dp, end = 24.dp),
            )
        }
        if (heroReady) {
            Text(
                (heroName ?: "ГЕРОЙ").uppercase(),
                color = Color.White.copy(alpha = 0.5f * (1f - waitingTransition)),
                fontSize = 58.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = GenerationRubikOne,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 82.dp, start = 24.dp, end = 24.dp),
            )
        }

        Canvas(Modifier.fillMaxSize()) {
            val t = now
            if (!heroReady && !showWaiting && waitingTransition < 1f) {
                val marksY = size.height * if (heroReady) 0.78f else 0.317f
                val gap = 27.dp.toPx()
                val dotRadius = 7.dp.toPx()
                val startX = size.width / 2f - gap
                repeat(3) { i ->
                    val baseAlpha = if (i <= tapCount) 1f else 0.20f
                    drawCircle(
                        color = Color.Black.copy(alpha = baseAlpha * (1f - waitingTransition)),
                        radius = dotRadius,
                        center = Offset(startX + i * gap, marksY),
                    )
                }
            }
            val rippleSnapshot = ripples.toList()
            val span = min(size.width, size.height)
            val maximumRadius = min(145.dp.toPx(), span * 0.37f)
            val minimumRadius = 16.dp.toPx()
            val waveStroke = 10.dp.toPx()

            fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRippleWaves(
                alphaScale: Float,
                strokeWidth: Float,
            ) {
                rippleSnapshot.forEach { ripple ->
                    val age = t - ripple.startedAt
                    repeat(RippleWaveCount) { waveIndex ->
                        val waveAge = age - waveIndex * RippleWaveDelayMs
                        if (waveAge < 0f || waveAge > RippleWaveDurationMs) return@repeat
                        val progress = (waveAge / RippleWaveDurationMs).coerceIn(0f, 1f)
                        val fade = 1f - progress
                        val easedProgress = 1f - fade * fade
                        val radius = minimumRadius + maximumRadius * easedProgress
                        // A younger wave is closer to the touch centre and brighter.
                        // Keep the outer waves clearly visible, then dissolve them
                        // only near the end of their travel.
                        val distanceAlpha = 1f - progress * 0.38f
                        val endFade = (fade / 0.18f).coerceIn(0f, 1f)
                        val alpha = distanceAlpha * endFade * alphaScale
                        drawCircle(
                            color = Color.White.copy(alpha = alpha.coerceIn(0f, 1f)),
                            radius = radius,
                            center = Offset(ripple.x, ripple.y),
                            style = Stroke(width = strokeWidth),
                        )
                    }
                }
            }

            if (rippleSnapshot.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    rippleBlurLayer.record(
                        size = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    ) {
                        drawRippleWaves(alphaScale = 1f, strokeWidth = waveStroke)
                    }
                    val rippleBlur = 56.dp.toPx()
                    rippleBlurLayer.renderEffect = BlurEffect(rippleBlur, rippleBlur, TileMode.Clamp)
                    drawLayer(rippleBlurLayer)
                    // Keep a very faint core so the ring remains readable over the
                    // pale background while still looking soft rather than outlined.
                    drawRippleWaves(alphaScale = 0.48f, strokeWidth = waveStroke)
                } else {
                    // Soft layered fallback for devices without RenderEffect.
                    drawRippleWaves(alphaScale = 0.13f, strokeWidth = 66.dp.toPx())
                    drawRippleWaves(alphaScale = 0.22f, strokeWidth = 34.dp.toPx())
                    drawRippleWaves(alphaScale = 0.60f, strokeWidth = waveStroke)
                }
            }
            if (waitingTransition > 0.001f) {
                val cx = size.width / 2f
                val cy = size.height * 0.57f
                val bob = (sin((t - revealAt).coerceAtLeast(0L) / 650.0) * 1.5).toFloat()
                val blink = blinkAmount(t, blinkAt, revealAt)
                val eyeWidth = 26.dp.toPx()
                val eyeHeight = 38.dp.toPx()
                val eyeDx = 28.dp.toPx()
                val lid = max(0.12f, 1f - blink)
                val eyeCenters = listOf(
                    Offset(cx - eyeDx, cy + bob + 3.dp.toPx()),
                    Offset(cx + eyeDx, cy + bob - 3.dp.toPx()),
                )
                val highlightWidth = 7.dp.toPx()
                val highlightHeight = 10.dp.toPx()
                eyeCenters.forEach { center ->
                    scale(1f, lid, center) {
                        drawOval(
                            color = Color.Black.copy(alpha = waitingTransition),
                            topLeft = Offset(
                                center.x - eyeWidth / 2f,
                                center.y - eyeHeight / 2f,
                            ),
                            size = Size(eyeWidth, eyeHeight),
                        )
                        drawOval(
                            color = Color.White.copy(alpha = 0.94f * waitingTransition),
                            topLeft = Offset(
                                center.x - eyeWidth * 0.22f,
                                center.y - eyeHeight * 0.34f,
                            ),
                            size = Size(highlightWidth, highlightHeight),
                        )
                    }
                }
            }
        }
        if (heroReady) {
            val heroModifier = Modifier.align(Alignment.Center).size(430.dp)
                .alpha(1f - waitingTransition).clip(RoundedCornerShape(18.dp))
            if (animationFrames.isEmpty()) {
                // An unaccepted design is a still, not the home-screen breathing renderer.
                AsyncImage(model = resolveMediaUrl(previewUrl), contentDescription = "Герой",
                    contentScale = ContentScale.Fit, modifier = heroModifier)
            } else {
            PngSequence(
                frames = animationFrames,
                fps = animationFps,
                decodeSize = 500,
                fallback = previewUrl,
                contentDescription = "Герой",
                modifier = heroModifier,
            )
            }
        }
        }

        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset -> onKnock.value(offset.x, offset.y) }
                },
        )
        if (heroReady || (greetingStage && !generating)) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .padding(bottom = 57.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {

                    IconButton(
                        onClick = { onGenerate(); playGenerationSound() },
                        enabled = acceptEnabled && !greetingStage,
                        modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f)),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF222222)),
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
                            .background(if (acceptEnabled) Color(0xFF222222) else Color(0x66222222))
                            .mascotClickable(enabled = acceptEnabled, onClick = onAccept),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.keep_hero_check_icon),
                            contentDescription = "Это мой герой",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            if (greetingStage) "ПРОДОЛЖИТЬ" else "ОСТАВИМ",
                            color = Color.White.copy(alpha = if (acceptEnabled) 1f else 0.70f),
                            fontSize = 13.4.sp,
                            fontWeight = FontWeight.Normal,
                            fontFamily = GenerationRubikOne,
                        )
                    }

            }
        }



        if (!heroReady && !showWaiting) {
            Text(
                if (error != null) "не удалось" else "чтобы сделать персонажа",
                color = if (error != null) {
                    Color(0xFFE94718).copy(alpha = 0.72f)
                } else {
                    Color.Black.copy(alpha = 0.20f)
                },
                fontSize = 12.sp,
                lineHeight = 15.sp,
                letterSpacing = (-0.276).sp,
                fontWeight = FontWeight.Medium,
                fontFamily = GenerationSbSansDisplayMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 772.dp),
            )
        }
        if (showWaiting || error != null) {
            Text(
                text = error ?: generationLabel ?: "Создаём вашего героя…",
                color = if (error != null) Color(0xFFB33C30) else Color(0xFF555B64),
                fontFamily = GenerationSbSansDisplayMedium,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 28.dp, end = 28.dp, bottom = 130.dp),
            )
        }
        if (heroReady && !greetingStage && error == null) {
            Text(
                "«Оставим» создаст 1 анимацию-приветствие",
                color = Color(0xFF555B64), fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp, start = 24.dp, end = 24.dp),
            )
        }
        if (greetingStage && error != null && !generating) {
            TextButton(onClick = onDeferGreeting,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 185.dp)) {
                Text("Позже", color = KnockInk)
            }
        }
        Text(
            "Версия ${BuildConfig.VERSION_NAME}",
            color = Color(0xFF9AA0A6),
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(2f)
                .padding(bottom = 10.dp),
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
