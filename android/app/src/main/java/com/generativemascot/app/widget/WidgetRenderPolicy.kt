package com.generativemascot.app.widget

import kotlin.math.sqrt

internal const val WIDGET_BITMAP_BUDGET_BYTES = 6 * 1024 * 1024
internal const val WIDGET_RENDER_VERSION = 3

/** Bound decoded memory, not PNG file sizes, for one complete active clip. */
internal fun widgetBitmapSide(frameCount: Int, preferredSide: Int = 320): Int {
    require(frameCount in 1..96)
    return minOf(preferredSide, sqrt(WIDGET_BITMAP_BUDGET_BYTES.toDouble() / (4 * frameCount)).toInt())
        .coerceAtLeast(1)
}

internal fun widgetNeedsUpdate(previousSignature: String?, nextSignature: String, force: Boolean): Boolean =
    force || previousSignature != nextSignature

internal fun preparedWidgetStateIsCurrent(
    preparedMascotId: String,
    preparedState: String,
    currentMascotId: String?,
    requestedState: String?,
): Boolean = preparedMascotId == currentMascotId && preparedState == requestedState
