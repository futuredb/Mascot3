package com.generativemascot.app.data

internal val WIDGET_SUPPORTED_INTERVALS = listOf(83, 125, 250, 500, 1_000, 2_000)
internal data class WidgetFrameTiming(val count: Int, val intervalMs: Int)

/** AdapterViewFlipper's setFlipInterval is NOT remotely callable. Use supported XML buckets. */
internal fun widgetFrameTiming(durationMs: Long): WidgetFrameTiming {
    require(durationMs in 1..192_000) { "Ролик слишком длинный для безопасного покадрового виджета" }
    val interval = WIDGET_SUPPORTED_INTERVALS.firstOrNull {
        (durationMs + it / 2) / it <= 96
    } ?: WIDGET_SUPPORTED_INTERVALS.last()
    val count = ((durationMs + interval / 2) / interval).toInt().coerceIn(1, 96)
    return WidgetFrameTiming(count, interval)
}
