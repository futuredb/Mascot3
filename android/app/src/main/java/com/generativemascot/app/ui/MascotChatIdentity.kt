package com.generativemascot.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.lerp
import androidx.core.content.res.ResourcesCompat
import com.generativemascot.app.R

internal val MascotChatIdentitySize = 136.dp
internal val MascotChatPortraitSize = 110.dp
internal val MascotChatIdentityTop = 29.dp

/** The same borderless portrait and Figma typeface in chat and interaction settings. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MascotChatIdentity(name: String, modifier: Modifier = Modifier, circleBackground: Color? = null,
    onClick: (() -> Unit)? = null, showName: Boolean = true, onClickLabel: String = "Настроить общение",
    loading: Boolean = false,
    expansion: Float = 0f,
    onLongClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val typeface = remember { ResourcesCompat.getFont(context, R.font.sb_sans_display_extended_semibold) }
    Box(modifier) {
        val circleAlpha = 1f - expansion
        val portrait = if (circleBackground != null) {
            val shape = RoundedCornerShape(percent = (50 * circleAlpha).toInt())
            Modifier.fillMaxSize().clip(shape)
                .background(circleBackground.copy(alpha = circleAlpha))
                .border(1.dp, Color.White.copy(alpha = circleAlpha), shape)
        } else Modifier.fillMaxSize().clipToBounds()
        BoxWithConstraints(portrait, contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(
                    lerp(MascotChatPortraitSize, maxWidth, expansion),
                    lerp(MascotChatPortraitSize, maxHeight, expansion),
                ),
                contentAlignment = Alignment.Center,
                content = content,
            )
            overlay()
        }
        if (onClick != null) Box(Modifier.matchParentSize().combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = onClickLabel,
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = if (onLongClick != null) "Увеличить персонажа" else null,
        ))
        if (showName && circleAlpha > 0f) Canvas(Modifier.fillMaxSize()
            .graphicsLayer { alpha = circleAlpha }
            .semantics { contentDescription = name }) {
            // Keep the text away from the edge while retaining room for the portrait.
            val radius = size.minDimension * .35f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val arc = android.graphics.Path().apply {
                addArc(cx - radius, cy - radius, cx + radius, cy + radius, -140f, 100f)
            }
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                this.typeface = typeface
                textSize = 12.sp.toPx()
                textAlign = android.graphics.Paint.Align.LEFT
            }
            val available = android.graphics.PathMeasure(arc, false).length
            val fittedName = android.text.TextUtils.ellipsize(name, android.text.TextPaint(paint),
                available, android.text.TextUtils.TruncateAt.END).toString()
            val startOffset = ((available - paint.measureText(fittedName)) / 2f).coerceAtLeast(0f)
            // A subtle background-colored outline keeps the name legible over moving video.
            if (circleBackground != null) {
                val outline = android.graphics.Paint(paint).apply {
                    color = circleBackground.toArgb()
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 3.dp.toPx()
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
                drawContext.canvas.nativeCanvas.drawTextOnPath(fittedName, arc, startOffset, 0f, outline)
            }
            drawContext.canvas.nativeCanvas.drawTextOnPath(fittedName, arc, startOffset, 0f, paint)
        }
    }
}
