package com.generativemascot.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import com.generativemascot.app.MainActivity
import com.generativemascot.app.MascotApp
import com.generativemascot.app.R
import com.generativemascot.app.data.LocalContextResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_WIDGET_FRAMES = 24
private const val WIDGET_FRAME_INTERVAL_MS = 167

private fun sampledFrames(files: List<File>): List<File> {
    if (files.size <= MAX_WIDGET_FRAMES) return files
    return List(MAX_WIDGET_FRAMES) { index ->
        files[index * files.lastIndex / (MAX_WIDGET_FRAMES - 1)]
    }.distinct()
}

/**
 * RemoteViews runs inside the launcher, so it cannot safely read our private files directly.
 * Decode a sharp frame sized for the widget itself instead of using a tiny fixed thumbnail.
 */
private fun widgetFrame(file: File, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val source = BitmapFactory.decodeFile(file.path) ?: return null
    val largestSide = maxOf(source.width, source.height)
    if (largestSide <= targetPx) return source
    val scale = targetPx.toFloat() / largestSide
    val scaled = Bitmap.createScaledBitmap(
        source,
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    source.recycle()
    return scaled
}

private fun widgetFrameSizePx(context: Context, widgetId: Int): Int {
    val options: Bundle = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
    val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 140)
    val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 140)
    val widgetPixels = (maxOf(widthDp, heightDp) * context.resources.displayMetrics.density).toInt()
    return widgetPixels.coerceIn(256, 512)
}

suspend fun updateMascotWidgets(context: Context) = withContext(Dispatchers.IO) {
    val app = context.applicationContext as MascotApp
    val manager = AppWidgetManager.getInstance(context)
    val provider = ComponentName(context, MascotWidgetReceiver::class.java)
    val widgetIds = manager.getAppWidgetIds(provider)
    if (widgetIds.isEmpty()) return@withContext

    val mascotId = app.session.acceptedMascotId() ?: app.session.mascotId()
    val state = app.session.currentState() ?: LocalContextResolver().resolve().also {
        app.session.saveCurrentState(it)
    }
    val sequence = mascotId?.let { app.heroStore.playbackSequenceFiles(it, state) }.orEmpty()
    val fallback = mascotId?.let { app.heroStore.stateFile(it, state) }
        ?: mascotId?.let { app.heroStore.baseFile(it) }
    val files = sampledFrames(sequence.ifEmpty { listOfNotNull(fallback) })
    val openIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("widget_interact", true)
    }
    val open = PendingIntent.getActivity(
        context, 0, openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    widgetIds.forEach { widgetId ->
        val views = RemoteViews(context.packageName, R.layout.mascot_widget)
        views.removeAllViews(R.id.widget_flipper)
        val frameSizePx = widgetFrameSizePx(context, widgetId)
        files.forEach { file ->
            widgetFrame(file, frameSizePx)?.let { bitmap ->
                val frame = RemoteViews(context.packageName, R.layout.mascot_widget_frame)
                frame.setImageViewBitmap(R.id.widget_frame_image, bitmap)
                views.addView(R.id.widget_flipper, frame)
            }
        }
        views.setInt(R.id.widget_flipper, "setFlipInterval", WIDGET_FRAME_INTERVAL_MS)
        views.setOnClickPendingIntent(R.id.widget_flipper, open)
        manager.updateAppWidget(widgetId, views)
    }
}

fun requestPinMascotWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val provider = ComponentName(context, MascotWidgetReceiver::class.java)
    if (!manager.isRequestPinAppWidgetSupported) {
        Toast.makeText(context, "Лаунчер не умеет ставить виджет из приложения", Toast.LENGTH_LONG).show()
        return
    }
    manager.requestPinAppWidget(provider, null, null)
}

class MascotWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                updateMascotWidgets(context)
            } finally {
                pending.finish()
            }
        }
    }
}
