package com.generativemascot.app.overlay

import android.content.Context
import kotlin.math.roundToInt

internal data class OverlayDimensions(val width: Int, val height: Int)

internal object OverlaySize {
    const val PREFERENCES = "mascot_overlay"
    const val KEY = "hero_scale"
    const val MIN = .7f
    const val MAX = 1.2f

    fun constrain(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN, MAX) else 1f

    fun normalize(value: Float): Float =
        (constrain(value) * 20).roundToInt() / 20f

    fun read(context: Context): Float = constrain(runCatching {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getFloat(KEY, 1f)
    }.getOrDefault(1f))

    fun save(context: Context, value: Float) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putFloat(KEY, constrain(value)).apply()
    }

    /** Limit the visible composition, not the transparent video canvas. */
    fun effectiveScale(scale: Float, density: Float, area: OverlayArea, artwork: OverlayArtwork? = null): Float {
        val requested = constrain(scale)
        val (width, height) = OverlayFraming.baseSize(artwork)
        // Video windows have the source aspect ratio; measure their body at the
        // actual base aspect ratio instead of assuming a square canvas.
        val baseBody = artwork?.let { OverlayFraming.visibleBounds(it, width.roundToInt(), height.roundToInt()) }
        val visibleWidth = baseBody?.width() ?: width
        val visibleHeight = baseBody?.height() ?: height
        // The dialog card has its own width constraint. It must not silently cap
        // the hero at 100%, especially for wide characters such as cats.
        return minOf(requested, area.width / (visibleWidth * density),
            area.height / (visibleHeight * density)).coerceAtLeast(.01f)
    }

    fun dimensions(scale: Float, density: Float, area: OverlayArea, artwork: OverlayArtwork? = null): OverlayDimensions {
        val (width, height) = OverlayFraming.baseSize(artwork)
        val factor = if (artwork == null) minOf(constrain(scale) * density, area.width / width, area.height / height)
            else effectiveScale(scale, density, area, artwork) * density
        return OverlayDimensions((width * factor).roundToInt().coerceAtLeast(1),
            (height * factor).roundToInt().coerceAtLeast(1))
    }

    /** Keep the feet and nearest side in place; only move if a display edge requires it. */
    fun reposition(area: OverlayArea, point: OverlayPoint, old: OverlayDimensions, size: OverlayDimensions): OverlayPoint {
        val onRight = point.x + old.width / 2 >= area.left + area.width / 2
        return OverlayLayout.clamp(area, size.width, size.height,
            if (onRight) point.x + old.width - size.width else point.x,
            point.y + old.height - size.height)
    }
}
