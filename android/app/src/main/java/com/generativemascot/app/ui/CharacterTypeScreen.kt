package com.generativemascot.app.ui

import android.graphics.Paint
import android.graphics.BlurMaskFilter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import android.view.LayoutInflater
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil.compose.AsyncImage
import kotlin.math.roundToInt
import com.generativemascot.app.R
import kotlin.math.abs

private data class CharacterType(val title: String, val image: Int, val video: Int, val width: Float, val top: Float, val height: Float)
private val characterTypes = listOf(
    CharacterType("Человек", R.drawable.type_person_figma, R.raw.character_person_figma, 463f, 195f, 408f),
    CharacterType("Существо", R.drawable.type_creature_figma, R.raw.character_creature_figma, 463f, 195f, 408f),
    CharacterType("Животное", R.drawable.type_animal_figma, R.raw.character_animal_figma, 463f, 195f, 408f),
)

@Composable
internal fun CharacterTypeScreen(onClose: () -> Unit, onFinish: (com.generativemascot.app.data.CharacterCreationChoice) -> Boolean,
    creationInProgress: Boolean = false, onCreationFailure: () -> String = { "Не удалось начать создание персонажа. Попробуй ещё раз." }) {
    var selectedStep by remember { mutableIntStateOf(0) }
    val selected = ((selectedStep % characterTypes.size) + characterTypes.size) % characterTypes.size
    val ring = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var showPersonality by remember { mutableStateOf(false) }
    val personalityProgress by animateFloatAsState(if (showPersonality) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "character transition")
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
    var referencePath by rememberSaveable { mutableStateOf<String?>(null) }
    var referenceLoading by remember { mutableStateOf(false) }
    var referenceError by remember { mutableStateOf<String?>(null) }
    var showReferenceNotice by remember { mutableStateOf(false) }
    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            referenceLoading = true
            scope.launch {
                try { referencePath = withContext(Dispatchers.IO) { saveChatImage(context, uri) }; referenceError = null }
                catch (_: Exception) { referenceError = "Не удалось открыть изображение" }
                finally { referenceLoading = false }
            }
        }
    }
    if (showReferenceNotice) AlertDialog(
        onDismissRequest = { showReferenceNotice = false },
        containerColor = Color(0xfff2f2f2), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = { Text("Фото для персонажа", fontFamily = SbSansDisplayMedium, color = FigmaInk) },
        text = { Text("Если загружаешь фотографию человека, используй только своё фото. Изображение будет передано сервису генерации. Видеомодель может отклонить даже собственное фото, если распознает реального человека.", fontFamily = SbSansTextRegular, color = FigmaInk) },
        confirmButton = { TextButton(onClick = { showReferenceNotice = false; referencePicker.launch("image/*") }) { Text("Выбрать изображение", color = FigmaInk) } },
        dismissButton = { TextButton(onClick = { showReferenceNotice = false }) { Text("Отмена", color = FigmaInk.copy(alpha = .65f)) } },
    )
    val labelFont = remember { ResourcesCompat.getFont(context, R.font.sb_sans_display_extended_semibold) }
    BackHandler(enabled = !creationInProgress) { if (showPersonality) showPersonality = false else onClose() }
    MascotSystemBars(Color(0xfff2f2f2))
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xfff2f2f2)).clip(androidx.compose.ui.graphics.RectangleShape)) {
        val sx = maxWidth / 393f
        val sy = maxHeight / 852f
        val dragDistancePx = with(LocalDensity.current) { (maxWidth * .55f).toPx() }
        AnimatedVisibility(visible = showPersonality, enter = fadeIn(tween(350)), exit = fadeOut(tween(150))) {
            CharacterPersonalityScreen(
                type = listOf("person", "creature", "animal")[selected],
                preview = characterTypes[selected].image,
                previewAspect = characterTypes[selected].width / characterTypes[selected].height,
                onFinish = { choice -> if (referenceLoading) false else onFinish(choice.copy(referencePath = referencePath)) },
                onFailure = { if (referenceLoading) "Подожди, изображение ещё загружается." else onCreationFailure() },
                onBack = { showPersonality = false },
                onClose = onClose,
                canNavigateBack = !creationInProgress,
                showPreview = false,
            )
        }
        if (!showPersonality) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sy * 175).background(Brush.verticalGradient(0f to Color.Transparent, .85f to Color(0xfff4f4f4))))
            val item = characterTypes[selected]
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
            if (!creationInProgress) Box(Modifier.offset(x = sx * 337, y = sy * 72).size(30.dp)
                .mascotClickable(onClickLabel = "Закрыть", onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.close_gallery_icon), contentDescription = "Закрыть", tint = Color.Unspecified, modifier = Modifier.size(30.dp))
            }
        }
        // One continuous carousel position drives both the curved labels and
        // characters, including the part of the swipe before it settles.
        val position = ring.value + dragOffset
        val half = characterTypes.size / 2f
        characterTypes.forEachIndexed { index, type ->
            val relative = ((index - position + half) % characterTypes.size + characterTypes.size) % characterTypes.size - half
            val distance = abs(relative).coerceAtMost(1f)
            val destinationWidth = 284f * type.width / type.height
            val width = type.width + (destinationWidth - type.width) * personalityProgress
            val height = type.height + (284f - type.height) * personalityProgress
            val top = type.top + (147f - type.top) * personalityProgress
            val horizontal = relative * 340f * (1f - personalityProgress)
            val alpha = (1f - .6f * distance) * (1f - personalityProgress * distance)
            if (index == selected) {
                key(type.video) {
                    FigmaCharacterVideo(
                        video = type.video, enabled = rememberAppResumed(),
                        modifier = Modifier.offset(x = sx * ((393f - width) / 2f + horizontal), y = sy * top)
                            .size(sx * width, sy * height)
                            .graphicsLayer { this.alpha = alpha; rotationZ = relative * 12f * (1f - personalityProgress) }
                            .blur(8.dp * distance * (1f - personalityProgress)),
                    )
                }
            }
        }
        if (!creationInProgress && !showPersonality) {
            Row(Modifier.offset(x = sx * 26, y = sy * 75),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.mascotClickable(onClickLabel = if (referencePath == null) "Загрузить референс" else "Заменить референс", onClick = { showReferenceNotice = true }), verticalAlignment = Alignment.CenterVertically) {
                    if (referencePath == null) Icon(painterResource(R.drawable.creation_attachment), contentDescription = null, tint = Color(0xFF292929).copy(alpha = .4f), modifier = Modifier.size(24.dp))
                    else AsyncImage(model = java.io.File(referencePath!!), contentDescription = "Референс", modifier = Modifier.size(24.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(8.dp))
                    Text(if (referenceLoading) "Загружаем…" else if (referencePath == null) "Загрузить реф" else "Заменить реф", fontFamily = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.sb_sans_display_regular)), fontSize = 14.sp, color = Color.Black.copy(alpha = .5f))
                }
                if (referencePath != null) Box(Modifier.size(30.dp).mascotClickable(onClickLabel = "Удалить референс", onClick = { referencePath = null }), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.close_gallery_icon), contentDescription = "Удалить референс", tint = Color.Black.copy(alpha = .5f), modifier = Modifier.size(20.dp))
                }
            }
            referenceError?.let { Text(it, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 52.dp), color = Color(0xff9e3030), fontSize = 12.sp) }
        }
    }
}


/** A single muted decoder follows the selected character and the app lifecycle. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun FigmaCharacterVideo(video: Int, enabled: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val player = createMascotVideoPlayer(context).apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
                repeatMode = Player.REPEAT_MODE_ONE
            }
            (LayoutInflater.from(context).inflate(R.layout.mascot_video_player, null, false) as PlayerView).apply {
                this.player = player
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                (videoSurfaceView as? TextureView)?.isOpaque = false
                applyBackgroundKey(this, android.graphics.Color.rgb(231, 235, 233), forceVideoEffect = true)
                player.setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://${context.packageName}/$video")))
                player.prepare()
                player.playWhenReady = enabled
            }
        },
        update = { view -> view.player?.playWhenReady = enabled },
        onRelease = { view ->
            val player = view.player as? androidx.media3.exoplayer.ExoPlayer
            view.player = null
            if (player != null) releaseMascotVideoPlayer(player)
        },
    )
}
