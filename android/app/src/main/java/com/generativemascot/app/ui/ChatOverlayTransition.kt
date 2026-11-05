package com.generativemascot.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Keeps the chat composed while it moves in and out, preserving its state. */
@Composable
internal fun ChatOverlayTransition(visible: Boolean, content: @Composable (active: Boolean) -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 220 else 180, easing = FastOutSlowInEasing),
        label = "chat transition",
    )
    val active = visible || progress > 0f
    Box(Modifier.fillMaxSize()
        .zIndex(if (active) 1f else -1f)
        .graphicsLayer { alpha = progress; translationY = (1f - progress) * 16.dp.toPx() }
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics { })) {
        content(active)
    }
}
