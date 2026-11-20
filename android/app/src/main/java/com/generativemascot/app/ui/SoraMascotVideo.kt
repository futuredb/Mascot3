package com.generativemascot.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.generativemascot.app.data.DERIVED_SLEEP_START_MS
import com.generativemascot.app.data.DERIVED_SLEEP_END_MS
import java.net.URI
import java.util.WeakHashMap
import com.generativemascot.app.physics.TiltLighting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

private const val CROSSFADE_MS = 360L
private const val PREPARE_BEFORE_END_MS = 500L
private const val TRANSITION_BEFORE_END_MS = 220L
// Closed sleep clips have matching endpoints: swap on the last frame, not
// hundreds of milliseconds early. At 24 fps this covers one frame interval.
private const val CLOSED_LOOP_SWAP_BEFORE_END_MS = 40L
private const val SLEEP_ANCHOR_MS = 6_000L
private const val DERIVED_SLEEP_ANCHOR_MS = 2_100L
private const val SLEEP_HANDOFF_PRELOAD_MS = 700L
private const val SLEEP_HANDOFF_CROSSFADE_MS = 360L
private const val SLEEP_LOOP_MARKER = "sleep-loop-ready"
private const val FIRST_FRAME_TIMEOUT_MS = 1_500L
private val compatibilityKeys = WeakHashMap<ExoPlayer, Pair<Int, GreenMatteTuning>>()
private val compatibilityLighting = WeakHashMap<ExoPlayer, VideoLightingBinding>()
private val compatibilityColorListeners = WeakHashMap<ExoPlayer, Player.Listener>()
private val localBackgroundKeys = object : LinkedHashMap<String, Int>(32, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 32
}
private data class NeutralMatte(val bitmap: Bitmap?)

private const val BACKGROUND_KEY_SHADER = """
    uniform shader content;
    layout(color) uniform half4 keyColor;
    uniform float greenKey;
    uniform float keyLow;
    uniform float keyHigh;
    uniform float greenLow;
    uniform float greenHigh;
    uniform float greenAdaptive;

    half4 main(float2 coordinate) {
        half4 color = content.eval(coordinate);
        float distanceFromBackground = distance(float3(color.rgb), float3(keyColor.rgb));
        float genericAlpha = smoothstep(keyLow, keyHigh, distanceFromBackground);
        // Generated chroma footage often preserves the green hue but changes
        // its luminance substantially. Key by green-channel dominance rather
        // than distance from literal #00FF00 so shaded green does not survive
        // as a visible rectangle.
        float greenDominance = float(color.g) - max(float(color.r), float(color.b));
        float chromaAlpha = 1.0 - smoothstep(0.07, 0.22, greenDominance);
        float greenHue = 1.0 - smoothstep(0.65, 0.72, float(color.b) / max(float(color.g), 0.001));
        float adaptiveAlpha = 1.0 - smoothstep(greenLow, greenHigh, greenDominance);
        chromaAlpha = mix(chromaAlpha, adaptiveAlpha, greenAdaptive * greenHue);
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
    onAnimationVisible: ((String) -> Unit)? = null,
    onOneShotFinished: ((String) -> Unit)? = null,
    lighting: TiltLighting? = null,
    fullClip: Boolean = false,
    onPlaybackError: (() -> Unit)? = null,
    playbackEnabled: Boolean = true,
    onFirstFrame: (() -> Unit)? = null,
    useNeutralTransitions: Boolean = true,
    switchActionsPromptly: Boolean = false,
    independentMatteActions: Set<String> = emptySet(),
) {
    val playbackAllowed = playbackEnabled && rememberAppResumed()
    if (videoUrls.isEmpty()) {
        if (videoUrl != null) LegacyPerformanceVideo(videoUrl, action, interaction, modifier, lighting, fullClip, onAnimationVisible, onPlaybackError, playbackAllowed, onFirstFrame)
        return
    }

    val derivedSleepLoop = remember(videoUrls) {
        videoUrls.keys.none { HeroLocalStore.normalizeVideoAction(it) == "sleep_loop" } &&
            videoUrls.keys.any { HeroLocalStore.normalizeVideoAction(it) == "sleeping" }
    }
    val normalizedUrls = remember(videoUrls, derivedSleepLoop) {
        val normalized = videoUrls.entries.associate { (key, value) ->
            HeroLocalStore.normalizeVideoAction(key) to value
        }
        if (derivedSleepLoop) {
            normalized + ("sleep_loop" to normalized.getValue("sleeping"))
        } else {
            normalized
        }
    }
    val backgroundKeys by produceState<Map<String, Int>>(
        initialValue = emptyMap(),
        normalizedUrls,
        neutralFrameUrl,
        independentMatteActions,
    ) {
        value = withContext(Dispatchers.IO) {
            val anchorKey=neutralFrameUrl?.let(::detectBackgroundColor)
            normalizedUrls.mapValues { (action, url) ->
                if (action in independentMatteActions) detectBackgroundColor(url)
                else anchorKey ?: detectBackgroundColor(url)
            }
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
    val neutralMatte by produceState<NeutralMatte?>(null,neutralFrameUrl,neutralBackgroundKey) {
        value=null
        if(Build.VERSION.SDK_INT<33 && neutralFrameUrl!=null && neutralBackgroundKey!=null) {
            value=withContext(Dispatchers.IO) {
                NeutralMatte(runCatching {
                    BitmapFactory.decodeFile(mediaUri(neutralFrameUrl).path)?.let { original ->
                        try { keyBitmap(original,neutralBackgroundKey!!) } finally { original.recycle() }
                    }
                }.getOrNull())
            }
        }
    }
    val idleAction = defaultHomeVideoAction(normalizedUrls.keys)
    var requestedAction by remember(normalizedUrls) {
        mutableStateOf(resolveAvailableHomeAction(action ?: "idle", normalizedUrls.keys))
    }
    var requestSerial by remember(normalizedUrls) { mutableIntStateOf(0) }

    fun queue(actionName: String) {
        val normalized = HeroLocalStore.normalizeVideoAction(actionName)
        requestedAction = if (normalizedUrls.containsKey(normalized)) normalized else idleAction
        requestSerial += 1
        Log.i("MascotPlayback", "queue action=$requestedAction serial=$requestSerial")
    }

    LaunchedEffect(action) {
        val normalized = HeroLocalStore.normalizeVideoAction(action ?: "idle")
        queue(normalized)
    }
    LaunchedEffect(interaction.actionSerial) {
        if (interaction.actionSerial > 0) interaction.actionName?.let { queue(it) }
    }
    LaunchedEffect(interaction.tapSerial) { if (interaction.tapSerial > 0) queue("joyful") }
    LaunchedEffect(interaction.petSerial) { if (interaction.petSerial > 0) queue("joyful") }
    LaunchedEffect(interaction.playSerial) { if (interaction.playSerial > 0) queue("joyful") }
    LaunchedEffect(interaction.sleepSerial) { if (interaction.sleepSerial > 0) queue("sleeping") }
    LaunchedEffect(interaction.spinSerial) { if (interaction.spinSerial > 0) queue("dancing") }
    LaunchedEffect(interaction.danceSerial) { if (interaction.danceSerial > 0) queue("dancing") }

    val context = LocalContext.current
    val visibleCallback by rememberUpdatedState(onAnimationVisible)
    val firstFrameCallback by rememberUpdatedState(onFirstFrame)
    val finishedCallback by rememberUpdatedState(onOneShotFinished)
    val playbackAllowedState by rememberUpdatedState(playbackAllowed)
    val firstFrameRendered = remember(normalizedUrls) { BooleanArray(2) }
    val players = remember(normalizedUrls) {
        List(2) { index ->
            createMascotVideoPlayer(context).apply {
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
                neutralFrameUrl?.takeIf { useNeutralTransitions }?.let { url ->
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
            if(Build.VERSION.SDK_INT<33)neutralMatte?.let { neutralView?.setImageBitmap(it.bitmap) }
        },
    )

    LaunchedEffect(players, playerViews, neutralView, normalizedUrls, backgroundKeys, neutralBackgroundKey,neutralMatte) {
        if (playerViews.size != 2) return@LaunchedEffect
        if (!backgroundKeys.keys.containsAll(normalizedUrls.keys)) return@LaunchedEffect
        if (neutralFrameUrl != null && neutralBackgroundKey == null) return@LaunchedEffect
        if(Build.VERSION.SDK_INT<33 && neutralFrameUrl!=null && neutralMatte==null)return@LaunchedEffect
        neutralView?.let { anchor ->
            neutralBackgroundKey?.let { applyBackgroundKey(anchor, it) }
            anchor.alpha = if(Build.VERSION.SDK_INT>=33 || neutralMatte?.bitmap!=null)1f else 0f
        }
        var activeIndex = 0
        var currentAction = requestedAction
        var oneShotBoundaryReportedAt: Long? = null
        val matteTunings = mutableMapOf<String, GreenMatteTuning>()
        val closedSleepSeams = mutableMapOf<String, Boolean>()
        val verifiedGeneratedSeams = mutableMapOf<String, Boolean>()
        val verifiedGeneratedSleepSeams = mutableMapOf<String, Boolean>()
        val lightingJobs = arrayOfNulls<Job>(2)
        // Start at zero instead of copying requestSerial: a detail screen can
        // request its action before the players finish initializing.
        var consumedSerial = 0
        var queuedAction: String? = null
        var preparedAction: String? = null

        suspend fun waitForFirstFrame(index: Int): Boolean {
            val deadline = SystemClock.uptimeMillis() + FIRST_FRAME_TIMEOUT_MS
            while (
                isActive &&
                !firstFrameRendered[index] &&
                SystemClock.uptimeMillis() < deadline
            ) delay(16)
            return firstFrameRendered[index]
        }

        suspend fun waitForReady(index: Int): Boolean {
            val player = players[index]
            val deadline = SystemClock.uptimeMillis() + 5_000L
            while (isActive && player.playbackState != Player.STATE_READY && player.playerError == null && SystemClock.uptimeMillis() < deadline) delay(32)
            return player.playbackState == Player.STATE_READY
        }

        suspend fun prepare(index: Int, actionName: String) {
            val url = normalizedUrls[actionName] ?: normalizedUrls.getValue(idleAction)
            lightingJobs[index]?.cancel()
            firstFrameRendered[index] = false
            val item = MediaItem.Builder()
                .setUri(mediaUri(url))
                .apply {
                    if (actionName == "sleep_loop" && derivedSleepLoop) {
                        setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(DERIVED_SLEEP_START_MS)
                                .setEndPositionMs(DERIVED_SLEEP_END_MS)
                                .build(),
                        )
                    }
                }
                .build()
            val lightPack=if(lighting!=null)loadVideoLighting(context,url) else null
            val binding=lightPack?.let { VideoLightingBinding(it,requireNotNull(lighting)) }
            val tuning = matteTunings[url] ?: withContext(Dispatchers.IO) { detectAdaptiveGreenMatte(url, neutralFrameUrl.takeUnless { actionName in independentMatteActions }) }
                .also { matteTunings[url] = it }
            if (actionName == "sleep_loop" && !derivedSleepLoop && url !in closedSleepSeams) {
                closedSleepSeams[url] = withContext(Dispatchers.IO) {
                    hasClosedLoopSeam(mediaUri(url), backgroundKeys[actionName] ?: Color.rgb(232, 244, 252))
                }
            }
            if (actionName != "sleep_loop" && url !in verifiedGeneratedSeams) {
                verifiedGeneratedSeams[url] = withContext(Dispatchers.IO) {
                    isVerifiedGeneratedLoop(mediaUri(url))
                }
            }
            if (actionName == "sleep_loop" && derivedSleepLoop && url !in verifiedGeneratedSleepSeams) {
                verifiedGeneratedSleepSeams[url] = withContext(Dispatchers.IO) {
                    isVerifiedGeneratedLoop(mediaUri(url), sleepSection = true)
                }
            }
            players[index].apply {
                stop()
                clearMediaItems()
                applyBackgroundKey(playerViews[index],backgroundKeys[actionName] ?: Color.rgb(232,244,252),binding, tuning = tuning)
                repeatMode = if (switchActionsPromptly) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                setMediaItem(item)
                prepare()
            }
            if (binding == null && lighting != null) {
                lightingJobs[index] = launch {
                    val ready = awaitVideoLighting(context, url) ?: return@launch
                    if (players[index].currentMediaItem?.localConfiguration?.uri == mediaUri(url)) {
                        applyBackgroundKey(playerViews[index], backgroundKeys[actionName] ?: Color.rgb(232,244,252),
                            VideoLightingBinding(ready, lighting), tuning = tuning)
                    }
                }
            }
        }

        prepare(activeIndex, currentAction)
        if (!waitForReady(activeIndex) || !isActive) return@LaunchedEffect
        while (isActive && !playbackAllowedState) delay(200)
        if (!isActive) return@LaunchedEffect
        players[activeIndex].play()
        if (!waitForFirstFrame(activeIndex) || !isActive) return@LaunchedEffect
        playerViews[activeIndex].alpha = 1f
        firstFrameCallback?.invoke()
        visibleCallback?.invoke(currentAction)
        neutralView?.let { anchor ->
            val steps = 9
            repeat(steps) { step ->
                anchor.alpha = 1f - (step + 1f) / steps
                delay(CROSSFADE_MS / steps)
            }
            anchor.alpha = 0f
        }

        var pausedForVisibility = false
        while (isActive) {
            if (!playbackAllowedState) {
                if (!pausedForVisibility) { players.forEach { it.pause() }; pausedForVisibility = true }
                delay(200)
                continue
            }
            if (pausedForVisibility) { players[activeIndex].play(); pausedForVisibility = false }
            if (requestSerial != consumedSerial) {
                consumedSerial = requestSerial
                queuedAction = requestedAction
                preparedAction = null
                Log.i("MascotPlayback", "consume action=$queuedAction current=$currentAction serial=$consumedSerial")
            }

            val active = players[activeIndex]
            val inactiveIndex = 1 - activeIndex
            // Direct-key packs contain the paid catalog clips. In
            // that mode `sleeping` itself is the closed breathing loop, so keep
            // repeating it until the user interacts instead of buying an
            // additional `sleep_loop` video.
            val desiredNext = queuedAction ?: if (
                currentAction == "sleeping" && !normalizedUrls.containsKey("sleep_loop")
            ) {
                "sleeping"
            } else {
                requestedAction
            }

            val sleepAnchor = minOf(
                if (derivedSleepLoop) DERIVED_SLEEP_ANCHOR_MS else SLEEP_ANCHOR_MS,
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
                    if (!waitForReady(inactiveIndex)) {
                        preparedAction = null; active.seekTo(0); active.play(); delay(250); continue
                    }
                    if (!isActive) break
                    incoming.seekTo(0)
                    incoming.play()
                    if (!waitForFirstFrame(inactiveIndex)) {
                        incoming.pause(); preparedAction = null; active.seekTo(0); active.play(); delay(250); continue
                    }
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

            // Verify dedicated clips before assuming their endpoints match.
            // Locally generated videos can violate the requested last frame.
            if (currentAction == "sleep_loop") {
                if (queuedAction == null) {
                    val exactSleepSeam = normalizedUrls["sleep_loop"]?.let { url ->
                        if (derivedSleepLoop) verifiedGeneratedSleepSeams[url] else closedSleepSeams[url]
                    } == true
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
                        loopRemaining <= (if (exactSleepSeam) CLOSED_LOOP_SWAP_BEFORE_END_MS else CROSSFADE_MS) ||
                        active.playbackState == Player.STATE_ENDED
                    ) {
                        if (preparedAction != SLEEP_LOOP_MARKER) {
                            prepare(inactiveIndex, "sleep_loop")
                            preparedAction = SLEEP_LOOP_MARKER
                        }
                        val incoming = players[inactiveIndex]
                        if (!waitForReady(inactiveIndex)) {
                            preparedAction = null; active.seekTo(0); active.play(); delay(250); continue
                        }
                        if (!isActive) break
                        incoming.seekTo(0)
                        incoming.play()
                        if (!waitForFirstFrame(inactiveIndex)) {
                            incoming.pause(); preparedAction = null; active.seekTo(0); active.play(); delay(250); continue
                        }
                        if (!isActive) break
                        val outgoingView = playerViews[activeIndex]
                        val incomingView = playerViews[inactiveIndex]
                        if (!exactSleepSeam) {
                            // Both clips continue moving during the overlap. A
                            // frozen outgoing pose makes the seam visibly stall.
                            outgoingView.translationZ = 0f
                            incomingView.translationZ = 1f
                            neutralView?.translationZ = 2f
                            val steps = 9
                            repeat(steps) { step ->
                                val mix = (step + 1f) / steps
                                incomingView.alpha = mix
                                delay(CROSSFADE_MS / steps)
                            }
                        }
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
            if (switchActionsPromptly && desiredNext == currentAction) {
                queuedAction = null
                delay(32)
                continue
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

            val verifiedRepeat = desiredNext == currentAction &&
                (normalizedUrls[currentAction]?.let { verifiedGeneratedSeams[it] } == true)
            val boundaryWindow = if (verifiedRepeat) {
                CLOSED_LOOP_SWAP_BEFORE_END_MS
            } else if (desiredNext == currentAction) {
                CROSSFADE_MS
            } else {
                TRANSITION_BEFORE_END_MS
            }
            val actionChanged = switchActionsPromptly && desiredNext != currentAction
            val atBoundary = actionChanged || remaining <= boundaryWindow || active.playbackState == Player.STATE_ENDED
            if (atBoundary) {
                // Report the actual first clip boundary rather than relying on the
                // resolver's one-second safety timer. Do not begin a second greeting
                // or signature while its shared-state transition is being published.
                // A bounded wait also keeps last-ready-video fallback packs playable.
                if (finishedCallback != null && currentAction in setOf("greeting", "signature_move") && desiredNext == currentAction) {
                    if (oneShotBoundaryReportedAt == null) {
                        oneShotBoundaryReportedAt = SystemClock.uptimeMillis()
                        finishedCallback?.invoke(currentAction)
                    }
                    if (SystemClock.uptimeMillis() - (oneShotBoundaryReportedAt ?: 0L) < 750L) {
                        delay(16)
                        continue
                    }
                }
                if (preparedAction != desiredNext) {
                    prepare(inactiveIndex, desiredNext)
                    preparedAction = desiredNext
                }
                val incoming = players[inactiveIndex]
                if (!waitForReady(inactiveIndex)) {
                    preparedAction = null; queuedAction = null; active.seekTo(0); active.play(); delay(250); continue
                }
                if (!isActive) break

                incoming.seekTo(0)
                incoming.play()
                val outgoingView = playerViews[activeIndex]
                val incomingView = playerViews[inactiveIndex]
                incomingView.alpha = 0f
                val steps = 9
                val anchor = neutralView
                if (useNeutralTransitions && anchor != null && desiredNext != currentAction) {
                    repeat(steps) { step ->
                        anchor.alpha = (step + 1f) / steps
                        delay(CROSSFADE_MS / steps)
                    }
                    active.pause()
                    // STATE_READY means the decoder is prepared, not that a frame
                    // reached the TextureView. Keep the neutral anchor visible
                    // until that happens so the surface's black clear color never
                    // flashes at the sides during a player swap.
                    if (!waitForFirstFrame(inactiveIndex)) {
                        incoming.pause(); anchor.alpha = 0f; preparedAction = null; queuedAction = null
                        active.seekTo(0); active.play(); delay(250); continue
                    }
                    if (!isActive) break
                    outgoingView.alpha = 0f
                    incomingView.alpha = 1f
                    repeat(steps) { step ->
                        anchor.alpha = 1f - (step + 1f) / steps
                        delay(CROSSFADE_MS / steps)
                    }
                    anchor.alpha = 0f
                } else if (verifiedRepeat) {
                    // The new generation passed an exact-frame seam check.
                    // A crossfade here would overlap different motion phases
                    // and introduce ghosting into an already closed cycle.
                    if (!waitForFirstFrame(inactiveIndex)) {
                        incoming.pause(); preparedAction = null; queuedAction = null
                        active.seekTo(0); active.play(); delay(250); continue
                    }
                    if (!isActive) break
                    active.pause()
                    outgoingView.alpha = 0f
                    incomingView.alpha = 1f
                } else {
                    // Existing provider clips can have different endpoints.
                    // Overlap their motion rather than freezing the outgoing
                    // frame for the whole blend or cutting it 350 ms early.
                    if (!waitForFirstFrame(inactiveIndex)) {
                        incoming.pause(); preparedAction = null; queuedAction = null
                        active.seekTo(0); active.play(); delay(250); continue
                    }
                    if (!isActive) break
                    outgoingView.translationZ = 0f
                    incomingView.translationZ = 1f
                    neutralView?.translationZ = 2f
                    repeat(steps) { step ->
                        val blend = (step + 1f) / steps
                        incomingView.alpha = blend
                        if (switchActionsPromptly) outgoingView.alpha = 1f - blend
                        delay(CROSSFADE_MS / steps)
                    }
                }
                active.pause()
                active.seekTo(0)
                outgoingView.alpha = 0f
                incomingView.alpha = 1f
                activeIndex = inactiveIndex
                currentAction = desiredNext
                if (currentAction !in setOf("greeting", "signature_move")) oneShotBoundaryReportedAt = null
                visibleCallback?.invoke(currentAction)
                Log.i("MascotPlayback", "transition complete current=$currentAction")
                if (queuedAction == currentAction) queuedAction = null
                preparedAction = null
            }
            // There is no visual work between transitions. Poll slowly while a
            // clip is far from its boundary, then keep frame-level precision.
            delay(if (remaining > PREPARE_BEFORE_END_MS + 250 && queuedAction == null) 50 else 16)
        }
    }

    DisposableEffect(players) {
        onDispose { players.forEach(::releaseMascotVideoPlayer) }
    }
}

@OptIn(UnstableApi::class)
internal fun releaseMascotVideoPlayer(player: ExoPlayer) {
    compatibilityColorListeners.remove(player)?.let(player::removeListener)
    compatibilityKeys.remove(player);compatibilityLighting.remove(player)
    player.release()
}

internal fun detectBackgroundColor(rawUrl: String): Int = runCatching {
    val uri = mediaUri(rawUrl)
    val cacheKey = if (uri.scheme == "file") uri.path?.let { filePath ->
        java.io.File(filePath).takeIf { it.isFile }?.let { "$filePath:${it.length()}:${it.lastModified()}" }
    } else null
    cacheKey?.let { key -> synchronized(localBackgroundKeys) { localBackgroundKeys[key] }?.let { return@runCatching it } }
    val bitmap = if (uri.path?.endsWith(".png", ignoreCase = true) == true) {
        BitmapFactory.decodeFile(uri.path)
    } else {
        MediaMetadataRetriever().let { retriever ->
            try {
                if (uri.scheme == "https" || uri.scheme == "http") {
                    retriever.setDataSource(uri.toString(), emptyMap())
                } else retriever.setDataSource(uri.path)
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
        Color.rgb(median(Color::red), median(Color::green), median(Color::blue)).also { value ->
            if (cacheKey != null) synchronized(localBackgroundKeys) { localBackgroundKeys[cacheKey] = value }
        }
    } finally {
        bitmap.recycle()
    }
}.getOrDefault(Color.rgb(232, 244, 252))

@OptIn(UnstableApi::class)
internal fun applyBackgroundKey(view: View, keyColor: Int, lighting: VideoLightingBinding? = null, conservative: Boolean = false, tuning: GreenMatteTuning = GreenMatteTuning(), forceVideoEffect: Boolean = false) {
    if (Build.VERSION.SDK_INT < 33 || (view is PlayerView && (lighting != null || forceVideoEffect))) {
        if(Build.VERSION.SDK_INT>=31)view.setRenderEffect(null)
        if(view is PlayerView) {
            (view.videoSurfaceView as? TextureView)?.isOpaque=false
            (view.player as? ExoPlayer)?.let { player ->
                if(compatibilityKeys[player] != (keyColor to tuning) || compatibilityLighting[player] !== lighting) {
                    val effect=VideoBackgroundKey(keyColor,lighting,videoFrameClock(player),tuning)
                    fun updateColor(tracks: androidx.media3.common.Tracks) {
                        val formats=tracks.groups.filter { it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO }
                            .flatMap { group -> (0 until group.length).filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it) } }
                        // Legacy canonical-frame stitching used FFmpeg's default
                        // BT.601 conversion without a colour tag. Media3's SDR
                        // effect path assumes BT.709; invert that matrix only
                        // for untagged clips. Explicit colour metadata is kept.
                        effect.correctUntaggedBt601=formats.isNotEmpty() && formats.all(::usesUntaggedLegacyMatrix)
                    }
                    compatibilityColorListeners.remove(player)?.let(player::removeListener)
                    val listener=object:Player.Listener {
                        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { updateColor(tracks) }
                    }
                    updateColor(player.currentTracks)
                    player.addListener(listener);compatibilityColorListeners[player]=listener
                    player.setVideoEffects(listOf(effect))
                    compatibilityKeys[player]=keyColor to tuning
                    if(lighting==null)compatibilityLighting.remove(player) else compatibilityLighting[player]=lighting
                }
            }
        }
        return // Still images are matted on their existing background IO jobs.
    }
    if(view is PlayerView) (view.player as? ExoPlayer)?.let { player ->
        if(compatibilityLighting.remove(player)!=null) {
            player.setVideoEffects(emptyList());compatibilityKeys.remove(player)
            compatibilityColorListeners.remove(player)?.let(player::removeListener)
        }
    }
    val shader = RuntimeShader(BACKGROUND_KEY_SHADER)
    shader.setColorUniform("keyColor", keyColor)
    shader.setFloatUniform("keyLow", if (conservative) 0.02f else 0.10f)
    shader.setFloatUniform("keyHigh", if (conservative) 0.09f else 0.42f)
    shader.setFloatUniform("greenLow", tuning.low)
    shader.setFloatUniform("greenHigh", tuning.high)
    shader.setFloatUniform("greenAdaptive", if (tuning.adaptive) 1f else 0f)
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
    lighting: TiltLighting?,
    fullClip: Boolean,
    onAnimationVisible: ((String) -> Unit)?,
    onPlaybackError: (() -> Unit)?,
    playbackAllowed: Boolean,
    onFirstFrame: (() -> Unit)?,
) {
    val context = LocalContext.current
    val uri = remember(videoUrl) { mediaUri(videoUrl) }
    val keyColor by produceState<Int?>(null,videoUrl) { value=withContext(Dispatchers.IO){detectBackgroundColor(videoUrl)} }
    var lightBinding by remember(videoUrl,lighting) { mutableStateOf<VideoLightingBinding?>(null) }
    var lightReady by remember(videoUrl,lighting) { mutableStateOf(false) }
    LaunchedEffect(videoUrl,lighting) {
        lightBinding=if(lighting!=null)loadVideoLighting(context,videoUrl)?.let { VideoLightingBinding(it,lighting) } else null
        lightReady=true
    }
    var readySerial by remember(videoUrl) { mutableIntStateOf(0) }
    val visibleCallback by rememberUpdatedState(onAnimationVisible)
    val firstFrameCallback by rememberUpdatedState(onFirstFrame)
    val errorCallback by rememberUpdatedState(onPlaybackError)
    val player = remember(videoUrl) {
        createMascotVideoPlayer(context).apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) readySerial += 1
                }
                override fun onRenderedFirstFrame() {
                    firstFrameCallback?.invoke()
                    if (fullClip) visibleCallback?.invoke("reaction")
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (fullClip) errorCallback?.invoke()
                }
            })
        }
    }
    var reaction by remember(videoUrl) { mutableStateOf<String?>(null) }
    var reactionToken by remember(videoUrl) { mutableIntStateOf(0) }
    var playbackView by remember(videoUrl) { mutableStateOf<PlayerView?>(null) }

    suspend fun showReaction(name: String) {
        val token = ++reactionToken
        if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            val end = videoBeat(reaction ?: action).endMs
            delay((end - player.currentPosition).coerceIn(0L, 8_000L))
        }
        if (reactionToken != token) return
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
                .also { it.player = player;(it.videoSurfaceView as? TextureView)?.isOpaque=false }
        },
        update = { view ->
            view.player=player
            view.alpha=if(keyColor==null || !lightReady)0f else 1f
            playbackView=view
        },
        onRelease = { if(playbackView===it)playbackView=null },
    )
    LaunchedEffect(player,keyColor,lightReady,playbackView) {
        val key=keyColor;val view=playbackView
        if(key!=null && lightReady && view!=null) {
            applyBackgroundKey(view,key,lightBinding,conservative = fullClip)
            player.setMediaItem(MediaItem.fromUri(uri));player.prepare()
        }
    }
    LaunchedEffect(player, keyColor, lightReady, playbackView, videoUrl, lighting) {
        val key = keyColor ?: return@LaunchedEffect
        val view = playbackView ?: return@LaunchedEffect
        val input = lighting ?: return@LaunchedEffect
        if (!lightReady || lightBinding != null) return@LaunchedEffect
        val pack = awaitVideoLighting(context, videoUrl) ?: return@LaunchedEffect
        lightBinding = VideoLightingBinding(pack, input)
        applyBackgroundKey(view, key, lightBinding, conservative = fullClip)
    }

    LaunchedEffect(player, readySerial, activeBeat, playbackAllowed) {
        if (!playbackAllowed) { player.pause(); return@LaunchedEffect }
        if (player.playbackState != Player.STATE_READY) return@LaunchedEffect
        if (fullClip) {
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.play()
            return@LaunchedEffect
        }
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

    DisposableEffect(player) { onDispose { releaseMascotVideoPlayer(player) } }
}
