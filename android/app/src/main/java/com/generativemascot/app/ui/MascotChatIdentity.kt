package com.generativemascot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.core.content.res.ResourcesCompat
import com.generativemascot.app.R

internal val MascotChatFigureSize = 288.dp
internal val MascotChatFigureStart = (-18).dp
internal val MascotChatIdentitySize = 136.dp
internal val MascotChatIdentityStart = 20.dp
internal val MascotChatIdentityTop = 29.dp

internal data class ChatFaceFraming(
    val videoCenter: Float,
    val videoScale: Float,
    val stillCenter: Float,
    val stillScale: Float,
    val videoX: Float = .5f,
    val stillX: Float = .5f,
)

// Bundled clips use different framing from their reference stills.
internal fun chatFaceFraming(heroId: String): ChatFaceFraming = when (heroId) {
    "300392a5-3351-4add-9e22-7a21a56f4d1a" -> ChatFaceFraming(.30f, 6.2f, .15f, 4f) // Петя
    "716cdb43-703b-4d7e-8a79-27f03d92b99c" -> ChatFaceFraming(.37f, 4.6f, .28f, 3.1f) // Уна
    "9f226b89-df35-4d3a-9b36-96c28ea1bb62" -> ChatFaceFraming(.27f, 4.4f, .28f, 4.2f) // Аким
    "a83e9d30-1270-40e6-902c-b44774e84c17" -> ChatFaceFraming(.38f, 4.6f, .37f, 4.2f) // Марс
    "de7d1775-d875-4db0-88c0-68833e4edae1" -> ChatFaceFraming(.36f, 3.25f, .29f, 2.65f) // Грин
    "6dccb53c-6086-4e07-b387-a36d2335844d" -> ChatFaceFraming(.37f, 3.45f, .285f, 2.3f) // Рико
    else -> ChatFaceFraming(.31f, 4.5f, .26f, 3.3f)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MascotChatIdentity(
    name: String,
    modifier: Modifier,
    circleBackground: Color,
    expansion: Float,
    onClickLabel: String,
    onLongClick: (() -> Unit)?,
    overlay: @Composable BoxScope.() -> Unit,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(percent = (50 * expansion).toInt())
        Box(
            Modifier.fillMaxSize().clip(shape)
                .background(circleBackground.copy(alpha = expansion))
                .border(1.dp, Color.White.copy(alpha = expansion), shape),
            contentAlignment = Alignment.Center,
        ) {
            content()
            overlay()
            if (expansion > 0f) ChatPortraitName(name, circleBackground, expansion)
        }
        Box(Modifier.fillMaxWidth(.45f + .55f * expansion)
            .fillMaxHeight(.72f + .28f * expansion)
            .semantics { contentDescription = name }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = onClickLabel,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Показать лицо персонажа" else null,
            ))
    }
}

@Composable
private fun ChatPortraitName(name: String, background: Color, alpha: Float) {
    val context = LocalContext.current
    val typeface = remember(context) { ResourcesCompat.getFont(context, R.font.sb_sans_display_extended_semibold) }
    Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        val radius = size.minDimension * .35f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val arc = android.graphics.Path().apply {
            addArc(cx - radius, cy - radius, cx + radius, cy + radius, -140f, 100f)
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.Black.toArgb()
            this.typeface = typeface
            textSize = 12.sp.toPx()
        }
        val available = android.graphics.PathMeasure(arc, false).length
        val fitted = android.text.TextUtils.ellipsize(name, android.text.TextPaint(paint), available,
            android.text.TextUtils.TruncateAt.END).toString()
        val start = ((available - paint.measureText(fitted)) / 2f).coerceAtLeast(0f)
        val outline = android.graphics.Paint(paint).apply {
            color = background.toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3.dp.toPx()
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        drawContext.canvas.nativeCanvas.drawTextOnPath(fitted, arc, start, 0f, outline)
        drawContext.canvas.nativeCanvas.drawTextOnPath(fitted, arc, start, 0f, paint)
    }
}
