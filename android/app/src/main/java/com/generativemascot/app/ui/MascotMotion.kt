package com.generativemascot.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

internal val MascotEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** One timing vocabulary for route, hero and action changes. Keys exclude polling updates. */
@Composable
internal fun <T> MascotTransition(
    target: T,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any? = { it },
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        contentKey = contentKey,
        transitionSpec = {
            (fadeIn(tween(200, delayMillis = 80)) +
                slideInVertically(tween(280, easing = MascotEase)) { it / 40 })
                .togetherWith(
                    fadeOut(tween(100)) +
                        slideOutVertically(tween(180, easing = MascotEase)) { -it / 80 },
                ).using(null)
        },
        label = "mascot navigation",
    ) { displayed -> content(displayed) }
}

internal fun Modifier.mascotClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        tween(130, easing = MascotEase),
        label = "press response",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}
