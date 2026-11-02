package com.generativemascot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.generativemascot.app.physics.HeroSpace
import com.generativemascot.app.physics.PhonePose

@Composable
internal fun HeroSpaceGrid(palette: HeroPalette, pose: () -> PhonePose, modifier: Modifier = Modifier) {
    val ink = if (palette.middle.luminance() < .36f) Color.White else Color(0xFF425A85)
    Canvas(modifier) {
        val current = pose()
        val heroHeight = size.height - 24.dp.toPx()
        val artworkSize = minOf(size.width - 48.dp.toPx(), heroHeight).coerceAtLeast(1f)
        val ground = (heroHeight * .5f + artworkSize * .35f) / size.height
        clipRect {
            HeroSpace.floor(current, ground).forEach { line ->
                drawLine(ink.copy(alpha = line.alpha),
                    Offset(line.x1 * size.width, line.y1 * size.height),
                    Offset(line.x2 * size.width, line.y2 * size.height),
                    strokeWidth = .8.dp.toPx())
            }
            // The shadow stays on the floor when the character hops. Its small
            // lateral drift ties the moving subject to the stationary grid.
            val hop = current.bounce.coerceAtLeast(0f)
            val center = Offset(size.width * .5f + current.x * 18.dp.toPx(), size.height * ground + current.y * 5.dp.toPx())
            val radius = size.width * (.20f - hop * .035f)
            scale(1f, 10.dp.toPx() / radius, center) {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF172644).copy(alpha = .17f - hop * .07f), Color.Transparent), center, radius),
                    radius = radius, center = center)
            }
        }
    }
}
