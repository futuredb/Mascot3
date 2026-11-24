package com.generativemascot.app.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

internal val MascotEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
internal fun rememberMascotPortraitBob(): State<Float> {
    if (!ValueAnimator.areAnimatorsEnabled()) return remember { mutableFloatStateOf(0f) }
    val motion = rememberInfiniteTransition(label = "portrait")
    val ease = CubicBezierEasing(.42f, 0f, .58f, 1f)
    return motion.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 4_000
        0f at 0 using ease
        -5.625f at 1_600 using ease
        0f at 3_200
        0f at 4_000
    }), label = "portrait bob")
}

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
            fadeIn(tween(180)).togetherWith(fadeOut(tween(180))).using(null)
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
