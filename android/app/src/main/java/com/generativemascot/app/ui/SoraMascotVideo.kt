package com.generativemascot.app.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.generativemascot.app.R
import com.generativemascot.app.data.HeroLocalStore
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val CROSSFADE_MS = 360L
private const val REACTION_HANDOFF_MS = 90L
private const val PREPARE_BEFORE_END_MS = 500L
private const val TRANSITION_BEFORE_END_MS = 220L
private const val SEAMLESS_SWAP_BEFORE_END_MS = 35L
private const val SLEEP_ANCHOR_MS = 6_000L
private const val SLEEP_HANDOFF_PRELOAD_MS = 700L
private const val SLEEP_HANDOFF_CROSSFADE_MS = 360L
private const val SLEEP_LOOP_MARKER = "sleep-loop-ready"
private const val FIRST_FRAME_TIMEOUT_MS = 1_500L

private const val BACKGROUND_KEY_SHADER = """
    uniform shader content;
    layout(color) uniform half4 keyColor;
    uniform float greenKey;

    half4 main(float2 coordinate) {
        half4 color = content.eval(coordinate);
        float distanceFromBackground = distance(float3(color.rgb), float3(keyColor.rgb));
        float genericAlpha = smoothstep(0.10, 0.42, distanceFromBackground);
        // Generated chroma footage often preserves the green hue but changes
        // its luminance substantially. Key by green-channel dominance rather
        // than distance from literal #00FF00 so shaded green does not survive
        // as a visible rectangle.
        float greenDominance = float(color.g) - max(float(color.r), float(color.b));
        float chromaAlpha = 1.0 - smoothstep(0.07, 0.22, greenDominance);
        float matteAlpha = mix(genericAlpha, chromaAlpha, greenKey);
        float alpha = float(color.a) * matteAlpha;

        // Video compression mixes green into antialiased silhouette pixels.
        // Despill only the semi-transparent chroma edge, leaving the opaque
        // character palette untouched.
        float edge = greenKey * (1.0 - smoothstep(0.70, 0.98, matteAlpha));
        float neutralGreen = min(float(color.g), max(float(color.r), float(color.b)) + 0.045);
        float3 cleanRgb = float3(color.rgb);
        cleanRgb.g = mix(float(color.g), neutralGreen, edge);
        // Preserve transparency already present in ImageView padding. Without
        // this multiplication transparent black pixels become opaque black
        // bars while the neutral transition frame is visible.
        return half4(half3(cleanRgb) * half(alpha), half(alpha));
    }
"""

private fun mediaUri(raw: String): Uri = runCatching {
    val clean = raw.substringBefore('?')
    val parsed = URI(clean)
    if (parsed.scheme == "file") Uri.fromFile(java.io.File(parsed)) else Uri.parse(clean)
}.getOrElse { Uri.parse(raw.substringBefore('?')) }

/**
 * Plays complete action clips that all share the same authored neutral first and
 * last frame. A gesture queues the next action instead of interrupting motion.
 * Two preloaded texture-backed players crossfade only at the neutral boundary.
 */
@SuppressLint("ProduceStateDoesNotAssignValue")
@OptIn(UnstableApi::class)
@Composable
fun SoraMascotVideo(
    videoUrl: String? = null,
    videoUrls: Map<String, String> = emptyMap(),
    neutralFrameUrl: String? = null,
    action: String?,
    interaction: PuppetInteraction = PuppetInteraction(),
    modifier: Modifier = Modifier,
) {
    if (videoUrls.isEmpty()) {
        if (videoUrl != null) LegacyPerformanceVideo(videoUrl, action, interaction, modifier)
        return
    }

    val normalizedUrls = remember(videoUrls) {
        videoUrls.entries.associate { (key, value) -> HeroLocalStore.normalizeVideoAction(key) to value }
    }
    val backgroundKeys by produceState<Map<String, Int>>(
        initialValue = emptyMap(),
        normalizedUrls,
    ) {
        value = withContext(Dispatchers.IO) {
            normalizedUrls.mapValues { (_, url) -> detectBackgroundColor(url) }
        }
    }
    val neutralBackgroundKey by produceState<Int?>(
        initialValue = null,
        neutralFrameUrl,
    ) {
        value = withContext(Dispatchers.IO) {
            neutralFrameUrl?.let(::detectBackgroundColor) ?: Color.rgb(232, 244, 252)
        }
    }
    val idleAction = if (normalizedUrls.containsKey("idle")) "idle" else normalizedUrls.keys.first()
    var requestedAction by remember(normalizedUrls) { mutableStateOf(idleAction) }
    var requestSerial by remember(normalizedUrls) { mutableIntStateOf(0) }

    fun queue(actionName: String) {
        val normalized = HeroLocalStore.normalizeVideoAction(actionName)
        requestedAction = if (normalizedUrls.containsKey(normalized)) normalized else idleAction
        requestSerial += 1
        Log.i("MascotPlayback", "queue action=$requestedAction serial=$requestSerial")
    }

    LaunchedEffect(action) {
        val normalized = HeroLocalStore.normalizeVideoAction(action ?: "idle")
        if (normalized != "idle") queue(normalized)
    }
    LaunchedEffect(interaction.actionSerial) {
        if (interaction.actionSerial > 0) interaction.actionName?.let(::queue)
    }
    LaunchedEffect(interaction.tapSerial) { if (interaction.tapSerial > 0) queue("joyful") }
    LaunchedEffect(interaction.petSerial) { if (interaction.petSerial > 0) queue("joyful") }
    LaunchedEffect(interaction.playSerial) { if (interaction.playSerial > 0) queue("joyful") }
    LaunchedEffect(interaction.sleepSerial) { if (interaction.sleepSerial > 0) queue("sleeping") }
    LaunchedEffect(interaction.spinSerial) { if (interaction.spinSerial > 0) queue("dancing") }
    LaunchedEffect(interaction.danceSerial) { if (interaction.danceSerial > 0) queue("dancing") }

    val context = LocalContext.current
    val firstFrameRendered = remember(normalizedUrls) { BooleanArray(2) }
    val players = remember(normalizedUrls) {
        List(2) { index ->
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        firstFrameRendered[index] = true
                    }
                })
            }
        }
    }
    var playerViews by remember(normalizedUrls) { mutableStateOf<List<PlayerView>>(emptyList()) }
    var neutralView by remember(normalizedUrls, neutralFrameUrl) { mutableStateOf<ImageView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            FrameLayout(viewContext).also { frame ->
                val views = players.mapIndexed { index, player ->
                    (LayoutInflater.from(viewContext)
                        .inflate(R.layout.mascot_video_player, frame, false) as PlayerView).also { view ->
                        view.player = player
                        // Keep decoder surfaces hidden until the real matte key
                        // and a decoded frame are both ready. Otherwise Samsung
                        // briefly composites the source green plate on route and
                        // character changes.
                        view.alpha = 0f
                        view.setKeepContentOnPlayerReset(true)
                        view.setBackgroundColor(Color.TRANSPARENT)
                        view.setShutterBackgroundColor(Color.TRANSPARENT)
                        (view.videoSurfaceView as? TextureView)?.isOpaque = false
                        frame.addView(view)
                    }
                }
                neutralFrameUrl?.let { url ->
                    ImageView(viewContext).also { image ->
                        image.layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                        // Match PlayerView's fit mode exactly. Generated motion
                        // may widen beyond the neutral pose, so cropping a square
                        // source into this tall container can hide hands, ears or
                        // props even when the paid source video is still intact.
                        image.scaleType = ImageView.ScaleType.FIT_CENTER
                        image.setBackgroundColor(Color.TRANSPARENT)
                        image.setImageURI(mediaUri(url))
                        image.alpha = 0f
                        neutralBackgroundKey?.let { applyBackgroundKey(image, it) }
                        frame.addView(image)
                        neutralView = image
                    }
                }
                playerViews = views
            }
        },
        update = { frame ->
            (0 until frame.childCount).forEach { index ->
                (frame.getChildAt(index) as? PlayerView)?.player = players[index]
            }
        },
    )

    LaunchedEffect(players, playerViews, neutralView, normalizedUrls, backgroundKeys, neutralBackgroundKey) {
        if (playerViews.size != 2) return@LaunchedEffect
        if (!backgroundKeys.keys.containsAll(normalizedUrls.keys)) return@LaunchedEffect
        if (neutralFrameUrl != null && neutralBackgroundKey == null) return@LaunchedEffect
        neutralView?.let { anchor ->
            neutralBackgroundKey?.let { applyBackgroundKey(anchor, it) }
            anchor.alpha = 1f
        }
        var activeIndex = 0
        var currentAction = idleAction
        // Start at zero instead of copying requestSerial: a detail screen can
        // request its action before the players finish initializing.
        var consumedSerial = 0
        var queuedAction: String? = null
        var preparedAction: String? = null

        suspend fun waitForFirstFrame(index: Int) {
            val deadline = SystemClock.uptimeMillis() + FIRST_FRAME_TIMEOUT_MS
            while (
                isActive &&
                !firstFrameRendered[index] &&
                SystemClock.uptimeMillis() < deadline
            ) delay(16)
        }

        fun prepare(index: Int, actionName: String) {
            val url = normalizedUrls[actionName] ?: normalizedUrls.getValue(idleAction)
            firstFrameRendered[index] = false
            applyBackgroundKey(
                playerViews[index],
                backgroundKeys[actionName] ?: Color.rgb(232, 244, 252),
            )
            players[index].apply {
                stop()
                clearMediaItems()
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(mediaUri(url)))
                prepare()
            }
        }

        suspend fun handOffNow(actionName: String) {
            val outgoingIndex = activeIndex
            val incomingIndex = 1 - outgoingIndex
            val outgoing = players[outgoingIndex]
            val incoming = players[incomingIndex]
            val outgoingView = playerViews[outgoingIndex]
            val incomingView = playerViews[incomingIndex]

            if (preparedAction != actionName) {
                prepare(incomingIndex, actionName)
                preparedAction = actionName
            }
            while (isActive && incoming.playbackState != Player.STATE_READY) delay(8)
            if (!isActive) return

            incomingView.alpha = 0f
            incoming.seekTo(0)
            incoming.play()
            val anchor = neutralView
            val steps = 5
            if (anchor != null) {
                repeat(steps) { step ->
                    anchor.alpha = (step + 1f) / steps
                    delay(REACTION_HANDOFF_MS / steps)
                }
            }
            outgoing.pause()
            waitForFirstFrame(incomingIndex)
            if (!isActive) return
            outgoingView.alpha = 0f
            incomingView.alpha = 1f
            if (anchor != null) {
                repeat(steps) { step ->
                    anchor.alpha = 1f - (step + 1f) / steps
                    delay(REACTION_HANDOFF_MS / steps)
                }
                anchor.alpha = 0f
            }
            outgoing.seekTo(0)
            activeIndex = incomingIndex
            currentAction = actionName
            if (queuedAction == currentAction) queuedAction = null
            preparedAction = null
            Log.i("MascotPlayback", "immediate transition current=$currentAction")
        }

        prepare(activeIndex, currentAction)
        while (isActive && players[activeIndex].playbackState != Player.STATE_READY) delay(16)
        if (!isActive) return@LaunchedEffect
        players[activeIndex].play()
        waitForFirstFrame(activeIndex)
        if (!isActive) return@LaunchedEffect
        playerViews[activeIndex].alpha = 1f
        neutralView?.let { anchor ->
            val steps = 9
            repeat(steps) { step ->
                anchor.alpha = 1f - (step + 1f) / steps
                delay(CROSSFADE_MS / steps)
            }
            anchor.alpha = 0f
        }

        while (isActive) {
            if (requestSerial != consumedSerial) {
                consumedSerial = requestSerial
                queuedAction = requestedAction
                preparedAction = null
                Log.i("MascotPlayback", "consume action=$queuedAction current=$currentAction serial=$consumedSerial")
            }

            val active = players[activeIndex]
            val inactiveIndex = 1 - activeIndex
            val desiredNext = queuedAction ?: idleAction

            // Idle is ambient, not a user-authored action. Never make a tap
            // wait up to six seconds for its loop boundary. Sleep must also be
            // interruptible so a normal tap always wakes the hero. Joy and
            // dance still play to completion once they have begun.
            if (
                queuedAction != null &&
                desiredNext != currentAction &&
                (
                    currentAction == idleAction ||
                    currentAction == "sleeping" ||
                    currentAction == "sleep_loop"
                )
            ) {
                handOffNow(desiredNext)
                continue
            }
            val sleepAnchor = minOf(
                SLEEP_ANCHOR_MS,
                (active.duration - 750L).coerceAtLeast(500L),
            )

            // A sleeping transition is deliberately never cut into a loop. It
            // plays naturally from standing to the authored resting pose, then
            // hands off to a separately generated closed breathing cycle whose
            // first and last image are that exact resting frame.
            if (
                currentAction == "sleeping" &&
                queuedAction == null &&
                normalizedUrls.containsKey("sleep_loop")
            ) {
                if (
                    active.currentPosition >= sleepAnchor - SLEEP_HANDOFF_PRELOAD_MS &&
                    preparedAction != SLEEP_LOOP_MARKER
                ) {
                    prepare(inactiveIndex, "sleep_loop")
                    preparedAction = SLEEP_LOOP_MARKER
                }
                if (active.currentPosition >= sleepAnchor) {
                    if (preparedAction != SLEEP_LOOP_MARKER) {
                        prepare(inactiveIndex, "sleep_loop")
                        preparedAction = SLEEP_LOOP_MARKER
                    }
                    val incoming = players[inactiveIndex]
                    val outgoingView = playerViews[activeIndex]
                    val incomingView = playerViews[inactiveIndex]
                    incomingView.alpha = 0f
                    active.pause()
                    while (isActive && incoming.playbackState != Player.STATE_READY) delay(16)
                    if (!isActive) break
                    incoming.seekTo(0)
                    incoming.play()
                    waitForFirstFrame(inactiveIndex)
                    if (!isActive) break
                    val steps = 15
                    repeat(steps) { step ->
                        val mix = (step + 1f) / steps
                        incomingView.alpha = mix
                        outgoingView.alpha = 1f - mix
                        delay(SLEEP_HANDOFF_CROSSFADE_MS / steps)
                    }
                    active.seekTo(0)
                    outgoingView.alpha = 0f
                    incomingView.alpha = 1f
                    activeIndex = inactiveIndex
                    currentAction = "sleep_loop"
                    Log.i("MascotPlayback", "transition sleeping->sleep_loop")
                    preparedAction = null
                    continue
                }
            }

            // The dedicated closed-pose clip may repeat forever. Its locally
            // prepared first and last frames are identical, so swap surfaces at
            // the boundary instead of blending two silhouettes into a ghost.
            if (currentAction == "sleep_loop") {
                if (queuedAction == null) {
                    val loopRemaining = if (active.duration > 0) {
                        active.duration - active.currentPosition
                    } else {
                        Long.MAX_VALUE
                    }
                    if (
                        loopRemaining <= SLEEP_HANDOFF_PRELOAD_MS &&
                        preparedAction != SLEEP_LOOP_MARKER
                    ) {
                        prepare(inactiveIndex, "sleep_loop")
                        preparedAction = SLEEP_LOOP_MARKER
                    }
                    if (
                        loopRemaining <= SEAMLESS_SWAP_BEFORE_END_MS ||
                        active.playbackState == Player.STATE_ENDED
                    ) {
                        if (preparedAction != SLEEP_LOOP_MARKER) {
                            prepare(inactiveIndex, "sleep_loop")
                            preparedAction = SLEEP_LOOP_MARKER
                        }
                        val incoming = players[inactiveIndex]
                        while (isActive && incoming.playbackState != Player.STATE_READY) delay(16)
                        if (!isActive) break
                        incoming.seekTo(0)
                        incoming.play()
                        waitForFirstFrame(inactiveIndex)
                        if (!isActive) break
                        val outgoingView = playerViews[activeIndex]
                        val incomingView = playerViews[inactiveIndex]
                        active.pause()
                        active.seekTo(0)
                        outgoingView.alpha = 0f
                        incomingView.alpha = 1f
                        activeIndex = inactiveIndex
                        preparedAction = null
                        continue
                    }
                    delay(16)
                    continue
                }
                // Do not seek back into the authored sleep clip to wake up.
                // Short clips can have no decodable frame at the old fixed
                // 6-second seek point, which left the coroutine waiting forever.
                // Fall through to the normal transition and crossfade directly
                // from the closed sleep loop to the requested action.
            }
            val remaining = if (currentAction == "sleep_loop" && queuedAction != null) {
                0L
            } else if (active.duration > 0) {
                active.duration - active.currentPosition
            } else {
                Long.MAX_VALUE
            }

            if (remaining <= PREPARE_BEFORE_END_MS && preparedAction != desiredNext) {
                prepare(inactiveIndex, desiredNext)
                preparedAction = desiredNext
            }

            val boundaryWindow = if (desiredNext == currentAction) {
                SEAMLESS_SWAP_BEFORE_END_MS
            } else {
                TRANSITION_BEFORE_END_MS
            }
            val atBoundary = remaining <= boundaryWindow || active.playbackState == Player.STATE_ENDED
            if (atBoundary) {
                if (preparedAction != desiredNext) {
                    prepare(inactiveIndex, desiredNext)
                    preparedAction = desiredNext
                }
                val incoming = players[inactiveIndex]
                while (isActive && incoming.playbackState != Player.STATE_READY) {
                    if (active.playbackState == Player.STATE_ENDED) active.pause()
                    delay(16)
                }
                if (!isActive) break

                incoming.seekTo(0)
                incoming.play()
                val outgoingView = playerViews[activeIndex]
                val incomingView = playerViews[inactiveIndex]
                incomingView.alpha = 0f
                val steps = 9
                val anchor = neutralView
                if (anchor != null && desiredNext != currentAction) {
                    repeat(steps) { step ->
                        anchor.alpha = (step + 1f) / steps
                        delay(CROSSFADE_MS / steps)
                    }
                    active.pause()
                    // STATE_READY means the decoder is prepared, not that a frame
                    // reached the TextureView. Keep the neutral anchor visible
                    // until that happens so the surface's black clear color never
                    // flashes at the sides during a player swap.
                    waitForFirstFrame(inactiveIndex)
                    if (!isActive) break
                    outgoingView.alpha = 0f
                    incomingView.alpha = 1f
                    repeat(steps) { step ->
                        anchor.alpha = 1f - (step + 1f) / steps
                        delay(CROSSFADE_MS / steps)
                    }
                    anchor.alpha = 0f
                } else {
                    // Seamless assets end on their own first frame. Wait until
                    // the incoming texture actually has that frame, then swap
                    // atomically; an alpha blend would show both bodies at once.
                    waitForFirstFrame(inactiveIndex)
                    if (!isActive) break
                }
                active.pause()
                active.seekTo(0)
                outgoingView.alpha = 0f
                incomingView.alpha = 1f
                activeIndex = inactiveIndex
                currentAction = desiredNext
                Log.i("MascotPlayback", "transition complete current=$currentAction")
                if (queuedAction == currentAction) queuedAction = null
                preparedAction = null
            }
            delay(16)
        }
    }

    DisposableEffect(players) {
        onDispose { players.forEach(ExoPlayer::release) }
    }
}

internal fun detectBackgroundColor(rawUrl: String): Int = runCatching {
    val uri = mediaUri(rawUrl)
    val bitmap = if (uri.path?.endsWith(".png", ignoreCase = true) == true) {
        BitmapFactory.decodeFile(uri.path)
    } else {
        MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(uri.path)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        }
    } ?: return@runCatching Color.rgb(232, 244, 252)

    try {
        val samples = mutableListOf<Int>()
        val xs = listOf(.025f, .075f, .925f, .975f)
        val ys = listOf(.04f, .18f, .36f, .64f, .82f, .96f)
        for (xRatio in xs) for (yRatio in ys) {
            val x = (bitmap.width * xRatio).toInt().coerceIn(0, bitmap.width - 1)
            val y = (bitmap.height * yRatio).toInt().coerceIn(0, bitmap.height - 1)
            samples += bitmap.getPixel(x, y)
        }
        fun median(channel: (Int) -> Int): Int = samples.map(channel).sorted()[samples.size / 2]
        Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
    } finally {
        bitmap.recycle()
    }
}.getOrDefault(Color.rgb(232, 244, 252))

@OptIn(UnstableApi::class)
internal fun applyBackgroundKey(view: View, keyColor: Int) {
    if (Build.VERSION.SDK_INT < 33) return
    val shader = RuntimeShader(BACKGROUND_KEY_SHADER)
    shader.setColorUniform("keyColor", keyColor)
    shader.setFloatUniform(
        "greenKey",
        if (
            Color.green(keyColor) >= 90 &&
            Color.green(keyColor) >= Color.red(keyColor) + 45 &&
            Color.green(keyColor) >= Color.blue(keyColor) + 35
        ) 1f else 0f,
    )
    // Applying a RenderEffect directly to TextureView is unreliable on some
    // Samsung/Qualcomm combinations: the decoder renders normally, but the
    // surface is composited as fully transparent. Apply the effect to the
    // PlayerView render node so its texture-backed child participates in the
    // filtered subtree.
    view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
}

private data class VideoBeat(val startMs: Long, val endMs: Long)

private fun videoBeat(action: String?): VideoBeat = when (action) {
    "joyful", "content", "eating", "sad" -> VideoBeat(2_000, 3_930)
    "sleeping" -> VideoBeat(4_000, 5_930)
    "dancing" -> VideoBeat(6_000, 7_930)
    else -> VideoBeat(0, 1_930)
}

/** Backward-compatible playback for already-created single Sora videos. */
@OptIn(UnstableApi::class)
@Composable
private fun LegacyPerformanceVideo(
    videoUrl: String,
    action: String?,
    interaction: PuppetInteraction,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val uri = remember(videoUrl) { mediaUri(videoUrl) }
    var readySerial by remember(videoUrl) { mutableIntStateOf(0) }
    val player = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) readySerial += 1
                }
            })
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    var reaction by remember(videoUrl) { mutableStateOf<String?>(null) }
    var reactionToken by remember(videoUrl) { mutableIntStateOf(0) }

    suspend fun showReaction(name: String) {
        val token = ++reactionToken
        reaction = name
        val beat = videoBeat(name)
        delay(beat.endMs - beat.startMs)
        if (reactionToken == token) reaction = null
    }

    LaunchedEffect(interaction.tapSerial) { if (interaction.tapSerial > 0) showReaction("joyful") }
    LaunchedEffect(interaction.petSerial) { if (interaction.petSerial > 0) showReaction("joyful") }
    LaunchedEffect(interaction.playSerial) { if (interaction.playSerial > 0) showReaction("joyful") }
    LaunchedEffect(interaction.sleepSerial) { if (interaction.sleepSerial > 0) showReaction("sleeping") }
    LaunchedEffect(interaction.spinSerial) { if (interaction.spinSerial > 0) showReaction("dancing") }
    LaunchedEffect(interaction.danceSerial) { if (interaction.danceSerial > 0) showReaction("dancing") }
    LaunchedEffect(interaction.actionSerial) {
        if (interaction.actionSerial > 0) {
            interaction.actionName?.let { showReaction(HeroLocalStore.normalizeVideoAction(it)) }
        }
    }

    val activeBeat = videoBeat(reaction ?: action)
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            (LayoutInflater.from(viewContext).inflate(R.layout.mascot_video_player, null) as PlayerView)
                .also { it.player = player }
        },
        update = { it.player = player },
    )

    LaunchedEffect(player, readySerial, activeBeat) {
        if (player.playbackState != Player.STATE_READY) return@LaunchedEffect
        player.seekTo(activeBeat.startMs)
        player.play()
        while (isActive) {
            if (player.currentPosition >= activeBeat.endMs - 35 || player.playbackState == Player.STATE_ENDED) {
                player.seekTo(activeBeat.startMs)
                player.play()
            }
            delay(30)
        }
    }

    DisposableEffect(player) { onDispose { player.release() } }
}
