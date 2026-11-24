package com.generativemascot.app.overlay

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Limits follow the visible body, not transparent padding in a generated video. */
internal class OverlayPlacement(
    private val area: OverlayArea,
    private val size: OverlayDimensions,
    private val body: RectF,
    motionMargin: Int = 0,
) {
    private val left = (body.left - motionMargin).coerceAtLeast(0f)
    private val right = (body.right + motionMargin).coerceAtMost(size.width.toFloat())
    private val minX = ceil(area.left - left).toInt()
    private val maxX = floor(area.right - right).toInt().coerceAtLeast(minX)
    private val top = (body.top - motionMargin).coerceAtLeast(0f)
    private val bottom = (body.bottom + motionMargin).coerceAtMost(size.height.toFloat())
    private val minY = ceil(area.top - top).toInt()
    private val maxY = floor(area.bottom - bottom).toInt().coerceAtLeast(minY)

    fun clamp(x: Int, y: Int) = OverlayPoint(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))

    fun bodyCenter(point: OverlayPoint) = PointF(point.x + body.centerX(), point.y + body.centerY())

    fun centeredOn(center: PointF) = clamp((center.x - body.centerX()).roundToInt(),
        (center.y - body.centerY()).roundToInt())

    fun restore(x: Float, y: Float): OverlayPoint {
        fun fraction(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else .5f
        return clamp(minX + ((maxX - minX) * fraction(x)).roundToInt(),
            minY + ((maxY - minY) * fraction(y)).roundToInt())
    }

    fun fractions(point: OverlayPoint) = Pair(
        ((point.x - minX).toFloat() / (maxX - minX).coerceAtLeast(1)).coerceIn(0f, 1f),
        ((point.y - minY).toFloat() / (maxY - minY).coerceAtLeast(1)).coerceIn(0f, 1f),
    )

    /** Resizing and folding keep the nearest visible side and the window's baseline. */
    fun reposition(point: OverlayPoint, previous: OverlayPlacement): OverlayPoint {
        val onRight = point.x + previous.body.centerX() >= area.left + area.width / 2f
        val x = if (onRight) point.x + previous.body.right - body.right
            else point.x + previous.body.left - body.left
        return clamp(x.roundToInt(), point.y + previous.size.height - size.height)
    }

    companion object {
        fun hero(area: OverlayArea, size: OverlayDimensions, art: OverlayArtwork?, margin: Int) =
            OverlayPlacement(area, size, art?.let { OverlayFraming.visibleBounds(it, size.width, size.height) }
                ?: RectF(0f, 0f, size.width.toFloat(), size.height.toFloat()), margin)

        fun window(area: OverlayArea, size: OverlayDimensions) =
            OverlayPlacement(area, size, RectF(0f, 0f, size.width.toFloat(), size.height.toFloat()))
    }
}
