package com.generativemascot.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.generativemascot.app.data.HeroLocalStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

private const val AssetRoot = "puppet/axolotl"
private const val LegacyAxolotl = "ac2a31dd-6ed3-4dc6-8203-5bd1ac397a46"
private val RequiredParts = setOf(
    "head", "torso", "tail", "arm", "foot_left", "foot_right", "gill_left", "gill_right",
    "eye_forward", "eye_left", "eye_right", "eye_closed", "mouth_happy", "mouth_o", "mouth_sad",
)

data class PuppetInteraction(
    val tapSerial: Int = 0,
    val petSerial: Int = 0,
    val playSerial: Int = 0,
    val spinSerial: Int = 0,
    val danceSerial: Int = 0,
    val sleepSerial: Int = 0,
    val dragX: Float = 0f,
    val dragY: Float = 0f,
)

private data class PuppetParts(
    val head: Bitmap, val torso: Bitmap, val tail: Bitmap, val arm: Bitmap,
    val footLeft: Bitmap, val footRight: Bitmap, val gillLeft: Bitmap, val gillRight: Bitmap,
    val eyeForward: Bitmap, val eyeLeft: Bitmap, val eyeRight: Bitmap, val eyeClosed: Bitmap,
    val mouthHappy: Bitmap, val mouthO: Bitmap, val mouthSad: Bitmap,
)

private enum class PuppetEmotion { HAPPY, CURIOUS, SURPRISED, SLEEPY, SAD }
private enum class Reaction { NONE, TAPPED, PETTED, PLAYING, SPINNING, DANCING, SLEEPING }

fun hasRiggedPuppet(mascotId: String?): Boolean {
    if (mascotId == null) return false
    if (mascotId == LegacyAxolotl) return true
    return HeroLocalStore.current?.puppetPartFiles(mascotId)?.keys?.containsAll(RequiredParts) == true
}

@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun RiggedPuppet(
    mascotId: String,
    action: String = "content",
    interaction: PuppetInteraction = PuppetInteraction(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val parts by produceState<PuppetParts?>(null, mascotId) {
        value = withContext(Dispatchers.IO) {
            val local = HeroLocalStore.current?.puppetPartFiles(mascotId).orEmpty()
            fun load(name: String): Bitmap {
                val file: File? = local[name]
                if (file != null) return BitmapFactory.decodeFile(file.absolutePath)
                return context.assets.open("$AssetRoot/$name.png").use(BitmapFactory::decodeStream)
            }
            runCatching {
                PuppetParts(
                    load("head"), load("torso"), load("tail"), load("arm"), load("foot_left"),
                    load("foot_right"), load("gill_left"), load("gill_right"), load("eye_forward"),
                    load("eye_left"), load("eye_right"), load("eye_closed"), load("mouth_happy"),
                    load("mouth_o"), load("mouth_sad"),
                )
            }.getOrNull()
        }
    }
    var frameTime by remember { mutableLongStateOf(0L) }
    var reaction by remember { mutableStateOf(Reaction.NONE) }
    var reactionStartedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(parts) { while (isActive && parts != null) withFrameNanos { frameTime = it } }
    LaunchedEffect(interaction.tapSerial) {
        if (interaction.tapSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        reaction = Reaction.TAPPED
        reactionStartedAt = startedAt
        delay(700)
        if (reactionStartedAt == startedAt) reaction = Reaction.NONE
    }
    LaunchedEffect(interaction.petSerial) {
        if (interaction.petSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        reaction = Reaction.PETTED
        reactionStartedAt = startedAt
        delay(1_150)
        if (reactionStartedAt == startedAt) reaction = Reaction.NONE
    }
    LaunchedEffect(interaction.playSerial) {
        if (interaction.playSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        reaction = Reaction.PLAYING
        reactionStartedAt = startedAt
        delay(1_300)
        if (reactionStartedAt == startedAt) reaction = Reaction.NONE
    }
    LaunchedEffect(interaction.spinSerial) {
        if (interaction.spinSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        reaction = Reaction.SPINNING
        reactionStartedAt = startedAt
        delay(900)
        if (reactionStartedAt == startedAt) reaction = Reaction.NONE
    }
    LaunchedEffect(interaction.danceSerial) {
        if (interaction.danceSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        reaction = Reaction.DANCING
        reactionStartedAt = startedAt
        delay(2_650)
        if (reactionStartedAt == startedAt) reaction = Reaction.NONE
    }
    LaunchedEffect(interaction.sleepSerial) {
        if (interaction.sleepSerial == 0) return@LaunchedEffect
        val startedAt = System.nanoTime()
        reaction = Reaction.SLEEPING
        reactionStartedAt = startedAt
        delay(2_200)
        if (reactionStartedAt == startedAt) reaction = Reaction.NONE
    }
    parts?.let {
        PuppetCanvas(it, frameTime, action, reaction, reactionStartedAt, interaction.dragX, interaction.dragY, modifier)
    }
}

@Composable
private fun PuppetCanvas(
    parts: PuppetParts, frameTimeNanos: Long, action: String, reaction: Reaction, reactionStartedAt: Long,
    dragX: Float, dragY: Float, modifier: Modifier,
) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    Canvas(modifier) {
        val seconds = frameTimeNanos / 1_000_000_000.0
        val baseEmotion = when (action) {
            "working", "thinking", "night_entity" -> PuppetEmotion.CURIOUS
            "sleeping" -> PuppetEmotion.SLEEPY
            "bored", "rainy" -> PuppetEmotion.SAD
            "surprised" -> PuppetEmotion.SURPRISED
            else -> PuppetEmotion.HAPPY
        }
        val emotion = when (reaction) {
            Reaction.TAPPED -> PuppetEmotion.SURPRISED
            Reaction.PETTED, Reaction.PLAYING, Reaction.DANCING -> PuppetEmotion.HAPPY
            Reaction.SPINNING -> PuppetEmotion.SURPRISED
            Reaction.SLEEPING -> PuppetEmotion.SLEEPY
            Reaction.NONE -> baseEmotion
        }
        val reactionAge = ((frameTimeNanos - reactionStartedAt).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
        val breath = sin(seconds * 2.0 * PI / if (emotion == PuppetEmotion.SLEEPY) 3.8 else 2.5).toFloat()
        val sway = sin(seconds * 2.0 * PI / if (action == "bored") 5.5 else 4.0).toFloat()
        val greeting = if (action == "acquaintance") sin(seconds * 2.0 * PI / .85).toFloat() else 0f
        val thinking = if (action == "thinking" || action == "working") sin(seconds * 2.0 * PI / 2.2).toFloat() else 0f
        val petBoost = if (reaction == Reaction.PETTED) 2.2f else 1f
        val playBounce = if (reaction == Reaction.PLAYING) -kotlin.math.abs(sin(reactionAge * PI * 3.5)).toFloat() * 52f else 0f
        val playWave = if (reaction == Reaction.PLAYING) sin(reactionAge * PI * 7.0).toFloat() else 0f
        val spinProgress = (reactionAge / .82f).coerceIn(0f, 1f)
        val spinRotation = if (reaction == Reaction.SPINNING) spinProgress * spinProgress * (3f - 2f * spinProgress) * 360f else 0f
        // A readable battle-emote rhythm: hips and arms travel in counter-phase,
        // alternating feet carry the weight, and the last beat lands in place.
        val dancePhase = reactionAge * (2.0 * PI * 2.35).toFloat()
        val danceActive = reaction == Reaction.DANCING
        val danceSide = if (danceActive) sin(dancePhase).toFloat() * 42f else 0f
        val danceHop = if (danceActive) -kotlin.math.abs(sin(dancePhase * .5f)).toFloat() * 30f else 0f
        val danceLean = if (danceActive) sin(dancePhase).toFloat() * 9f else 0f
        val danceArm = if (danceActive) sin(dancePhase + PI.toFloat() / 2f).toFloat() * 58f else 0f
        val danceKnee = if (danceActive) sin(dancePhase).toFloat() else 0f
        val leftFootLift = if (danceActive) danceKnee.coerceAtLeast(0f) * 30f else 0f
        val rightFootLift = if (danceActive) (-danceKnee).coerceAtLeast(0f) * 30f else 0f
        val tail = sin(seconds * 2.0 * PI / (1.7 / petBoost) + .7).toFloat()
        val gills = sin(seconds * 2.0 * PI / 2.1 + 1.4).toFloat()
        val blink = emotion != PuppetEmotion.SLEEPY && (seconds % 3.7) > 3.52
        val recoil = if (emotion == PuppetEmotion.SURPRISED) -22f else 0f
        val droop = if (emotion == PuppetEmotion.SAD) 18f else 0f
        val scale = minOf(size.width, size.height) / 1000f
        val left = (size.width - 1000f * scale) / 2f
        val top = (size.height - 1000f * scale) / 2f

        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            canvas.save()
            canvas.translate(left + dragX * .35f + danceSide, top + dragY * .18f + playBounce + danceHop)
            canvas.scale(scale, scale)
            canvas.rotate((dragX / 22f).coerceIn(-13f, 13f) + spinRotation + danceLean, 500f, 690f)
            canvas.part(parts.tail, 735f, 615f + breath * 4f, 265f, -8f + tail * 14f, paint = paint)
            canvas.part(parts.gillLeft, 245f, 345f + recoil + droop, 255f, -4f - gills * 5f - droop * .3f, paint = paint)
            canvas.part(parts.gillRight, 755f, 345f + recoil + droop, 255f, 4f + gills * 5f + droop * .3f, paint = paint)
            canvas.part(parts.torso, 500f - danceSide * .18f, 625f + breath * 5f, 440f, sway * 1.5f - danceLean * .45f, 1f + breath * .018f, 1f + breath * .025f, paint)
            canvas.part(parts.arm, 300f - danceArm * .75f, 610f + breath * 3f - playWave * 20f + danceArm * .16f, 125f, -8f + sway * 5f - playWave * 42f + danceArm, paint = paint)
            canvas.part(parts.arm, 700f - danceArm * .75f, 610f - greeting * 34f + playWave * 20f - danceArm * .16f, 125f, 8f - sway * 5f + greeting * 48f + playWave * 42f - danceArm, -1f, 1f, paint)
            canvas.part(parts.footLeft, 395f - danceKnee * 22f, 772f - leftFootLift, 155f, sway * 1.5f - danceKnee * 12f, paint = paint)
            canvas.part(parts.footRight, 605f - danceKnee * 22f, 772f - rightFootLift, 155f, -sway * 1.5f - danceKnee * 12f, paint = paint)
            canvas.part(parts.head, 500f + sway * 4f - danceSide * .12f, 365f + recoil + droop - breath * 2f, 620f, sway * 2f + thinking * 4f - danceLean * .55f, paint = paint)
            val eye = when {
                emotion == PuppetEmotion.SLEEPY || blink -> parts.eyeClosed
                emotion == PuppetEmotion.CURIOUS && thinking >= 0 -> parts.eyeRight
                emotion == PuppetEmotion.CURIOUS -> parts.eyeLeft
                else -> parts.eyeForward
            }
            val eyeScale = if (emotion == PuppetEmotion.SURPRISED) 1.2f else 1f
            val eyeWidth = if (emotion == PuppetEmotion.SLEEPY || blink) 112f else 100f
            canvas.part(eye, 405f + sway * 2f, 365f + recoil + droop, eyeWidth, 0f, eyeScale, eyeScale, paint)
            canvas.part(eye, 595f + sway * 2f, 365f + recoil + droop, eyeWidth, 0f, -eyeScale, eyeScale, paint)
            val mouth = when (emotion) {
                PuppetEmotion.SURPRISED -> parts.mouthO
                PuppetEmotion.SAD, PuppetEmotion.SLEEPY -> parts.mouthSad
                else -> parts.mouthHappy
            }
            val mouthWidth = when (emotion) {
                PuppetEmotion.SURPRISED -> 76f
                PuppetEmotion.SAD -> 105f
                PuppetEmotion.SLEEPY -> 75f
                else -> 120f + breath * 4f
            }
            canvas.part(mouth, 500f + sway * 2f, 472f + recoil + droop, mouthWidth, 0f, paint = paint)
            canvas.restore()
        }
    }
}

private fun android.graphics.Canvas.part(
    bitmap: Bitmap, centerX: Float, centerY: Float, width: Float, rotation: Float,
    scaleX: Float = 1f, scaleY: Float = 1f, paint: Paint,
) {
    val height = width * bitmap.height / bitmap.width.toFloat()
    save(); translate(centerX, centerY); rotate(rotation); scale(scaleX, scaleY)
    drawBitmap(bitmap, null, RectF(-width / 2f, -height / 2f, width / 2f, height / 2f), paint)
    restore()
}
