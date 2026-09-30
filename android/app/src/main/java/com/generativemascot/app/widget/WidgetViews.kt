package com.generativemascot.app.widget

import android.app.PendingIntent
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.generativemascot.app.R

internal fun widgetAdapterLayout(intervalMs: Int, alternate: Boolean): Int = when (intervalMs) {
    83 -> if (alternate) R.layout.mascot_widget_adapter_alt else R.layout.mascot_widget_adapter
    125 -> if (alternate) R.layout.mascot_widget_adapter_125_alt else R.layout.mascot_widget_adapter_125
    250 -> if (alternate) R.layout.mascot_widget_adapter_250_alt else R.layout.mascot_widget_adapter_250
    500 -> if (alternate) R.layout.mascot_widget_adapter_500_alt else R.layout.mascot_widget_adapter_500
    1_000 -> if (alternate) R.layout.mascot_widget_adapter_1000_alt else R.layout.mascot_widget_adapter_1000
    2_000 -> if (alternate) R.layout.mascot_widget_adapter_2000_alt else R.layout.mascot_widget_adapter_2000
    else -> error("Unsupported widget interval: $intervalMs")
}

internal fun preloadedWidgetViews(
    packageName: String,
    layout: Int,
    rows: List<RemoteViews>,
    intervalMs: Int,
    open: PendingIntent,
): RemoteViews = RemoteViews(packageName, layout).apply {
    require(rows.isNotEmpty() && rows.size <= 96)
    removeAllViews(R.id.widget_flipper)
    rows.forEach { addView(R.id.widget_flipper, it) }
    setInt(R.id.widget_flipper, "setFlipInterval", intervalMs)
    setOnClickPendingIntent(R.id.widget_flipper, open)
}

/** No service calls, file reads, or bitmap decoding on each launcher frame. */
@RequiresApi(Build.VERSION_CODES.S)
internal fun fixedCollectionWidgetViews(
    packageName: String,
    layout: Int,
    rows: List<RemoteViews>,
    intervalMs: Int,
    open: PendingIntent,
): RemoteViews {
    require(rows.isNotEmpty() && rows.size <= 96)
    val items = RemoteViews.RemoteCollectionItems.Builder()
        .setHasStableIds(true).setViewTypeCount(1)
    rows.forEachIndexed { index, row -> items.addItem(index.toLong(), row) }
    return RemoteViews(packageName, layout).apply {
        setRemoteAdapter(R.id.widget_flipper, items.build())
        require(intervalMs in com.generativemascot.app.data.WIDGET_SUPPORTED_INTERVALS)
        // AdapterViewFlipper speed comes from XML; its setter is not remotely callable.
        setPendingIntentTemplate(R.id.widget_flipper, open)
    }
}
