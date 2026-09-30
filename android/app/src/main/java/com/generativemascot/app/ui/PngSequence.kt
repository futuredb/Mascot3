package com.generativemascot.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.generativemascot.app.data.resolveMediaUrl
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.animationFramesWithoutZero
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun PngSequence(
    frames: List<String>,
    fps: Int,
    decodeSize: Int,
    fallback: String? = null,
    mascotIdHint: String? = null,
    puppetAction: String = "content",
    interaction: PuppetInteraction = PuppetInteraction(),
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val resolved = remember(frames) {
        animationFramesWithoutZero(frames) { it }.mapNotNull(::resolveMediaUrl)
    }
    val mascotId = remember(resolved, fallback, mascotIdHint) {
        mascotIdHint ?: extractMascotId(resolved.firstOrNull()) ?: extractMascotId(fallback)
    }
    val bitmaps by produceState(initialValue = emptyList<Bitmap>(), resolved, decodeSize) {
        value = withContext(Dispatchers.IO) {
            resolved.mapNotNull { url -> decodeLocalFrame(url, decodeSize) }
        }
    }
    val resolvedFallback = remember(fallback) { resolveMediaUrl(fallback) }
    val fallbackBitmap by produceState<Bitmap?>(
        initialValue = null,
        resolvedFallback,
        decodeSize,
    ) {
        value = withContext(Dispatchers.IO) {
            resolvedFallback?.let { decodeLocalFrame(it, decodeSize) }
        }
    }
    val reactionBitmaps by produceState(initialValue = emptyMap<String, Bitmap>(), mascotId, decodeSize) {
        value = withContext(Dispatchers.IO) {
            if (mascotId == null) return@withContext emptyMap()
            listOf("joyful", "content", "surprised", "sleeping", "thinking", "bored", "acquaintance")
                .mapNotNull { state ->
                    HeroLocalStore.current?.stateFile(mascotId, state)?.let { file ->
                        decodeFile(file.absolutePath, decodeSize)?.let { state to it }
                    }
                }
                .toMap()
        }
    }
    var frameTime by remember(resolved) { mutableLongStateOf(0L) }
    var genericReaction by remember(mascotId) { mutableStateOf<String?>(null) }
    var genericReactionStartedAt by remember(mascotId) { mutableLongStateOf(0L) }
    LaunchedEffect(bitmaps) {
        while (isActive && bitmaps.size > 1) {
            withFrameNanos { frameTime = it }
        }
    }
    LaunchedEffect(interaction.tapSerial) {
        if (interaction.tapSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        genericReaction = "sad"; genericReactionStartedAt = startedAt
        delay(3_900); if (genericReactionStartedAt == startedAt) genericReaction = null
    }
    LaunchedEffect(interaction.petSerial) {
        if (interaction.petSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        genericReaction = "idle"; genericReactionStartedAt = startedAt
        delay(3_900); if (genericReactionStartedAt == startedAt) genericReaction = null
    }
    LaunchedEffect(interaction.playSerial) {
        if (interaction.playSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        genericReaction = "eating"; genericReactionStartedAt = startedAt
        delay(3_900); if (genericReactionStartedAt == startedAt) genericReaction = null
    }
    LaunchedEffect(interaction.spinSerial) {
        if (interaction.spinSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        genericReaction = "dancing"; genericReactionStartedAt = startedAt
        delay(3_900); if (genericReactionStartedAt == startedAt) genericReaction = null
    }
    LaunchedEffect(interaction.danceSerial) {
        if (interaction.danceSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        genericReaction = "dancing"; genericReactionStartedAt = startedAt
        delay(3_900); if (genericReactionStartedAt == startedAt) genericReaction = null
    }
    LaunchedEffect(interaction.sleepSerial) {
        if (interaction.sleepSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        genericReaction = "sleeping"; genericReactionStartedAt = startedAt
        delay(3_900); if (genericReactionStartedAt == startedAt) genericReaction = null
    }
    LaunchedEffect(interaction.actionSerial) {
        if (interaction.actionSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        genericReaction = interaction.actionName
        genericReactionStartedAt = startedAt
        delay(3_900)
        if (genericReactionStartedAt == startedAt) genericReaction = null
    }
    val activeAction = remember(genericReaction, puppetAction) {
        HeroLocalStore.normalizeFullFrameAction(genericReaction ?: puppetAction)
    }
    val defaultAction = remember(puppetAction) { HeroLocalStore.normalizeFullFrameAction(puppetAction) }
    val needsLocalSequence = bitmaps.isEmpty() || (genericReaction != null && activeAction != defaultAction)
    val fullFrameBitmaps by produceState(
        initialValue = emptyList<Bitmap>(),
        mascotId,
        activeAction,
        needsLocalSequence,
        decodeSize,
    ) {
        value = withContext(Dispatchers.IO) {
            if (mascotId == null || !needsLocalSequence) return@withContext emptyList()
            animationFramesWithoutZero(HeroLocalStore.current?.sequenceFiles(mascotId, activeAction).orEmpty()) { it.name }
                .mapNotNull { file -> decodeFile(file.absolutePath, decodeSize) }
        }
    }
    LaunchedEffect(fullFrameBitmaps) {
        while (isActive && fullFrameBitmaps.size > 1) withFrameNanos { frameTime = it }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val activeReaction = genericReaction ?: puppetAction
        val bitmap = when (genericReaction) {
            "content" -> reactionBitmaps["content"] ?: reactionBitmaps["joyful"]
            "joyful" -> reactionBitmaps["joyful"] ?: reactionBitmaps["acquaintance"]
            "surprised" -> reactionBitmaps["surprised"] ?: reactionBitmaps["thinking"]
            "sleeping" -> reactionBitmaps["sleeping"] ?: reactionBitmaps["bored"]
            else -> null
        } ?: reactionBitmaps[puppetAction] ?: bitmaps.firstOrNull() ?: fallbackBitmap
        val playback = fullFrameBitmaps.ifEmpty { bitmaps }
        if (playback.size > 1) {
            WholeCharacterFrames(
                frames = playback,
                fps = fps.coerceIn(1, 60),
                frameTimeNanos = { frameTime },
                startedAtNanos = genericReactionStartedAt.takeIf { genericReaction != null } ?: 0L,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (hasRiggedPuppet(mascotId)) {
            RiggedPuppet(
                mascotId = mascotId!!,
                action = puppetAction,
                interaction = interaction,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (playback.firstOrNull() ?: bitmap != null) {
            WholeCharacterFrames(
                frames = listOfNotNull(playback.firstOrNull() ?: bitmap),
                fps = 1,
                frameTimeNanos = { frameTime },
                startedAtNanos = 0L,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!fallback.isNullOrBlank()) {
            AsyncImage(
                model = resolvedFallback,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun WholeCharacterFrames(
    frames: List<Bitmap>,
    fps: Int,
    frameTimeNanos: () -> Long,
    startedAtNanos: Long,
    modifier: Modifier = Modifier,
) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    Canvas(modifier = modifier) {
        if (frames.isEmpty()) return@Canvas
        // Read the clock only in the draw phase: PNG playback must not recompose
        // the entire hero UI every frame. A single still needs no clock at all.
        val frameTime = if (frames.size > 1) frameTimeNanos() else 0L
        val elapsed = if (startedAtNanos > 0L) {
            (frameTime - startedAtNanos).coerceAtLeast(0L)
        } else frameTime
        val index = ((elapsed / (1_000_000_000L / fps.coerceAtLeast(1))) % frames.size).toInt()
        val bitmap = frames[index]
        val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val destination = android.graphics.RectF(
            (size.width - width) / 2f,
            (size.height - height) / 2f,
            (size.width + width) / 2f,
            (size.height + height) / 2f,
        )
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(bitmap, null, destination, paint)
        }
    }
}

private val MascotIdPattern = Regex("(?:/hero/|/assets/)([0-9a-fA-F-]{16,})/")

private fun extractMascotId(url: String?): String? =
    url?.let { MascotIdPattern.find(it)?.groupValues?.getOrNull(1) }

private const val MeshColumns = 14
private const val MeshRows = 14

/**
 * A lightweight 2.5D puppet. Android deforms the transparent mascot texture
 * every display frame, so motion stays continuous without generated video or
 * dozens of decoded bitmaps. The center of the face is kept deliberately
 * stable while the torso, outline and appendage zones have secondary motion.
 */
@Composable
private fun LivingMesh(
    bitmap: Bitmap,
    frameTimeNanos: Long,
    reaction: String?,
    reactionStartedAt: Long,
    modifier: Modifier = Modifier,
) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val vertices = remember(bitmap) { FloatArray((MeshColumns + 1) * (MeshRows + 1) * 2) }
    Canvas(modifier = modifier) {
        val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        val seconds = frameTimeNanos / 1_000_000_000.0
        val breath = sin(seconds * 2.0 * PI / 2.8).toFloat()
        val sway = sin(seconds * 2.0 * PI / 4.1).toFloat()
        val follow = sin(seconds * 2.0 * PI / 1.7 + 0.8).toFloat()
        val reactionAge = ((frameTimeNanos - reactionStartedAt).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
        val motionTime = if (reactionStartedAt > 0L) reactionAge else seconds.toFloat()
        val bounce = when (reaction) {
            "joyful" -> -kotlin.math.abs(sin(motionTime * PI * 3.5)).toFloat() * 24f
            "acquaintance" -> -kotlin.math.abs(sin(motionTime * PI * 1.35)).toFloat() * 12f
            else -> 0f
        }
        val spinProgress = (reactionAge / .82f).coerceIn(0f, 1f)
        val spinRotation = if (reaction == "spin") spinProgress * spinProgress * (3f - 2f * spinProgress) * 360f else 0f
        val dancePhase = reactionAge * (2.0 * PI * 2.35).toFloat()
        val danceActive = reaction == "dance"
        val danceSide = if (danceActive) sin(dancePhase).toFloat() * 22f else 0f
        val danceHop = if (danceActive) -kotlin.math.abs(sin(dancePhase * .5f)).toFloat() * 18f else 0f
        val danceLean = if (danceActive) sin(dancePhase).toFloat() * 8f else 0f
        val danceTwist = if (danceActive) sin(dancePhase + PI.toFloat() / 2f).toFloat() * 13f else 0f
        val shiver = if (reaction == "rainy") sin(seconds * 34.0).toFloat() * 4.5f else 0f
        val thinkingTilt = if (reaction == "thinking") sin(seconds * 1.7).toFloat() * 3.2f else 0f
        val boredTilt = if (reaction == "bored") sin(seconds * .8).toFloat() * 2f - 3f else 0f
        val workingLean = if (reaction == "working") -2.5f else 0f

        var offset = 0
        for (row in 0..MeshRows) {
            val ny = row.toFloat() / MeshRows
            for (column in 0..MeshColumns) {
                val nx = column.toFloat() / MeshColumns
                val torso = ((ny - .34f) / .58f).coerceIn(0f, 1f)
                val faceGuard = (1f - (abs(nx - .5f) * 3.2f).coerceIn(0f, 1f)) *
                    (1f - (abs(ny - .38f) * 5f).coerceIn(0f, 1f))
                val edge = (abs(nx - .5f) * 2f).coerceIn(0f, 1f)
                val outlineMotion = edge * edge * (1f - faceGuard)

                var x = left + nx * width + danceSide + shiver
                var y = top + ny * height + bounce + danceHop
                x += (nx - .5f) * breath * 7f * torso
                x += sway * 4f * (1f - ny) * (1f - faceGuard)
                x += follow * 3.5f * outlineMotion * sin((ny * PI * 2.0) + seconds * 2.4).toFloat()
                x += danceTwist * (ny - .58f) * (1f - faceGuard * .7f)
                y += breath * 4.5f * torso
                y += sin(seconds * 2.0 * PI / 3.3).toFloat() * 2.5f
                if (reaction == "sleeping") {
                    x += (nx - .5f) * 10f * torso
                    y += 10f * (1f - faceGuard * .4f)
                }
                if (reaction == "bored") y += 7f * (1f - faceGuard)
                if (reaction == "working") y -= kotlin.math.abs(sin(seconds * 3.4)).toFloat() * 2f * torso

                vertices[offset++] = x
                vertices[offset++] = y
            }
        }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.save()
            canvas.nativeCanvas.rotate(
                spinRotation + danceLean + thinkingTilt + boredTilt + workingLean,
                size.width / 2f,
                size.height * .72f,
            )
            canvas.nativeCanvas.drawBitmapMesh(
                bitmap,
                MeshColumns,
                MeshRows,
                vertices,
                0,
                null,
                0,
                paint,
            )
            canvas.nativeCanvas.restore()
        }
    }
}

private fun decodeFile(path: String, maxSize: Int): Bitmap? {
    val decoded = BitmapFactory.decodeFile(path) ?: return null
    val longest = maxOf(decoded.width, decoded.height)
    if (longest <= maxSize) return decoded
    val scale = maxSize.toFloat() / longest
    return Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
        true,
    ).also { if (it !== decoded) decoded.recycle() }
}

private fun decodeLocalFrame(url: String, maxSize: Int): Bitmap? {
    if (!url.startsWith("file:")) return null
    val path = runCatching { URI(url.substringBefore('?')).path }.getOrNull() ?: return null
    val decoded = BitmapFactory.decodeFile(path) ?: return null
    val longest = maxOf(decoded.width, decoded.height)
    if (longest <= maxSize) return decoded
    val scale = maxSize.toFloat() / longest
    return Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
        true,
    ).also { if (it !== decoded) decoded.recycle() }
}
