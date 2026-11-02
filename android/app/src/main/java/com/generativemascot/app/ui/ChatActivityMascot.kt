package com.generativemascot.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Keep the previous player visible until its replacement actually renders a frame. */
@Composable
internal fun ChatActivityMascot(
    activities: Map<String, String>, active: Boolean, busy: Boolean, reactionUrl: String?,
    onReactionVisible: (String) -> Unit, onReactionError: () -> Unit,
    fallback: @Composable () -> Unit,
) {
    val foreground = rememberAppResumed()
    val idles = remember(activities) { listOf("idle-look", "idle-stretch", "idle-sway", "idle-ponder").mapNotNull(activities::get) }
    var current by remember(activities) { mutableStateOf<String?>(null) }
    var pending by remember(activities) { mutableStateOf<String?>(null) }
    var pendingReady by remember(activities) { mutableStateOf(false) }
    var idleIndex by remember(activities) { mutableIntStateOf(0) }
    val latestVisible by rememberUpdatedState(onReactionVisible)
    val latestError by rememberUpdatedState(onReactionError)
    fun request(url: String?) {
        if (url == null || url == pending) return
        if (url == current) { pending = null; pendingReady = false; return }
        pendingReady = false
        pending = url
    }
    LaunchedEffect(busy, reactionUrl, activities, current) {
        if (isStaleChatReaction(pending, reactionUrl, activities.values)) {
            pending = null
            pendingReady = false
        }
        request(chatActivityTarget(busy, reactionUrl, current, idles, activities["thinking"]))
    }
    LaunchedEffect(current, active, foreground, busy, reactionUrl) {
        if (!active || !foreground || busy || reactionUrl != null || current == null || idles.isEmpty()) return@LaunchedEffect
        delay(8_000)
        idleIndex = if (current in idles) (idles.indexOf(current) + 1) % idles.size else 0
        request(idles[idleIndex])
    }
    LaunchedEffect(pending, pendingReady) {
        val next = pending ?: return@LaunchedEffect
        if (!pendingReady) return@LaunchedEffect
        delay(240)
        current = next
        pending = null
        pendingReady = false
        if (next == reactionUrl) latestVisible(next)
    }
    LaunchedEffect(pending, pendingReady, active, foreground) {
        val candidate = pending ?: return@LaunchedEffect
        if (pendingReady || !foreground || (!active && current != null)) return@LaunchedEffect
        delay(15_000)
        if (pending == candidate && !pendingReady) {
            pending = null
            if (candidate == reactionUrl) latestError()
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (current == null) fallback()
        listOfNotNull(current, pending).distinct().forEach { url ->
            key(url) {
                val alpha by animateFloatAsState(
                    if (url == current || pendingReady) 1f else .001f,
                    animationSpec = tween(220), label = "chat mascot crossfade",
                )
                SoraMascotVideo(videoUrl = url, action = "idle", fullClip = true,
                    playbackEnabled = active || current == null,
                    onAnimationVisible = { if (url == pending) pendingReady = true },
                    onPlaybackError = {
                        if (url == pending) {
                            pending = null; pendingReady = false
                            if (url == reactionUrl) latestError()
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(10.dp).graphicsLayer {
                        // New chat clips have a padded reference; the existing thinking clip does not.
                        val scale = if (url == activities["thinking"]) 1f else 1f / .75f
                        scaleX = scale; scaleY = scale; this.alpha = alpha
                    })
            }
        }
    }
}
