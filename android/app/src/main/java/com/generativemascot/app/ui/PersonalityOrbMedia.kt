package com.generativemascot.app.ui

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.generativemascot.app.R
import kotlinx.coroutines.delay

/** The supplied artwork is keyed by personality ID, never by list position. */
internal data class PersonalityOrbAsset(@DrawableRes val image: Int, @RawRes val video: Int? = null)
internal val personalityOrbAssets = mapOf(
    "neutral" to PersonalityOrbAsset(R.drawable.personality_neutral, R.raw.personality_neutral_motion),
    "direct" to PersonalityOrbAsset(R.drawable.personality_direct, R.raw.personality_direct_motion),
    "friendly" to PersonalityOrbAsset(R.drawable.personality_friendly, R.raw.personality_friendly_motion),
    "concise" to PersonalityOrbAsset(R.drawable.personality_concise, R.raw.personality_concise_motion),
    "expert" to PersonalityOrbAsset(R.drawable.personality_expert),
    "socratic" to PersonalityOrbAsset(R.drawable.personality_socratic),
    "strategic" to PersonalityOrbAsset(R.drawable.personality_strategic),
    "mentor" to PersonalityOrbAsset(R.drawable.personality_mentor),
    "skeptic" to PersonalityOrbAsset(R.drawable.personality_skeptic),
    "humorous" to PersonalityOrbAsset(R.drawable.personality_humorous),
    "executive" to PersonalityOrbAsset(R.drawable.personality_executive, R.raw.personality_executive_motion),
    "intellectual" to PersonalityOrbAsset(R.drawable.personality_intellectual),
)

/** One muted player for the selected, settled row; the still stays underneath until a frame is ready. */
internal class PersonalityOrbPlayer(context: Context) {
    private val appContext = context.applicationContext
    val player = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 0f
        trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
    }
    var activeId by mutableStateOf<String?>(null)
        private set
    var visibleId by mutableStateOf<String?>(null)
        private set
    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            val current = player.currentMediaItem?.mediaId
            if (current != null && current == activeId) visibleId = current
        }
        override fun onPlayerError(error: PlaybackException) { visibleId = null }
    }
    init { player.addListener(listener) }
    fun clear() {
        visibleId = null
        activeId = null
        player.stop()
        player.clearMediaItems()
    }
    fun start(id: String, @RawRes video: Int) {
        clear()
        activeId = id
        player.setMediaItem(MediaItem.Builder().setMediaId(id).setUri(Uri.parse("android.resource://${appContext.packageName}/$video")).build())
        player.prepare()
        player.playWhenReady = true
    }
    fun release() { player.removeListener(listener); player.release() }
}

@Composable
internal fun rememberPersonalityOrbPlayer(selectedId: String, scrolling: Boolean): PersonalityOrbPlayer {
    val context = LocalContext.current
    val resumed = rememberAppResumed()
    val controller = remember(context) { PersonalityOrbPlayer(context) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    LaunchedEffect(controller, selectedId, scrolling, resumed) {
        controller.clear()
        val video = personalityOrbAssets[selectedId]?.video
        if (resumed && !scrolling && video != null) {
            delay(180)
            controller.start(selectedId, video)
        }
    }
    return controller
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PersonalityOrb(styleId: String, selected: Boolean, controller: PersonalityOrbPlayer, modifier: Modifier = Modifier) {
    val asset = personalityOrbAssets.getValue(styleId)
    val alpha by animateFloatAsState(if (selected && controller.visibleId == styleId) 1f else 0f,
        tween(150), label = "orb first frame")
    Box(modifier.clip(CircleShape)) {
        Image(painterResource(asset.image), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (selected && controller.activeId == styleId && asset.video != null) {
            AndroidView(modifier = Modifier.fillMaxSize().alpha(alpha), factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.mascot_video_player, null, false) as PlayerView).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    player = controller.player
                }
            }, update = { if (it.player !== controller.player) it.player = controller.player }, onRelease = { it.player = null })
        }
    }
}
