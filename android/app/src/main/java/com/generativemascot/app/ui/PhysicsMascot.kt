package com.generativemascot.app.ui

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.LayoutInflater
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.generativemascot.app.R
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.physics.InstalledPhysics
import com.generativemascot.app.physics.PhysicsStore
import com.generativemascot.app.physics.PhysicsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private enum class PhysicsPhase { IDLE, SETTLING, VIDEO, RETURNING }

@Composable
fun PhysicsMascot(
    mascotId: String, videoUrls: Map<String, String>, neutralFrameUrl: String?,
    action: String, interaction: PuppetInteraction, modifier: Modifier = Modifier,
    onAnimationVisible: ((String) -> Unit)?, onOneShotFinished: ((String) -> Unit)?,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { PhysicsStore(context) }
    var installed by remember(mascotId) { mutableStateOf<InstalledPhysics?>(null) }
    var status by remember(mascotId) { mutableStateOf<String?>(null) }
    var failure by remember(mascotId) { mutableStateOf<String?>(null) }
    LaunchedEffect(mascotId) {
        var revision: String? = null
        while (isActive) {
            val next = withContext(Dispatchers.IO) { store.revision(mascotId) }
            if (next != revision) {
                revision = next
                withContext(Dispatchers.IO) { runCatching { store.load(mascotId) } }
                    .onSuccess { installed = it; failure = null }.onFailure { installed = null; failure = it.message }
            }
            status = store.status(mascotId)
            delay(2000)
        }
    }
    Box(modifier) {
        val pack = installed
        if (pack != null && failure == null) {
            key(mascotId, pack.directory.name) {
                PhysicsPlayback(pack, videoUrls, neutralFrameUrl, action, interaction, onAnimationVisible, onOneShotFinished, { failure = it })
            }
        } else fallback()
        if (mascotId == PhysicsStore.PILOT_ID && (pack == null || failure != null)) {
            Column(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                (failure ?: status)?.let { Text(it, fontSize = 12.sp) }
                TextButton(onClick = {
                    runCatching { store.requestPreparation(mascotId) }.onFailure { failure = it.message }
                }, enabled = status != "Готовим физику…") { Text("Подготовить физику", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun PhysicsPlayback(
    installed: InstalledPhysics, videoUrls: Map<String, String>, neutralFrameUrl: String?, action: String,
    interaction: PuppetInteraction, visible: ((String) -> Unit)?, finished: ((String) -> Unit)?, failed: (String) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    var phase by remember { mutableStateOf(PhysicsPhase.IDLE) }
    var pending by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var videoVisible by remember { mutableStateOf(false) }
    var waking by remember { mutableStateOf(false) }
    var settleToken by remember { mutableIntStateOf(0) }
    var playbackSerial by remember { mutableIntStateOf(0) }
    var physicsView by remember { mutableStateOf<PhysicsView?>(null) }
    var calibrated by remember { mutableStateOf(false) }
    LaunchedEffect(physicsView, foreground) {
        while (foreground && isActive) {
            calibrated = physicsView?.calibrated == true
            delay(100)
        }
    }
    val urls = remember(videoUrls) { videoUrls.mapKeys { HeroLocalStore.normalizeVideoAction(it.key) } }
    val visibleCallback by rememberUpdatedState(visible)
    val finishedCallback by rememberUpdatedState(finished)
    val failureCallback by rememberUpdatedState(failed)
    fun request(raw: String) {
        val next = HeroLocalStore.normalizeVideoAction(raw)
        if (playing == "sleeping" && next !in setOf("sleeping", "sleep_loop")) {
            waking = true; pending = next.takeIf { it != "idle" && it in urls }; return
        }
        if (next == "idle" || next !in urls || next == pending || (next == playing && phase == PhysicsPhase.VIDEO)) return
        pending = next
        if (phase == PhysicsPhase.IDLE) { phase = PhysicsPhase.SETTLING; settleToken++ }
    }
    LaunchedEffect(action) { request(action) }
    LaunchedEffect(interaction.actionSerial) { if (interaction.actionSerial > 0) interaction.actionName?.let(::request) }
    LaunchedEffect(interaction.tapSerial) { if (interaction.tapSerial > 0) request("joyful") }
    LaunchedEffect(interaction.petSerial) { if (interaction.petSerial > 0) request("joyful") }
    LaunchedEffect(interaction.playSerial) { if (interaction.playSerial > 0) request("joyful") }
    LaunchedEffect(interaction.sleepSerial) { if (interaction.sleepSerial > 0) request("sleeping") }
    LaunchedEffect(interaction.spinSerial) { if (interaction.spinSerial > 0) request("dancing") }
    LaunchedEffect(interaction.danceSerial) { if (interaction.danceSerial > 0) request("dancing") }
    fun ended() { phase = PhysicsPhase.RETURNING; settleToken++ }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            // TextureView must be consumed while the neutral frame is prepared.
            // An alpha-zero parent can stop SurfaceTexture consumption, blocking
            // eglSwapBuffers and the callback that is meant to reveal physics.
            // The final video frame remains above it until onSettled fires.
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (videoVisible && phase == PhysicsPhase.VIDEO) 0f else 1f },
            factory = { ctx -> PhysicsView(ctx, installed).also { view ->
                physicsView = view
                if (!view.sensorsAvailable) failureCallback("На устройстве недоступны датчики движения")
            } },
            update = { view ->
                view.neutral = phase != PhysicsPhase.IDLE
                view.settleToken = settleToken
                view.onFailure = failureCallback
                view.onReady = { if (phase == PhysicsPhase.IDLE) visibleCallback?.invoke("idle") }
                view.onSettled = {
                    when (phase) {
                        PhysicsPhase.SETTLING, PhysicsPhase.RETURNING -> {
                            val next = pending; pending = null
                            playing = next; waking = false; videoVisible = false; playbackSerial++
                            phase = if (next == null) PhysicsPhase.IDLE else PhysicsPhase.VIDEO
                            if (next == null) visibleCallback?.invoke("idle")
                        }
                        else -> Unit
                    }
                }
                if (foreground && (phase != PhysicsPhase.VIDEO || !videoVisible)) view.resume() else view.pause()
            },
            onRelease = { it.pause(); if (physicsView === it) physicsView = null },
        )
        val current = playing
        if (current != null) {
            key(current, playbackSerial) {
                val videoModifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (videoVisible) 1f else 0f }
                if (current == "sleeping") {
                    SoraMascotVideo(videoUrls = urls, neutralFrameUrl = neutralFrameUrl,
                        action = if (waking) "idle" else "sleeping", modifier = videoModifier,
                        onAnimationVisible = { name ->
                            if (waking && name == "idle") ended()
                            else { videoVisible = true; visibleCallback?.invoke(name) }
                        })
                } else {
                    PhysicsActionVideo(urls.getValue(current), neutralFrameUrl, foreground && phase == PhysicsPhase.VIDEO, videoModifier,
                        onVisible = { videoVisible = true; visibleCallback?.invoke(current) },
                        onEnded = { if (current in setOf("greeting", "signature")) finishedCallback?.invoke(current); ended() }, onFailure = failureCallback)
                }
            }
        }
        if (phase == PhysicsPhase.IDLE && foreground) {
            Column(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!calibrated) Text("Подержите телефон неподвижно", fontSize = 12.sp)
                TextButton(onClick = { physicsView?.recalibrate(); calibrated = false }, enabled = calibrated) {
                    Text("Выровнять по телефону", fontSize = 12.sp)
                }
            }
        }
    }
}

/** One complete authored action. The last decoded frame remains until physics is ready. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun PhysicsActionVideo(url: String, neutralFrameUrl: String?, running: Boolean, modifier: Modifier, onVisible: () -> Unit, onEnded: () -> Unit, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val visible by rememberUpdatedState(onVisible); val ended by rememberUpdatedState(onEnded); val failed by rememberUpdatedState(onFailure)
    val player = remember(url) { ExoPlayer.Builder(context).build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_OFF } }
    var playbackView by remember(url) { mutableStateOf<PlayerView?>(null) }
    var keyColor by remember(url) { mutableStateOf<Int?>(null) }
    LaunchedEffect(url,neutralFrameUrl) { keyColor = withContext(Dispatchers.IO) { detectBackgroundColor(neutralFrameUrl ?: url) } }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { visible() }
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) ended() }
            override fun onPlayerError(error: PlaybackException) { failed("Не удалось воспроизвести действие") }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    AndroidView(modifier = modifier, factory = { ctx ->
        (LayoutInflater.from(ctx).inflate(R.layout.mascot_video_player, FrameLayout(ctx), false) as PlayerView).apply {
            this.player = player; setKeepContentOnPlayerReset(true); setShutterBackgroundColor(AndroidColor.TRANSPARENT)
            (videoSurfaceView as? TextureView)?.isOpaque = false
        }.also { playbackView=it }
    })
    LaunchedEffect(player, keyColor, playbackView) {
        val key=keyColor;val view=playbackView
        if (key != null && view != null) {
            // Video effects must be installed before prepare. AndroidView's
            // asynchronous update can otherwise lose the first-clip race.
            applyBackgroundKey(view,key)
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url.substringBefore('?')))); player.prepare()
        }
    }
    LaunchedEffect(player, running, keyColor) { player.playWhenReady = running && keyColor != null }
}
