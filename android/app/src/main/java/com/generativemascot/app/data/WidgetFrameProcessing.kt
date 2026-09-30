package com.generativemascot.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

internal const val WIDGET_CACHE_MAX_SIDE = 320
internal const val WIDGET_CACHE_BITMAP_BUDGET_BYTES = 24 * 1024 * 1024
internal const val WIDGET_KEY_PROCESSING_MAX_SIDE = 1280

/** Bounds decoded URI frames too; inline RemoteViews retain their separate, smaller budget. */
internal fun widgetCacheFrameSide(count: Int): Int {
    require(count in 1..96)
    return minOf(WIDGET_CACHE_MAX_SIDE, sqrt(WIDGET_CACHE_BITMAP_BUDGET_BYTES.toDouble() / (4 * count)).toInt())
}

internal data class WidgetCrop(val inset: Float) {
    fun rect(width: Int, height: Int) = Rect(
        floor(width * inset).toInt(), floor(height * inset).toInt(),
        ceil(width * (1 - inset)).toInt(), ceil(height * (1 - inset)).toInt(),
    )
}

/** One centered window for the ENTIRE clip, never per-frame tracking/zooming. */
internal class WidgetCropTracker {
    private var extent = .30f // Keep at least 60% of the original canvas.
    private var visible = false

    fun include(cutout: Bitmap) {
        val pixels = IntArray(cutout.width * cutout.height)
        cutout.getPixels(pixels, 0, cutout.width, 0, 0, cutout.width, cutout.height)
        var left = cutout.width
        var top = cutout.height
        var right = -1
        var bottom = -1
        pixels.forEachIndexed { index, color ->
            if (Color.alpha(color) > 8) {
                val x = index % cutout.width
                val y = index / cutout.width
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        check(right >= left && bottom >= top) { "В кадре виджета пропал герой" }
        visible = true
        // Three percent of the source on each side covers AA and small bounds-pass rounding.
        extent = maxOf(extent, .5f - left.toFloat() / cutout.width + .03f,
            (right + 1f) / cutout.width - .5f + .03f,
            .5f - top.toFloat() / cutout.height + .03f,
            (bottom + 1f) / cutout.height - .5f + .03f)
    }

    fun finish(): WidgetCrop {
        check(visible) { "Нет кадров для виджета" }
        return WidgetCrop((.5f - extent).coerceIn(0f, .20f))
    }
}

internal fun removeChromaBackground(source: Bitmap): Bitmap {
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (index in pixels.indices) {
        val color = pixels[index]
        val sourceAlpha = Color.alpha(color)
        val red = Color.red(color); val green = Color.green(color); val blue = Color.blue(color)
        val dominance = minOf(green - red, green - blue)
        val alpha = when {
            sourceAlpha < 8 -> 0
            green > 105 && dominance >= 78 -> 0
            green > 85 && dominance > 28 -> (sourceAlpha * (78 - dominance).coerceIn(0, 50) / 50f).toInt()
            else -> sourceAlpha
        }
        pixels[index] = if (alpha == 0) Color.TRANSPARENT else {
            val cleanGreen = if (dominance > 12) minOf(green, maxOf(red, blue) + 12) else green
            Color.argb(alpha, red, cleanGreen, blue)
        }
    }
    return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
}

/** Key at source resolution, then filtered scaling of premultiplied transparent pixels. */
internal fun prepareWidgetFrame(source: Bitmap, crop: WidgetCrop, maxSide: Int): Bitmap {
    require(maxSide in 1..WIDGET_CACHE_MAX_SIDE)
    val cutout = removeChromaBackground(source)
    try {
        val rect = crop.rect(source.width, source.height)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(rect.width(), rect.height()))
        val output = Bitmap.createBitmap((rect.width() * scale).toInt().coerceAtLeast(1),
            (rect.height() * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            Canvas(output).drawBitmap(cutout, rect, RectF(0f, 0f, output.width.toFloat(), output.height.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    } finally { cutout.recycle() }
}
