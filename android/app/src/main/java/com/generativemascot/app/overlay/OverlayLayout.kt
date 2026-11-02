package com.generativemascot.app.overlay

import kotlin.math.roundToInt

data class OverlayArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = (right - left).coerceAtLeast(1)
    val height get() = (bottom - top).coerceAtLeast(1)
}

data class OverlayPoint(val x: Int, val y: Int)

/** Hint and compact windows stay inside the display; the hero uses visible-body limits. */
object OverlayLayout {
    fun clamp(area: OverlayArea, width: Int, height: Int, x: Int, y: Int) = OverlayPoint(
        x.coerceIn(area.left, maxOf(area.left, area.right - width)),
        y.coerceIn(area.top, maxOf(area.top, area.bottom - height)),
    )

    fun restore(area: OverlayArea, width: Int, height: Int, x: Float, y: Float): OverlayPoint {
        fun fraction(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else .5f
        return clamp(area, width, height,
            area.left + ((area.width - width).coerceAtLeast(0) * fraction(x)).roundToInt(),
            area.top + ((area.height - height).coerceAtLeast(0) * fraction(y)).roundToInt())
    }

    fun fractions(area: OverlayArea, width: Int, height: Int, point: OverlayPoint) = Pair(
        ((point.x - area.left).toFloat() / (area.width - width).coerceAtLeast(1)).coerceIn(0f, 1f),
        ((point.y - area.top).toFloat() / (area.height - height).coerceAtLeast(1)).coerceIn(0f, 1f),
    )

    fun bubble(area: OverlayArea, hero: OverlayPoint, heroWidth: Int, bubbleWidth: Int,
        bubbleHeight: Int, gap: Int, anchorY: Int? = null, tailInset: Int = 0, tailHeight: Int = 0,
        toRight: Boolean = hero.x + heroWidth / 2 < area.left + area.width / 2): OverlayPoint {
        return clamp(area, bubbleWidth, bubbleHeight,
            hero.x + heroWidth / 2 - bubbleWidth / 2,
            if (anchorY != null) hero.y + anchorY - tailHeight
            else hero.y - bubbleHeight / 3)
    }
}

data class OverlayHint(val category: String, val title: String, val detail: String)

/** Deliberately local examples, never inferred from another app or submitted to a provider. */
val overlayDemoHints = listOf(
    OverlayHint("Накопления", "Отложим с зарплаты\n89 000 ₽ на квартиру?", ""),
    OverlayHint("Перерыв", "Давай на минутку выдохнем", "Небольшая пауза среди дел"),
    OverlayHint("Прогулка", "Может, пройдёмся?", "Можно начать с короткого маршрута"),
)
