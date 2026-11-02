package com.generativemascot.app.ui

import android.graphics.Paint
import android.graphics.BlurMaskFilter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.generativemascot.app.R
import kotlin.math.abs

private data class CharacterType(val title: String, val image: Int, val width: Float, val top: Float, val height: Float)
private val characterTypes = listOf(
    CharacterType("Человек", R.drawable.type_person_new, 264f, 221f, 371f),
    CharacterType("Существо", R.drawable.type_creature_new, 231f, 292f, 295f),
    CharacterType("Животное", R.drawable.type_animal_new, 380f, 206f, 410f),
)

@Composable
internal fun CharacterTypeScreen(onClose: () -> Unit, onFinish: (com.generativemascot.app.data.CharacterCreationChoice) -> Boolean,
    creationInProgress: Boolean = false) {
    var selectedStep by remember { mutableIntStateOf(0) }
    val selected = ((selectedStep % characterTypes.size) + characterTypes.size) % characterTypes.size
    val ring = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var showPersonality by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    fun settle(target: Int) {
        val start = ring.value + dragOffset
        dragOffset = 0f
        selectedStep = target
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ring.snapTo(start)
            ring.animateTo(target.toFloat(), tween(420, easing = FastOutSlowInEasing))
        }
    }
    val context = LocalContext.current
    val labelFont = remember { ResourcesCompat.getFont(context, R.font.sb_sans_display_extended_semibold) }
    BackHandler(enabled = !creationInProgress) { if (showPersonality) showPersonality = false else onClose() }
    MascotSystemBars(Color(0xfff2f2f2))
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xfff2f2f2)).clip(androidx.compose.ui.graphics.RectangleShape)) {
        val sx = maxWidth / 393f
        val sy = maxHeight / 852f
        val dragDistancePx = with(LocalDensity.current) { (maxWidth * .55f).toPx() }
        Image(painterResource(R.drawable.selection_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (showPersonality) {
            CharacterPersonalityScreen(
                type = listOf("person", "creature", "animal")[selected],
                preview = characterTypes[selected].image,
                previewAspect = characterTypes[selected].width / characterTypes[selected].height,
                onFinish = onFinish,
                onBack = { showPersonality = false },
                canNavigateBack = !creationInProgress,
            )
        } else {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sy * 175).background(Brush.verticalGradient(0f to Color.Transparent, .85f to Color(0xfff4f4f4))))
            val item = characterTypes[selected]
            val leftImage = when (selected) { 0 -> R.drawable.selection_side; 1 -> R.drawable.selection_person; else -> R.drawable.selection_creature_side }
            val rightImage = when (selected) { 0 -> R.drawable.selection_creature; 1 -> R.drawable.selection_side; else -> R.drawable.selection_person_side }
            Image(painterResource(leftImage), null, Modifier.offset(x = sx * -230, y = sy * 265).size(sx * 325, sy * 392).graphicsLayer { rotationZ = -30f }.blur(10.dp), contentScale = ContentScale.FillBounds)
            Image(painterResource(rightImage), null, Modifier.offset(x = sx * 300, y = sy * 265).size(sx * 325, sy * 392).graphicsLayer { rotationZ = 30f }.blur(10.dp), contentScale = ContentScale.FillBounds)
            AnimatedContent(targetState = selected, modifier = Modifier.fillMaxSize(), transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(180)) }, label = "character type") { index ->
                val type = characterTypes[index]
                Box(Modifier.fillMaxSize()) {
                    if (index == 1) Image(painterResource(R.drawable.type_creature_glow), null,
                        Modifier.offset(x = sx * 78, y = sy * 259).size(sx * 231, sy * 346).blur(10.dp),
                        contentScale = ContentScale.FillBounds)
                    Image(painterResource(type.image), type.title, Modifier.offset(x = sx * ((393f - type.width) / 2f), y = sy * type.top).size(sx * type.width, sy * type.height), contentScale = ContentScale.FillBounds)
                }
            }
            Box(Modifier.fillMaxSize().pointerInput(dragDistancePx) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        drag = 0f
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { ring.stop() }
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        drag += dx
                        dragOffset = (-drag / dragDistancePx).coerceIn(-1f, 1f)
                    },
                    onDragEnd = {
                        val nearest = ring.value.roundToInt()
                        val target = if (abs(dragOffset) > .18f) nearest + if (dragOffset > 0f) 1 else -1 else nearest
                        settle(target)
                    },
                    onDragCancel = { settle(selectedStep) },
                )
            })
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Выбран тип: ${item.title}. Смахните для выбора." }) {
                drawIntoCanvas { canvas ->
                    val scale = size.width / 393f
                    val cx = size.width / 2f
                    val cy = size.height * (768f / 852f)
                    val radius = 112.5f * scale
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.BLACK
                        textSize = 24f * scale
                        typeface = labelFont
                        textAlign = Paint.Align.CENTER
                    }
                    fun drawCurved(label: String, middleAngle: Float) {
                        val widths = label.map { paint.measureText(it.toString()) }
                        var distance = -widths.sum() / 2f
                        label.forEachIndexed { index, letter ->
                            val width = widths[index]
                            val angle = middleAngle + (distance + width / 2f) / radius
                            val x = cx + kotlin.math.sin(angle) * radius
                            val y = cy - kotlin.math.cos(angle) * radius
                            canvas.nativeCanvas.save()
                            canvas.nativeCanvas.rotate(Math.toDegrees(angle.toDouble()).toFloat(), x, y)
                            canvas.nativeCanvas.drawText(letter.toString(), x, y, paint)
                            canvas.nativeCanvas.restore()
                            distance += width
                        }
                    }
                    val position = ring.value + dragOffset
                    val half = characterTypes.size / 2f
                    characterTypes.forEachIndexed { index, type ->
                        val relative = ((index - position + half) % characterTypes.size + characterTypes.size) % characterTypes.size - half
                        val distance = abs(relative)
                        val visibility = ((1.5f - distance) / .25f).coerceIn(0f, 1f)
                        paint.alpha = (255f * visibility).toInt()
                        paint.maskFilter = if (distance > .08f) BlurMaskFilter(2.5f * scale * distance.coerceAtMost(1f), BlurMaskFilter.Blur.NORMAL) else null
                        drawCurved(type.title, relative * kotlin.math.PI.toFloat() / 2f)
                    }
                }
            }
            Box(Modifier.offset(x = sx * 45, y = sy * 698).size(sx * 72, sy * 112)
                .mascotClickable(onClickLabel = "Предыдущий тип", onClick = { settle(selectedStep - 1) }))
            Box(Modifier.offset(x = sx * 276, y = sy * 698).size(sx * 72, sy * 112)
                .mascotClickable(onClickLabel = "Следующий тип", onClick = { settle(selectedStep + 1) }))
            MascotSmallCircleAction(
                icon = Icons.Rounded.Check,
                label = "Выбрать ${item.title}",
                onClick = { showPersonality = true },
                enabled = !creationInProgress,
                background = Color(0xff222222),
                tint = Color.White,
                modifier = Modifier.offset(x = (maxWidth - MascotSmallActionSize) / 2, y = sy * 745),
            )
            if (!creationInProgress) Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 4.dp)) {
                MascotTopAction(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onClose)
            }
        }
    }
}
