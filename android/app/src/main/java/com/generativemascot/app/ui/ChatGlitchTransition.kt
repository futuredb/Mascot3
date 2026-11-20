package com.generativemascot.app.ui

import com.generativemascot.app.data.CharacterGlitchPalette
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin

// Approved variants 01, 03, 07, 08, 09, 10, in the same order as chat-glitch.js.
internal enum class ChatGlitchEffect { Slices, Pixels, Packets, Dropout, Columns, Echo }

internal class ChatGlitchState(initialHelios: Boolean) {
    var displayedHelios by mutableStateOf(initialHelios)
    var effect by mutableStateOf(ChatGlitchEffect.Slices)
    val progress = Animatable(1f)
    val alpha: Float get() = 1f - chatGlitchOcclusion(progress.value)
    var warm by mutableStateOf(Color(0xFFFFAD7A))
    var cool by mutableStateOf(Color(0xFFAAA0FF))
}

internal fun chatGlitchOcclusion(progress: Float): Float {
    fun smooth(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
    return smooth((progress - .29f) / .15f) * smooth((.71f - progress) / .15f)
}

@Composable
internal fun rememberChatGlitchTransition(
    key: String,
    targetHelios: Boolean,
    visible: Boolean,
    palette: CharacterGlitchPalette,
): ChatGlitchState {
    val state = remember(key) {
        ChatGlitchState(false).apply {
            warm = Color(android.graphics.Color.parseColor(palette.warmHex))
            cool = Color(android.graphics.Color.parseColor(palette.coolHex))
        }
    }
    var next by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, targetHelios, visible) {
        if (!visible || state.displayedHelios == targetHelios) {
            state.displayedHelios = targetHelios
            state.progress.snapTo(1f)
        } else {
            state.effect = ChatGlitchEffect.entries[next++ % ChatGlitchEffect.entries.size]
            state.progress.snapTo(0f)
            state.progress.animateTo(.5f, tween(210, easing = LinearEasing))
            // Replace sources only while the figure is fully dissolved.
            state.displayedHelios = targetHelios
            state.progress.animateTo(1f, tween(210, easing = LinearEasing))
        }
    }
    return state
}

@Composable
internal fun ChatGlitchOverlay(state: ChatGlitchState, modifier: Modifier = Modifier, loading: Boolean = false) {
    val loadingProgress by rememberInfiniteTransition(label = "helios-loading-glitch").animateFloat(
        initialValue = .18f,
        targetValue = .82f,
        animationSpec = infiniteRepeatable(tween(920, easing = LinearEasing), RepeatMode.Reverse),
        label = "helios-loading-glitch-progress",
    )
    Canvas(modifier) {
        val transitioning = state.progress.value > 0f && state.progress.value < 1f
        if (!transitioning && !loading) return@Canvas
        val t = if (transitioning) state.progress.value else loadingProgress
        val wave = sin(PI * t).toFloat()
        val cover = chatGlitchOcclusion(t)
        val warm = state.warm
        val cool = state.cool
        val unit = size.width / 256f
        val tick = (t * 8).toInt()
        fun noise(seed: Int): Float {
            val n = sin(seed * 127.1 + 311.7) * 43758.5453
            return (n - kotlin.math.floor(n)).toFloat()
        }
        fun rect(color: Color, alpha: Float, x: Float, y: Float, w: Float, h: Float) {
            drawRect(color.copy(alpha = alpha.coerceIn(0f, 1f)), Offset(x * unit, y * unit), Size(w * unit, h * unit))
        }
        when (state.effect) {
            ChatGlitchEffect.Slices -> for (y in 0..255 step 14) {
                val color = if (y % 28 == 0) warm else cool
                rect(color, cover * .2f, 0f, y.toFloat(), 256f, 14f)
                rect(color, wave * .48f, 12 + noise(y + tick) * 140, y.toFloat(), 25 + noise(y) * 50, 2f)
            }
            ChatGlitchEffect.Pixels -> for (y in 0..255 step 20) for (x in 0..255 step 20) {
                val n = noise(x + y * 2 + tick * 11)
                if (n < .48f * wave) rect(if (n < .24f) warm else cool, wave * .38f, x.toFloat(), y.toFloat(), 18f, 18f)
            }
            ChatGlitchEffect.Packets -> for (y in 0..255 step 32) for (x in 0..255 step 32) {
                val n = noise(x * 3 + y + tick * 13)
                rect(if (n > .5f) warm else cool, wave * (.08f + n * .3f), x + 2f, y + 2f, 28f, 28f)
                if (n > .58f) rect(if (n > .5f) cool else warm, wave * .35f, x + 6f, y + 7f, 13f, 2f)
            }
            ChatGlitchEffect.Dropout -> {
                for (y in 0..255 step 4) for (x in 0..255 step 4) {
                    val n = noise((x + y * 256) * 4 + tick * 132)
                    if (n >= .35f) rect(if (n > .5f) warm else cool, wave * .28f, x.toFloat(), y.toFloat(), 4f, 4f)
                }
                for (y in 0..255 step 4) rect(cool, wave * .18f, 0f, y.toFloat(), 256f, 1f)
                rect(warm, wave * .6f, 40 + noise(tick + 5) * 80, 76 + noise(tick) * 90, 74f, 3f)
            }
            ChatGlitchEffect.Columns -> for (x in 0..255 step 18) {
                val alternate = (x / 18) % 2 == 1
                rect(if (alternate) warm else cool, cover * .22f, x.toFloat(), 0f, 18f, 256f)
                rect(if (alternate) cool else warm, wave * .5f, x.toFloat(), 32 + noise(x + tick) * 158, 1f, 34f)
            }
            ChatGlitchEffect.Echo -> for (i in 0..2) {
                val distance = (i + 1) * 7 * wave * .7375f
                drawRect((if (i % 2 == 1) warm else cool).copy(alpha = wave * .5f),
                    Offset((66 - distance) * unit, (30 - distance) * unit),
                    Size((124 + distance * 2) * unit, (196 + distance * 2) * unit), style = Stroke(unit))
            }
        }
    }
}
