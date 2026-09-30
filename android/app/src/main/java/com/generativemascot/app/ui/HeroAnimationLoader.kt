package com.generativemascot.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Lightweight orbit occupies the hero slot; no still/video is mounted beneath it. */
@Composable
internal fun HeroAnimationLoader(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "hero awakening")
    val pulse by transition.animateFloat(
        initialValue = .96f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orbit pulse",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "orbit rotation",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(96.dp).graphicsLayer {
            scaleX = pulse
            scaleY = pulse
        }.testTag("home-animation-loader").semantics {
            contentDescription = "Готовим анимацию героя"
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            liveRegion = LiveRegionMode.Polite
        }) {
            val radius = 36.dp.toPx()
            val topLeft = center - Offset(radius, radius)
            val diameter = Size(radius * 2, radius * 2)
            drawCircle(Color.White.copy(alpha = .12f), radius = radius, style = Stroke(3.dp.toPx()))
            rotate(rotation) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        .02f to Color.Transparent,
                        .45f to Color.White.copy(alpha = .18f),
                        .8333333f to Color.White.copy(alpha = .95f),
                        .89f to Color.Transparent,
                        1f to Color.Transparent,
                        center = center,
                    ),
                    startAngle = 0f, sweepAngle = 300f, useCenter = false,
                    topLeft = topLeft, size = diameter,
                    style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                )
                // A bright orbiting tip makes the otherwise quiet ring feel alive.
                drawCircle(Color.White, radius = 3.5.dp.toPx(),
                    center = center + Offset(radius * .5f, -radius * .8660254f))
            }
        }
    }
}
