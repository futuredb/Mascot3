package com.generativemascot.app.overlay

import android.content.Context
import kotlin.math.roundToInt

internal data class OverlayDimensions(val width: Int, val height: Int)

internal object OverlaySize {
    const val PREFERENCES = "mascot_overlay"
    const val KEY = "hero_scale"
    const val MIN = .7f
    const val MAX = 1.5f

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

    fun dimensions(scale: Float, density: Float, area: OverlayArea, artwork: OverlayArtwork? = null): OverlayDimensions {
        val (width, height) = OverlayFraming.baseSize(artwork)
        val factor = minOf(constrain(scale) * density, area.width / width, area.height / height)
        return OverlayDimensions((width * factor).roundToInt().coerceIn(1, area.width),
            (height * factor).roundToInt().coerceIn(1, area.height))
    }

    /** Keep the feet and nearest side in place; only move if a display edge requires it. */
    fun reposition(area: OverlayArea, point: OverlayPoint, old: OverlayDimensions, size: OverlayDimensions): OverlayPoint {
        val onRight = point.x + old.width / 2 >= area.left + area.width / 2
        return OverlayLayout.clamp(area, size.width, size.height,
            if (onRight) point.x + old.width - size.width else point.x,
            point.y + old.height - size.height)
    }
}
