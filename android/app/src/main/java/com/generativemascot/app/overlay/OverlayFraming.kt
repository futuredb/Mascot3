package com.generativemascot.app.overlay

import android.graphics.Rect
import android.graphics.RectF

/** Fit the entire video canvas, including poses wider than its first frame. */
internal object OverlayFraming {
    const val CONTENT_FRACTION = .8f

    fun source(artwork: OverlayArtwork): Rect = if (artwork.video != null)
        Rect(0, 0, artwork.frames.first().width, artwork.frames.first().height)
    else Rect(artwork.bounds)

    fun destination(artwork: OverlayArtwork, width: Int, height: Int): RectF {
        val source = source(artwork)
        val scale = minOf(width * CONTENT_FRACTION / source.width(), height * CONTENT_FRACTION / source.height())
        val w = source.width() * scale
        val h = source.height() * scale
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    fun visibleBounds(artwork: OverlayArtwork, width: Int, height: Int): RectF {
        val source = source(artwork)
        val frame = destination(artwork, width, height)
        val scale = frame.width() / source.width()
        val visible = artwork.bounds
        return RectF(frame.left + (visible.left - source.left) * scale,
            frame.top + (visible.top - source.top) * scale,
            frame.left + (visible.right - source.left) * scale,
            frame.top + (visible.bottom - source.top) * scale)
    }

    /** Preserve the neutral pose's approximate size while reserving its complete video canvas. */
    fun baseSize(artwork: OverlayArtwork?): Pair<Float, Float> {
        if (artwork?.video == null) return 134f to 210f
        val frame = artwork.frames.first()
        val fit = minOf(134f * .94f / artwork.bounds.width(), 210f * .94f / artwork.bounds.height())
        return frame.width * fit / CONTENT_FRACTION to frame.height * fit / CONTENT_FRACTION
    }
}
