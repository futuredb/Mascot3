package com.generativemascot.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.Toast
import androidx.core.content.FileProvider
import com.generativemascot.app.MainActivity
import com.generativemascot.app.MascotApp
import com.generativemascot.app.R
import com.generativemascot.app.data.WIDGET_SUPPORTED_INTERVALS
import com.generativemascot.app.data.widgetFrameTiming
import com.generativemascot.app.worker.WidgetFrameWorker
import com.generativemascot.app.worker.WidgetMoodWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val WIDGET_FRAME_MAX_PX = 320
private const val TAG = "MascotWidget"
private const val WIDGET_RENDER_PREFS = "widget_render_state"
private const val WIDGET_RENDER_SIGNATURE = "animation_signature"
private const val WIDGET_RENDER_ALTERNATE = "alternate_layout"
private val widgetUpdateLock = Mutex()

internal fun requestWidgetState(context: Context, mascotId: String, state: String) {
    context.getSharedPreferences(WIDGET_RENDER_PREFS, Context.MODE_PRIVATE).edit()
        .putString("requested:$mascotId", state).apply()
}

internal fun requestedWidgetState(context: Context, mascotId: String): String? =
    context.getSharedPreferences(WIDGET_RENDER_PREFS, Context.MODE_PRIVATE).getString("requested:$mascotId", null)

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
    // Small local frames; fixed collections additionally cap their total decoded bitmap memory.
    return widgetPixels.coerceIn(128, WIDGET_FRAME_MAX_PX)
}

/**
 * One UI sometimes reapplies an updated collection to the existing AdapterViewFlipper but keeps
 * showing rows from its previous adapter. Alternating between two identical layout resources when
 * the source clip changes forces a clean host-side inflate without ever presenting an empty view.
 */
private fun animatedWidgetLayout(context: Context, widgetId: Int, signature: String, preloadFrames: Boolean, intervalMs: Int): Int {
    val prefs = context.getSharedPreferences(WIDGET_RENDER_PREFS, Context.MODE_PRIVATE)
    val previous = prefs.getString("$WIDGET_RENDER_SIGNATURE:$widgetId", null)
    val alternate = if (previous == signature) {
        prefs.getBoolean("$WIDGET_RENDER_ALTERNATE:$widgetId", false)
    } else {
        !prefs.getBoolean("$WIDGET_RENDER_ALTERNATE:$widgetId", false)
    }
    return if (preloadFrames) {
        if (alternate) R.layout.mascot_widget_alt else R.layout.mascot_widget
    } else {
        widgetAdapterLayout(intervalMs, alternate)
    }
}

private fun defaultWidgetHostPackage(context: Context): String? {
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return context.packageManager
        .resolveActivity(homeIntent, 0)
        ?.activityInfo
        ?.packageName
}

private fun usesPreloadedWidgetFrames(context: Context): Boolean =
    defaultWidgetHostPackage(context) == "com.sec.android.app.launcher"

private fun widgetHostPackages(context: Context): Set<String> {
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return buildSet {
        context.packageManager.queryIntentActivities(homeIntent, 0).forEach { result ->
            result.activityInfo?.packageName?.let(::add)
        }
        defaultWidgetHostPackage(context)?.let(::add)
        // The system host can render widgets on lock-screen surfaces.
        add("com.android.systemui")
    }
}

private fun widgetFrameUri(context: Context, file: File, hosts: Set<String>): Uri? =
    runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).also { uri ->
            hosts.forEach { host ->
                context.grantUriPermission(host, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }.onFailure {
        Log.e(TAG, "Cannot expose widget frame ${file.name}", it)
    }.getOrNull()

private fun staticWidgetViews(context: Context, file: File?, sizePx: Int, open: PendingIntent): RemoteViews =
    RemoteViews(context.packageName, R.layout.mascot_widget_static).apply {
        file?.let { widgetFrame(it, sizePx) }?.let { setImageViewBitmap(R.id.widget_static_image, it) }
        setOnClickPendingIntent(R.id.widget_static_image, open)
    }

suspend fun updateMascotWidgets(
    context: Context,
    prepareAnimation: Boolean = true,
    stateOverride: String? = null,
    force: Boolean = false,
    preparedMascotId: String? = null,
) = widgetUpdateLock.withLock { withContext(Dispatchers.IO) {
    val app = context.applicationContext as MascotApp
    val manager = AppWidgetManager.getInstance(context)
    val provider = ComponentName(context, MascotWidgetReceiver::class.java)
    val widgetIds = manager.getAppWidgetIds(provider)
    if (widgetIds.isEmpty()) return@withContext

    val mascotId = app.session.acceptedMascotId() ?: app.session.mascotId()
    // A widget update must remain local and fast. ContextRefreshWorker resolves weather/state later.
    val record = app.behavior.store.current
    if (mascotId != null && record.heroId != mascotId) return@withContext
    val state = com.generativemascot.app.state.renderedAction(record,
        mascotId?.let { app.heroStore.actionVideoUrls(it).keys }.orEmpty())
    if (stateOverride != null && stateOverride != state) return@withContext
    if (preparedMascotId != null && !preparedWidgetStateIsCurrent(
            preparedMascotId, state, mascotId, requestedWidgetState(context, preparedMascotId))) return@withContext
    if (prepareAnimation && mascotId != null) requestWidgetState(context, mascotId, state)
    val animation = mascotId?.let { id ->
        runCatching { app.heroStore.cachedWidgetAnimation(id, state) }
            .onFailure { Log.e(TAG, "Cannot read cached widget animation", it) }
            .getOrNull()
    }
    val sourceFrames = animation?.files.orEmpty()
    val timing = if (sourceFrames.isNotEmpty()) runCatching {
        widgetFrameTiming(sourceFrames.size.toLong() * (animation?.frameIntervalMs ?: 125))
    }.getOrNull() else null
    val frames = if (timing != null) List(timing.count) { sourceFrames[it * sourceFrames.size / timing.count] }
        else sourceFrames.take(1)
    val frameCount = frames.size
    val frameIntervalMs = timing?.intervalMs ?: 2_000
    val firstFrame = frames.firstOrNull()
    val fallbackFile = mascotId?.let { app.heroStore.stateFile(it, state) }
        ?: mascotId?.let { app.heroStore.baseFile(it) }
    val animationSignature = listOf(
        mascotId.orEmpty(),
        firstFrame?.parentFile?.absolutePath.orEmpty(),
        firstFrame?.lastModified()?.toString().orEmpty(),
        frameCount.toString(),
        frameIntervalMs.toString(),
        WIDGET_RENDER_VERSION.toString(),
        if (frameCount == 0) "${fallbackFile?.absolutePath}:${fallbackFile?.lastModified()}" else "",
    ).joinToString(":")
    val preloadFrames = usesPreloadedWidgetFrames(context)
    val fixedCollection = !preloadFrames && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val needsPreparation = mascotId != null && runCatching { app.heroStore.needsWidgetPreparation(mascotId, state) }
        .onFailure { Log.e(TAG, "Cannot inspect widget cache", it) }.getOrDefault(false)
    val renderPrefs = context.getSharedPreferences(WIDGET_RENDER_PREFS, Context.MODE_PRIVATE)
    val frameRows by lazy { if (preloadFrames) {
        val widgetHosts = widgetHostPackages(context)
        frames.mapNotNull { file ->
            widgetFrameUri(context, file, widgetHosts)?.let { uri ->
                RemoteViews(context.packageName, R.layout.mascot_widget_frame).apply {
                    setImageViewUri(R.id.widget_frame_image, uri)
                }
            }
        }
    } else {
        emptyList()
    } }
    val openIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("widget_interact", true)
    }
    widgetIds.forEach { widgetId ->
        val options = manager.getAppWidgetOptions(widgetId)
        val sizeKey = listOf(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).joinToString(":") { options.getInt(it).toString() }
        val signature = "$animationSignature:$preloadFrames:$fixedCollection:$sizeKey"
        val previous = renderPrefs.getString("$WIDGET_RENDER_SIGNATURE:$widgetId", null)
        if (needsPreparation && previous != null && renderPrefs.getString("mascot:$widgetId", null) == mascotId && !force) {
            // Keep the current animation visible until the requested replacement is fully prepared.
            return@forEach
        }
        if (!widgetNeedsUpdate(previous, signature, force)) return@forEach
        val supportedInterval = WIDGET_SUPPORTED_INTERVALS.minBy { kotlin.math.abs(it - frameIntervalMs) }
        val animatedLayout = animatedWidgetLayout(context, widgetId, signature, preloadFrames, supportedInterval)
        val alternate = if (previous == signature) renderPrefs.getBoolean("$WIDGET_RENDER_ALTERNATE:$widgetId", false)
            else !renderPrefs.getBoolean("$WIDGET_RENDER_ALTERNATE:$widgetId", false)
        val open = PendingIntent.getActivity(
            context,
            widgetId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            },
        )
        val adapter = Intent(context, MascotWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra(EXTRA_MASCOT_ID, mascotId)
            putExtra(EXTRA_STATE_KEY, state)
            // Intent identity ignores extras, so include the source identity in the data URI.
            data = Uri.parse(
                "mascots://widget/$widgetId/${mascotId ?: "none"}/${Uri.encode(state)}/${firstFrame?.lastModified() ?: 0L}",
            )
        }
        var renderedAnimation = true
        val views = try { if (frameCount > 0 && preloadFrames && frameRows.isNotEmpty()) {
            preloadedWidgetViews(context.packageName, animatedLayout, frameRows, frameIntervalMs, open)
        } else if (frameCount > 0 && fixedCollection) {
            val side = widgetBitmapSide(frameCount, widgetFrameSizePx(context, widgetId))
            val rows = frames.map { file ->
                val bitmap = widgetFrame(file, side) ?: error("Cannot decode widget frame ${file.name}")
                RemoteViews(context.packageName, R.layout.mascot_widget_frame).apply {
                    setImageViewBitmap(R.id.widget_frame_image, bitmap)
                    setOnClickFillInIntent(R.id.widget_frame_image, Intent())
                }
            }
            fixedCollectionWidgetViews(context.packageName, animatedLayout, rows, supportedInterval, open)
        } else if (frameCount > 0) {
            RemoteViews(context.packageName, animatedLayout).apply {
                // AOSP/Pixel Launcher has stricter RemoteViews complexity limits. Its standard
                // adapter path requests and renders one small frame at a time.
                setRemoteAdapter(R.id.widget_flipper, adapter)
                setPendingIntentTemplate(R.id.widget_flipper, open)
            }
        } else {
            staticWidgetViews(context, fallbackFile, widgetFrameSizePx(context, widgetId), open)
        } } catch (error: Exception) {
            Log.e(TAG, "Cannot build widget $widgetId animation; retaining the previous image", error)
            if (previous != null && renderPrefs.getString("mascot:$widgetId", null) == mascotId) return@forEach
            renderedAnimation = false
            runCatching { staticWidgetViews(context, fallbackFile, widgetFrameSizePx(context, widgetId), open) }
                .getOrNull() ?: return@forEach
        }
        if (mascotId != (app.session.acceptedMascotId() ?: app.session.mascotId())) return@forEach
        if (mascotId != null && requestedWidgetState(context, mascotId) != state) return@forEach
        try {
            manager.updateAppWidget(widgetId, views)
            if (renderedAnimation && frameCount > 0) app.behavior.observed("widget", state)
        } catch (error: Exception) {
            Log.e(TAG, "Launcher rejected widget $widgetId update", error)
            return@forEach
        }
        // A damaged cache must be retried on a later refresh, not remembered as successfully drawn.
        if (!renderedAnimation) return@forEach
        if (!preloadFrames && !fixedCollection && frameCount > 0) {
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_flipper)
        }
        renderPrefs.edit()
            .putString("$WIDGET_RENDER_SIGNATURE:$widgetId", signature)
            .putString("mascot:$widgetId", mascotId)
            .putBoolean("$WIDGET_RENDER_ALTERNATE:$widgetId", alternate)
            .apply()
        val renderer = if (preloadFrames) "preloaded" else if (fixedCollection) "fixed_collection" else "adapter"
        Log.i(TAG, "Updated widget $widgetId with $frameCount frame(s) at ${frameIntervalMs}ms for state=$state renderer=$renderer")
    }
    if (prepareAnimation && needsPreparation && mascotId != null) {
        WidgetFrameWorker.enqueue(context, mascotId, state)
    }
} }

class MascotWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        MascotWidgetFramesFactory(applicationContext, intent)
}

private class MascotWidgetFramesFactory(
    private val context: Context,
    intent: Intent,
) : RemoteViewsService.RemoteViewsFactory {
    private val widgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID,
    )
    private val mascotId = intent.getStringExtra(EXTRA_MASCOT_ID)
    private val stateKey = intent.getStringExtra(EXTRA_STATE_KEY).orEmpty().ifBlank { "idle" }
    private var files: List<File> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val app = context.applicationContext as MascotApp
        files = runCatching {
            val animation = mascotId?.let { id -> app.heroStore.cachedWidgetAnimation(id, stateKey) }
            val fallback = mascotId?.let { app.heroStore.stateFile(it, stateKey) }
                ?: mascotId?.let { app.heroStore.baseFile(it) }
            val source = animation?.files.orEmpty()
            val timing = if (source.isNotEmpty()) runCatching {
                widgetFrameTiming(source.size.toLong() * (animation?.frameIntervalMs ?: 125))
            }.getOrNull() else null
            (if (timing != null) List(timing.count) { source[it * source.size / timing.count] } else source.take(1))
                .ifEmpty { listOfNotNull(fallback) }
        }.onFailure {
            Log.e(TAG, "Cannot load frames for widget $widgetId", it)
        }.getOrDefault(emptyList())
    }

    override fun onDestroy() {
        files = emptyList()
    }

    override fun getCount(): Int = files.size

    override fun getViewAt(position: Int): RemoteViews? {
        val file = files.getOrNull(position) ?: return null
        val bitmap = widgetFrame(file, widgetFrameSizePx(context, widgetId)) ?: return null
        return RemoteViews(context.packageName, R.layout.mascot_widget_frame).apply {
            setImageViewBitmap(R.id.widget_frame_image, bitmap)
            setOnClickFillInIntent(R.id.widget_frame_image, Intent())
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = files.getOrNull(position)?.let { file ->
        31L * file.name.hashCode() + file.lastModified()
    } ?: position.toLong()

    override fun hasStableIds(): Boolean = true
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

private const val ACTION_ROTATE_WIDGET_MOOD = "app.mascot3.action.ROTATE_WIDGET_MOOD"
private const val WIDGET_MOOD_ROTATION_INTERVAL_MS = 30 * 60 * 1_000L

private fun widgetMoodAlarm(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    24_091,
    Intent(context, MascotWidgetReceiver::class.java).setAction(ACTION_ROTATE_WIDGET_MOOD),
    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE
    } else {
        0
    },
)

private fun scheduleWidgetMoodRotation(context: Context) {
    val alarm = context.getSystemService(AlarmManager::class.java)
    // Refresh the common resolver without a widget-owned rotation or device wakeup.
    alarm.setInexactRepeating(
        AlarmManager.ELAPSED_REALTIME,
        SystemClock.elapsedRealtime() + WIDGET_MOOD_ROTATION_INTERVAL_MS,
        WIDGET_MOOD_ROTATION_INTERVAL_MS,
        widgetMoodAlarm(context),
    )
}

private fun cancelWidgetMoodRotation(context: Context) {
    context.getSystemService(AlarmManager::class.java).cancel(widgetMoodAlarm(context))
}

class MascotWidgetReceiver : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_ROTATE_WIDGET_MOOD) {
            WidgetMoodWorker.enqueue(context)
        }
    }

    override fun onEnabled(context: Context) {
        scheduleWidgetMoodRotation(context)
    }

    override fun onDisabled(context: Context) {
        cancelWidgetMoodRotation(context)
        WidgetMoodWorker.cancel(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        scheduleWidgetMoodRotation(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                (context.applicationContext as MascotApp).behavior.refresh()
                updateMascotWidgets(context, force = true)
            } catch (error: Exception) {
                Log.e(TAG, "Cannot refresh widget", error)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val editor = context.getSharedPreferences(WIDGET_RENDER_PREFS, Context.MODE_PRIVATE).edit()
        ids.forEach { id ->
            editor.remove("$WIDGET_RENDER_SIGNATURE:$id").remove("$WIDGET_RENDER_ALTERNATE:$id").remove("mascot:$id")
        }
        editor.apply()
    }
}

private const val EXTRA_MASCOT_ID = "mascot_id"
private const val EXTRA_STATE_KEY = "state_key"
