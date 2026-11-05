package com.generativemascot.app.widget

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.generativemascot.app.MainActivity
import com.generativemascot.app.MascotApp
import com.generativemascot.app.R
import com.generativemascot.app.overlay.OverlaySession
import com.generativemascot.app.physics.PhoneInteractionMotion
import com.generativemascot.app.physics.PhoneMotion
import com.generativemascot.app.physics.PhonePose
import com.generativemascot.app.physics.PhysicsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** RemoteViews cannot host the GL surface, so only the current 2.5D frame is sent to the launcher. */
class WidgetDepthService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sensor by lazy { PhoneMotion(this) }
    private val movement = PhoneInteractionMotion()
    private val manager by lazy { AppWidgetManager.getInstance(this) }
    private val app by lazy { application as MascotApp }
    private var loop: Job? = null
    private var reactionLoad: Job? = null
    private var renderer: WidgetDepthRenderer? = null
    private var heroId: String? = null
    private var loadedRevision: String? = null
    private var reactionFiles: List<File> = emptyList()
    private var reactionInterval = 125L
    private var reactionStart = 0L
    private var previousAction: String? = null
    private var pendingTap = false
    private var lastTick = 0L
    private var nextHeroCheck = 0L
    private var sensorsRunning = false
    private var interactive = true
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            interactive = intent?.action != Intent.ACTION_SCREEN_OFF
            if (!interactive) stopSensors()
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensor.onAcceleration = { sample -> if (movement.observe(sample)) requestReaction() }
        interactive = getSystemService(PowerManager::class.java).isInteractive
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        val channel = "mascot-widget-depth"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "Объёмный виджет", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 9520, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 9522, Intent(this, WidgetDepthService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_overlay_notification)
            .setContentTitle("Объёмный виджет")
            .setContentText("Персонаж в виджете")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, "Выключить", stop).build()
        try { ServiceCompat.startForeground(this, 9520, notification, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) }
        catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (loop?.isActive != true) loop = scope.launch {
            try { runFrames() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { stopSelf() }
        }
        if (intent?.action == ACTION_REACT) {
            if (heroId == null || renderer == null) pendingTap = true else requestReaction()
        }
        return START_STICKY
    }

    private suspend fun runFrames() {
        while (scope.isActive) {
            val ids = manager.getAppWidgetIds(ComponentName(this, MascotWidgetReceiver::class.java))
            if (ids.isEmpty()) { stopSelf(); return }
            val available = interactive && !getSystemService(KeyguardManager::class.java).isDeviceLocked && OverlaySession.hero(this) == null
            if (!available) { stopSensors(); delay(700); continue }
            val now = SystemClock.uptimeMillis()
            val checkHero = heroId == null || now >= nextHeroCheck
            val selected = if (checkHero) {
                nextHeroCheck = now + 1000L
                app.session.acceptedMascotId() ?: app.session.mascotId()
            } else heroId
            if (selected == null) { stopSensors(); delay(1000); continue }
            val store = PhysicsStore(this)
            val currentRevision = if (checkHero) runCatching { store.revision(selected) }.getOrNull() else loadedRevision
            if (selected != heroId || renderer == null || (currentRevision != null && currentRevision != loadedRevision)) {
                stopSensors(); renderer?.close(); renderer = null; heroId = selected
                reactionFiles = emptyList(); reactionLoad?.cancel()
                val physics = try { store.load(selected) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (physics == null) { loadedRevision = null; pendingTap = false; delay(10_000); continue }
                renderer = withContext(Dispatchers.IO) { WidgetDepthRenderer(physics) }
                loadedRevision = store.revision(selected)
                if (pendingTap) { pendingTap = false; requestReaction() }
            }
            if (!sensorsRunning) { sensorsRunning = sensor.start(); movement.reset(); lastTick = 0L }
            val sample = sensor.sample
            if (movement.observe(sample)) requestReaction()
            val elapsed = if (lastTick == 0L) 0.016 else ((now - lastTick).coerceIn(1L, 250L) / 1000.0)
            var remaining = elapsed.toFloat()
            var pose = movement.pose
            while (remaining > 0f) {
                val step = minOf(remaining, .05f)
                pose = movement.advance(step, sample)
                remaining -= step
            }
            lastTick = now
            val bitmap = if (reactionFiles.isNotEmpty()) {
                val index = ((now - reactionStart) / reactionInterval).toInt()
                if (index in reactionFiles.indices) withContext(Dispatchers.IO) { BitmapFactory.decodeFile(reactionFiles[index].path) }
                else { reactionFiles = emptyList(); null }
            } else null
            val frame = if (bitmap != null) {
                try { withContext(Dispatchers.Default) { renderReaction(bitmap, pose) } }
                finally { bitmap.recycle() }
            } else withContext(Dispatchers.Default) { renderer?.render(256, elapsed, sample) }
            if (frame != null && OverlaySession.hero(this) == null && interactive) {
                val tap = PendingIntent.getForegroundService(this, 9521,
                    Intent(this, WidgetDepthService::class.java).setAction(ACTION_REACT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val views = RemoteViews(packageName, R.layout.mascot_widget_static).apply {
                    setImageViewBitmap(R.id.widget_static_image, frame)
                    setOnClickPendingIntent(R.id.widget_static_image, tap)
                }
                // RemoteViews/launcher may still be holding this bitmap after
                // updateAppWidget returns. Let GC reclaim it after replacement.
                try { manager.updateAppWidget(ids, views) }
                catch (error: Exception) { frame.recycle(); throw error }
            } else frame?.recycle()
            // Match the existing widget's 12 fps while accounting for render
            // and launcher delivery time instead of adding a fixed delay.
            delay((83L - (SystemClock.uptimeMillis() - now)).coerceAtLeast(5L))
        }
    }

    private fun requestReaction() {
        val id = heroId ?: return
        if (reactionFiles.isNotEmpty() || reactionLoad?.isActive == true) return
        reactionLoad = scope.launch {
            val store = app.heroStore
            val options = listOf("joyful", "greeting", "curious", "tender", "dancing")
                .filter { store.actionVideoFile(id, it) != null }
            val action = (options - previousAction).randomOrNull() ?: options.randomOrNull() ?: return@launch
            val frames = runCatching { store.widgetAnimation(id, action) }.getOrNull() ?: return@launch
            if (heroId != id || OverlaySession.hero(this@WidgetDepthService) != null) return@launch
            if (frames.files.isNotEmpty()) {
                previousAction = action; reactionFiles = frames.files
                reactionInterval = frames.frameIntervalMs.coerceAtLeast(83).toLong()
                reactionStart = SystemClock.uptimeMillis()
            }
        }
    }

    private fun renderReaction(source: Bitmap, pose: PhonePose): Bitmap {
        val result = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val motion = pose.artwork()
        val camera = Camera()
        val matrix = Matrix()
        try {
            camera.rotateX(motion.rotationX); camera.rotateY(motion.rotationY)
            camera.getMatrix(matrix)
            matrix.preTranslate(-128f, -128f); matrix.postTranslate(128f, 128f)
            canvas.translate(motion.translationX, motion.translationY)
            canvas.rotate(motion.rotationZ, 128f, 128f)
            canvas.concat(matrix)
            val scale = minOf(256f / source.width, 256f / source.height)
            val width = source.width * scale; val height = source.height * scale
            canvas.drawBitmap(source, null, android.graphics.RectF((256 - width) / 2, (256 - height) / 2,
                (256 + width) / 2, (256 + height) / 2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return result
        } catch (error: Throwable) { result.recycle(); throw error }
    }

    private fun stopSensors() {
        if (sensorsRunning) sensor.stop()
        sensorsRunning = false; lastTick = 0L; movement.reset()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        stopSensors(); sensor.onAcceleration = null; unregisterReceiver(screenReceiver); scope.cancel(); renderer?.close(); renderer = null
        CoroutineScope(Dispatchers.IO).launch { runCatching { updateMascotWidgets(applicationContext, force = true) } }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_REACT = "app.mascot3.widget.DEPTH_REACT"
        private const val ACTION_STOP = "app.mascot3.widget.DEPTH_STOP"
        private const val PREFERENCES = "widget_depth"
        fun reactFromWidget(context: Context) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            ContextCompat.startForegroundService(context, Intent(context, WidgetDepthService::class.java).setAction(ACTION_REACT))
        }
        fun startIfPresent(context: Context) {
            if (!context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("enabled", true)) return
            val manager = AppWidgetManager.getInstance(context)
            if (manager.getAppWidgetIds(ComponentName(context, MascotWidgetReceiver::class.java)).isEmpty()) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WidgetDepthService::class.java)) }
        }
    }
}
