package com.generativemascot.app.overlay

import android.animation.ValueAnimator
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.generativemascot.app.R
import com.generativemascot.app.physics.PhoneInteractionMotion
import com.generativemascot.app.physics.PhoneMotion
import com.generativemascot.app.physics.PhonePose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

data class OverlayStatus(val running: Boolean = false, val loading: Boolean = false, val error: String? = null)

class MascotOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private val prefs by lazy { getSharedPreferences(OverlaySize.PREFERENCES, MODE_PRIVATE) }
    private var load: Job? = null
    private var activeHeroId: String? = null
    private var appMonitor: Job? = null
    private var appRules = OverlayAppRules()
    private var foregroundPackage: String? = null
    private var widgetHomePackage: String? = null
    private var artwork: OverlayArtwork? = null
    private var hero: OverlayHeroView? = null
    private var bubble: OverlayHintView? = null
    private var compact: OverlayCompactView? = null
    private var heroParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleArea: OverlayArea? = null
    private var compactParams: WindowManager.LayoutParams? = null
    private var bubbleVisible = true
    private var hintIndex = 0
    private var position = OverlayPoint(0, 0)
    private var compactPosition = OverlayPoint(0, 0)
    private var collapsed = false
    private var destroyed = false
    private var stopping = false
    private var heroAdded = false
    private var bubbleAdded = false
    private var compactAdded = false
    private var heroWindow: OverlayHeroWindow? = null
    private var gestureWindow: OverlayGestureWindow? = null
    private var pendingLongPress: Runnable? = null
    private val cancelTouches = mutableListOf<() -> Unit>()
    private val phoneMotion by lazy { PhoneMotion(this) }
    private val movement = PhoneInteractionMotion()
    private val choreographer by lazy { Choreographer.getInstance() }
    private var motionRunning = false
    private var previousFrame = 0L
    private val unlockCheck = Runnable { showIfUnlocked() }
    private val motionFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!motionRunning) return
            if (!heroAdded || !ValueAnimator.areAnimatorsEnabled()) { stopMotion(); return }
            val sample = phoneMotion.sample
            if (movement.observe(sample)) hero?.react()
            if (previousFrame > 0) {
                hero?.updateLighting((frameTimeNanos - previousFrame) / 1e9f, sample)
                hero?.applyPhonePose(movement.advance((frameTimeNanos - previousFrame) / 1e9f, sample))
                updateBubble(motionOnly = true)
            }
            previousFrame = frameTimeNanos
            choreographer.postFrameCallback(this)
        }
    }
    private val sizeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == OverlaySize.KEY) main.post { if (!destroyed && !stopping) resizeHero() }
        if (key == OverlayAppRules.MODE_KEY || key == OverlayAppRules.APPS_KEY)
            main.post { if (!destroyed && !stopping) showIfUnlocked() }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                main.removeCallbacks(unlockCheck)
                stopAppMonitor()
                savePosition(); saveCompactPosition()
                detachWindows()
            }
            else showIfUnlocked()
        }
    }
    private val permissionWatcher = AppOpsManager.OnOpChangedListener { _, _ ->
        main.post { if (!Settings.canDrawOverlays(this)) fail("Разрешение на показ поверх приложений отключено") }
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WindowManager::class.java)
        prefs.registerOnSharedPreferenceChangeListener(sizeListener)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(AppOpsManager::class.java).startWatchingMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionWatcher)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { dismiss(); return START_NOT_STICKY }
        if (stopping) return START_NOT_STICKY
        val restoring = intent == null || intent.action == ACTION_RESTORE
        val id = if (restoring) OverlaySession.hero(this) else intent?.getStringExtra(EXTRA_HERO)
        // START_STICKY restarts have no intent. A stale restore after an explicit
        // dismissal must never enable a different hero or show a permission error.
        if (restoring && id == null) { dismiss(); return START_NOT_STICKY }
        if (!OverlaySession.validHero(id) || !Settings.canDrawOverlays(this)) {
            fail("Разрешите показ поверх других приложений")
            return START_NOT_STICKY
        }
        try { foregroundNotification() }
        catch (_: Exception) { fail("Android не разрешил включить героя. Попробуйте из приложения."); return START_NOT_STICKY }
        OverlaySession.enable(this, requireNotNull(id))
        if (intent?.action == ACTION_WIDGET) {
            widgetHomePackage = if (OverlayAppRules.read(this).mode != OverlayAppMode.EVERYWHERE && OverlayAppRules.hasUsageAccess(this))
                packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            else null
            bubbleVisible = true
            prefs.edit().putBoolean("collapsed", false).putBoolean("bubble_visible", true).apply()
            if (collapsed && artwork != null) setCollapsed(false)
            else collapsed = false
        }
        if (activeHeroId == id && (load?.isActive == true || artwork != null)) {
            showIfUnlocked()
            return START_STICKY
        }
        activeHeroId = id
        mutableStatus.value = OverlayStatus(loading = true)
        load?.cancel()
        detachWindows()
        cancelTouches.clear()
        heroWindow?.removeAllViews(); heroWindow = null
        hero = null; bubble = null; compact = null
        collapsed = prefs.getBoolean("collapsed", false)
        bubbleVisible = prefs.getBoolean("bubble_visible", true)
        hintIndex = prefs.getInt("hint_index", 0).coerceIn(0, overlayDemoHints.lastIndex)
        artwork?.recycle(); artwork = null
        load = scope.launch {
            // Retain ownership on cancellation: a late decode must never leak bitmaps or re-add a window.
            var decoded: OverlayArtwork? = null
            try {
                withContext(Dispatchers.IO) { decoded = loadOverlayArtwork(this@MascotOverlayService, id) }
                val physics = try { com.generativemascot.app.physics.PhysicsStore(this@MascotOverlayService).load(id) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (destroyed) return@launch
                artwork = decoded
                hero = OverlayHeroView(this@MascotOverlayService, requireNotNull(decoded), physics, phoneMotion).apply {
                    contentDescription = "Плавающий герой. Нажмите для реакции. Разведите или сведите два пальца, чтобы изменить размер. Удерживайте, чтобы скрыть или показать подсказку. Перетащите, чтобы переместить."
                    setOnLongClickListener {
                        bubbleVisible = !bubbleVisible
                        prefs.edit().putBoolean("bubble_visible", bubbleVisible).apply()
                        updateBubble(); true
                    }
                    installDrag(this)
                }
                if (physics == null) scope.launch {
                    while (isActive && !destroyed && !stopping && activeHeroId == id) {
                        delay(10_000)
                        val prepared = try { com.generativemascot.app.physics.PhysicsStore(this@MascotOverlayService).load(id) }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        if (prepared != null && activeHeroId == id && !destroyed && !stopping) {
                            hero?.installPhysics(prepared, phoneMotion)
                            break
                        }
                    }
                }
                compact = OverlayCompactView(this@MascotOverlayService, requireNotNull(decoded)) { setCollapsed(false) }
                    .also { installDrag(it) }
                decoded = null
                bubble = OverlayHintView(this@MascotOverlayService, onNext = {
                    hintIndex = (hintIndex + 1) % overlayDemoHints.size
                    prefs.edit().putInt("hint_index", hintIndex).apply()
                    bubble?.setHint(overlayDemoHints[hintIndex]); updateBubble()
                }, onHide = { dismiss() }, onCollapse = { setCollapsed(true) })
                    .apply { setHint(overlayDemoHints[hintIndex]); installDrag(this); installOverlayTouchRegion(this) { touchRegion() } }
                measureWindows()
                restorePosition()
                compactPosition = OverlayLayout.restore(usableArea(), compactParams!!.width, compactParams!!.height,
                    prefs.getFloat("compact_x", .04f), prefs.getFloat("compact_y", .75f))
                showIfUnlocked()
                if (!destroyed && !stopping && hero != null) mutableStatus.value = OverlayStatus(running = true)
                val preparedArtwork = artwork
                if (preparedArtwork?.video != null) scope.launch {
                    val envelope = withContext(Dispatchers.IO) {
                        android.graphics.Rect().apply {
                            (listOfNotNull(preparedArtwork.video) + preparedArtwork.reactions).forEach { clip ->
                                union(videoEnvelope(clip, preparedArtwork.frames.first().width, preparedArtwork.frames.first().height))
                            }
                        }
                    }
                    if (!destroyed && !stopping && artwork === preparedArtwork) {
                        preparedArtwork.canvasBounds = envelope
                        showIfUnlocked()
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { fail("Не удалось открыть героя. Дождитесь сохранения его изображения и попробуйте ещё раз.") }
            finally { decoded?.recycle() }
        }
        return START_STICKY
    }

    private fun foregroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Герой поверх приложений",
            NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 71, Intent(this, OverlaySettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 72, Intent(this, MascotOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_overlay_notification)
            .setContentTitle("Герой рядом")
            .setContentText("Нажмите — герой отреагирует. Удерживайте — скрыть подсказку.")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, "Скрыть героя", stop).build()
        ServiceCompat.startForeground(this, NOTIFICATION, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Leave FLAG_SPLIT_TOUCH off: a gesture starting on the hero keeps both
        // fingers, even if the second lands outside this small window.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        title = "Mascot companion"
    }

    private fun usableArea(): OverlayArea {
        val margin = overlayDp(8)
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return OverlayArea(insets.left + margin, insets.top + margin,
                metrics.bounds.width() - insets.right - margin, metrics.bounds.height() - insets.bottom - margin)
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") windows.defaultDisplay.getRealMetrics(metrics)
        return OverlayArea(margin, overlayDp(32), metrics.widthPixels - margin, metrics.heightPixels - overlayDp(48))
    }

    private fun measureWindows() {
        val area = usableArea()
        val size = OverlaySize.dimensions(OverlaySize.read(this), resources.displayMetrics.density, area, artwork)
        heroParams = params(size.width, size.height).apply {
            // Only transparent canvas padding may leave the screen. The body is
            // constrained by heroPlacement; hint and compact windows stay inside.
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        compactParams = params(minOf(overlayDp(48), area.width), minOf(overlayDp(48), area.height))
        val card = bubble ?: return
        card.compositionScale = OverlaySize.read(this)
        val width = minOf((overlayDp(248) * card.compositionScale).roundToInt(), area.width)
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(area.height, View.MeasureSpec.AT_MOST))
        bubbleParams = params(width, card.measuredHeight.coerceAtLeast(1))
    }

    private fun resizeHero() {
        val old = heroParams ?: return // A pending load will read the latest preference itself.
        val previous = OverlayDimensions(old.width, old.height)
        val size = OverlaySize.dimensions(OverlaySize.read(this), resources.displayMetrics.density, usableArea(), artwork)
        if (size == previous && bubble?.compositionScale == OverlaySize.read(this)) return // Persisting a pinch must not reposition it or restart playback.
        measureWindows()
        val next = heroParams ?: return
        position = heroPlacement(next.width, next.height).reposition(position,
            heroPlacement(previous.width, previous.height))
        showIfUnlocked() // Update existing windows; do not restart a clip or detach its surface.
        savePosition()
    }

    private fun setCollapsed(value: Boolean) {
        if (value == collapsed || destroyed || stopping) return
        collapsed = value
        if (!value) bubbleVisible = true
        prefs.edit().putBoolean("collapsed", value).putBoolean("bubble_visible", bubbleVisible).apply()
        showIfUnlocked()
    }

    private fun showIfUnlocked() {
        main.removeCallbacks(unlockCheck)
        if (destroyed || stopping || !Settings.canDrawOverlays(this)) return
        if (!getSystemService(PowerManager::class.java).isInteractive) {
            stopAppMonitor(); detachWindows(); return
        }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            stopAppMonitor()
            detachWindows()
            // One UI can deliver USER_PRESENT before KeyguardManager clears the
            // lock. Wait while the display is awake, including biometric unlock
            // with no second broadcast. No wake lock, sensors or decoder here.
            main.postDelayed(unlockCheck, 1_000)
            return
        }
        if (hero == null) return
        refreshAppMonitor()
        if (!shouldShowOver(foregroundPackage)) {
            savePosition(); saveCompactPosition()
            detachWindows()
            return
        }
        try {
            val view = hero ?: return
            val p = heroParams ?: return
            position = heroPlacement(p.width, p.height).clamp(position.x, position.y)
            p.x = position.x; p.y = position.y
            // addView registers immediately, but onAttachedToWindow happens at the next traversal.
            val host = heroWindow ?: OverlayHeroWindow(this, view, requireNotNull(artwork)).also { heroWindow = it }
            val visibleParams = host.configure(p)
            if (!heroAdded) { windows.addView(host, visibleParams); heroAdded = true }
            else windows.updateViewLayout(host, visibleParams)
            startMotion()
            updateBubble()
        } catch (_: Exception) { fail("Android закрыл плавающее окно. Включите героя снова в приложении.") }
    }

    private fun stopAppMonitor() {
        appMonitor?.cancel(); appMonitor = null
        foregroundPackage = null
    }

    private fun shouldShowOver(current: String?) =
        appRules.showsOver(current) || (current != null && current == widgetHomePackage)

    private fun refreshAppMonitor() {
        val next = OverlayAppRules.read(this)
        if (next != appRules) { stopAppMonitor(); appRules = next }
        if (!next.needsTracking && widgetHomePackage == null) { stopAppMonitor(); return }
        if (appMonitor?.isActive == true) return
        // The reader owns its cursor on IO. A canceled query cannot publish into
        // a newer configuration or re-add windows after lock/stop.
        appMonitor = scope.launch {
            val reader = OverlayForegroundApps(this@MascotOverlayService)
            while (isActive) {
                val current = withContext(Dispatchers.IO) { reader.currentPackage() }
                if (!getSystemService(PowerManager::class.java).isInteractive ||
                    getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                    showIfUnlocked()
                    return@launch
                }
                val wasVisible = shouldShowOver(foregroundPackage)
                // A widget tap temporarily reveals the hero on this launcher.
                // Leaving home restores the saved rules without modifying them.
                if (current != null && current != packageName && current != widgetHomePackage)
                    widgetHomePackage = null
                foregroundPackage = current
                if (shouldShowOver(current) != wasVisible) showIfUnlocked()
                delay(700)
            }
        }
    }

    private fun startMotion() {
        if (motionRunning || !heroAdded || !ValueAnimator.areAnimatorsEnabled()) return
        if (!phoneMotion.start()) return
        movement.reset(); previousFrame = 0L
        motionRunning = true
        choreographer.postFrameCallback(motionFrame)
    }

    private fun stopMotion() {
        if (motionRunning) {
            motionRunning = false
            choreographer.removeFrameCallback(motionFrame)
            phoneMotion.stop()
        }
        previousFrame = 0L; movement.reset()
        hero?.resetLighting()
        hero?.applyPhonePose(PhonePose())
    }

    private fun updateBubble(motionOnly: Boolean = false) {
        updateCompact()
        val card = bubble ?: return
        if (collapsed || !bubbleVisible || !heroAdded) {
            removeBubble(); updateGestureWindow(); return
        }
        try {
            val area = if (motionOnly && bubbleArea != null) bubbleArea!! else usableArea().also { bubbleArea = it }
            val p = bubbleParams ?: return
            val heroSize = heroParams ?: return
            val visible = hero?.hintBounds(heroSize.width, heroSize.height) ?: return
            val anchor = OverlayPoint(position.x + visible.left.roundToInt(), position.y)
            val visibleWidth = visible.width().roundToInt().coerceAtLeast(1)
            // Tilt must not flip the whole card to the other side at the screen centre.
            val neutral = hero?.hintBounds(heroSize.width, heroSize.height, transformed = false) ?: return
            val toRight = position.x + neutral.centerX() < area.left + area.width / 2
            card.pointTowardHero(toRight)
            val oldHeight = p.height
            if (card.isLayoutRequested || card.measuredWidth != p.width)
                card.measure(View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(area.height, View.MeasureSpec.AT_MOST))
            p.height = card.measuredHeight.coerceAtLeast(1)
            val target = OverlayLayout.bubble(area, anchor, visibleWidth, p.width, p.height, overlayDp(2),
                anchorY = hero?.hintAnchorY(heroSize.width, heroSize.height),
                tailInset = card.tailInset, tailHeight = card.tailHeight, toRight = toRight)
            card.aimAtHero(position.x + (hero?.hintTargetX(heroSize.width, heroSize.height, toRight) ?: 0f) - target.x)
            val changed = p.x != target.x || p.y != target.y || oldHeight != p.height
            p.x = target.x; p.y = target.y
            if (bubbleAdded) { if (changed) windows.updateViewLayout(card, p) }
            else { removeGestureWindow(); windows.addView(card, p); bubbleAdded = true }
            updateGestureWindow()
        } catch (_: Exception) { fail("Не удалось показать подсказку. Включите героя снова.") }
    }

    private fun updateCompact() {
        if (!collapsed || !heroAdded) {
            if (compactAdded) compact?.let { windows.removeViewImmediate(it) }
            compactAdded = false
            return
        }
        val view = compact ?: return
        val p = compactParams ?: return
        val hp = heroParams ?: return
        val visible = hero?.hintBounds(hp.width, hp.height, transformed = false) ?: return
        val target = OverlayLayout.clamp(usableArea(), p.width, p.height,
            position.x + visible.right.roundToInt() - p.width * 5 / 6,
            position.y + visible.bottom.roundToInt() - p.height * 5 / 6)
        compactPosition = target
        val changed = p.x != target.x || p.y != target.y
        p.x = target.x; p.y = target.y
        if (!compactAdded) {
            removeGestureWindow()
            windows.addView(view, p); compactAdded = true
        } else if (changed) windows.updateViewLayout(view, p)
    }

    private fun updateGestureWindow() {
        if (Build.VERSION.SDK_INT < 33 || !heroAdded) { removeGestureWindow(); return }
        if (gestureWindow == null) {
            val input = OverlayGestureWindow(this) {
                buildList {
                    if (compactAdded) compact?.let { bell -> compactParams?.let { p ->
                        add(bell to android.graphics.Region(p.x, p.y, p.x + p.width, p.y + p.height))
                    } }
                    if (bubbleAdded) bubble?.let { card -> bubbleParams?.let { p ->
                        add(card to card.touchRegion().apply { translate(p.x, p.y) })
                    } }
                    if (heroAdded) hero?.let { view -> heroParams?.let { p ->
                        add(view to view.touchRegion().apply { translate(p.x, p.y) })
                    } }
                }
            }
            installOverlayTouchRegion(input) { input.touchRegion() }
            val bounds = windows.currentWindowMetrics.bounds
            val p = params(bounds.width(), bounds.height()).apply {
                x = 0; y = 0
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                // No pixels are drawn. One UI drops input at very low window alpha.
                // Stay below Android's .8 untrusted-overlay occlusion limit while
                // keeping input enabled on Samsung; touchRegion still limits hits.
                alpha = .5f
                title = "Mascot gestures"
            }
            windows.addView(input, p)
            gestureWindow = input
        }
        gestureWindow?.invalidate()
    }

    private fun removeGestureWindow() {
        gestureWindow?.let { runCatching { windows.removeViewImmediate(it) } }
        gestureWindow = null
    }

    private fun installDrag(view: View, compactMode: Boolean = false) {
        var downX = 0f; var downY = 0f; var start = position; var dragging = false
        var longPressed = false
        var multiTouch = false
        var accepting = false
        var scale = OverlaySize.read(this)
        var scaleDirty = false
        var anchorX = .5f; var anchorY = .5f
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        fun persistSize() {
            if (!scaleDirty) return
            scaleDirty = false
            OverlaySize.save(this, scale)
            savePosition()
        }
        val detector = if (compactMode) null else OverlayPinchGesture(overlayDp(2).toFloat(),
                onBegin = begin@ { focusX, focusY ->
                    val p = heroParams ?: return@begin false
                    if (!accepting || !heroAdded) return@begin false
                    cancelLongPress()
                    multiTouch = true
                    scale = OverlaySize.read(this@MascotOverlayService)
                    // Use the actual event window origin, not a pending layout update.
                    anchorX = (focusX - position.x) / p.width
                    anchorY = (focusY - position.y) / p.height
                    true
                },
                onScale = update@ { factor, focusX, focusY ->
                    val p = heroParams ?: return@update
                    if (!accepting || !heroAdded) return@update
                    if (!factor.isFinite() || factor <= 0f) return@update
                    scale = OverlaySize.constrain(scale * factor)
                    val area = usableArea()
                    val size = OverlaySize.dimensions(scale, resources.displayMetrics.density, area, artwork)
                    val target = heroPlacement(size.width, size.height, area).clamp(
                        (focusX - anchorX * size.width).roundToInt(),
                        (focusY - anchorY * size.height).roundToInt())
                    scaleDirty = true
                    bubble?.compositionScale = scale
                    bubbleParams?.width = minOf((overlayDp(248) * scale).roundToInt(), area.width)
                    if (p.width != size.width || p.height != size.height || position != target || bubble?.isLayoutRequested == true) {
                        p.width = size.width; p.height = size.height
                        position = target
                        showIfUnlocked() // Resize the existing surface; keep the current animation.
                    }
                },
                onEnd = ::persistSize)
        cancelTouches += {
            accepting = false
            detector?.cancel()
            persistSize()
            multiTouch = false
        }
        val touchListener = View.OnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                accepting = true
                multiTouch = false
            }
            if (!accepting) return@OnTouchListener true
            if (event.pointerCount > 1) {
                multiTouch = true
                cancelLongPress()
            }
            detector?.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelLongPress()
                    downX = event.rawX; downY = event.rawY
                    start = if (compactMode) compactPosition else position
                    dragging = false; longPressed = false
                    if (!compactMode && view !is OverlayHintView) pendingLongPress = Runnable {
                        pendingLongPress = null
                        if (!dragging && !multiTouch && accepting && view.isAttachedToWindow) longPressed = view.performLongClick()
                    }.also { main.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Wait for all fingers to lift before allowing a new drag or reaction.
                    if (multiTouch || view is OverlayHintView) return@OnTouchListener true
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (hypot(dx, dy) > slop) { dragging = true; cancelLongPress() }
                    if (dragging) {
                        val p = (if (compactMode) compactParams else heroParams) ?: return@OnTouchListener true
                        val target = if (compactMode) OverlayLayout.clamp(usableArea(), p.width, p.height,
                            start.x + dx.toInt(), start.y + dy.toInt())
                        else heroPlacement(p.width, p.height).clamp(start.x + dx.toInt(), start.y + dy.toInt())
                        if (compactMode) compactPosition = target else position = target
                        showIfUnlocked()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    accepting = false
                    cancelLongPress()
                    persistSize()
                    if (!multiTouch && !dragging && !longPressed) view.performClick()
                    else if (dragging) { if (compactMode) saveCompactPosition() else savePosition() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    accepting = false
                    cancelLongPress()
                    persistSize()
                    if (compactMode) saveCompactPosition() else savePosition()
                    true
                }
                else -> true
            }
        }
        if (view is OverlayHintView) view.gestureTouch = touchListener else view.setOnTouchListener(touchListener)
    }

    private fun cancelLongPress() {
        pendingLongPress?.let(main::removeCallbacks)
        pendingLongPress = null
    }

    private fun savePosition() {
        val p = heroParams ?: return
        val (x, y) = heroPlacement(p.width, p.height).fractions(position)
        prefs.edit().putFloat("x", x).putFloat("y", y).putBoolean("body_position", true).apply()
    }

    private fun heroPlacement(width: Int, height: Int, area: OverlayArea = usableArea()) =
        OverlayPlacement.hero(area, OverlayDimensions(width, height), artwork, overlayDp(16))

    private fun restorePosition() {
        val p = heroParams ?: return
        val x = prefs.getFloat("x", .04f)
        val y = prefs.getFloat("y", .58f)
        val placement = heroPlacement(p.width, p.height)
        position = if (!prefs.getBoolean("body_position", false) && prefs.contains("x")) {
            // Preserve the old on-screen location once, then persist using the
            // new travel range. An update must not send a centred hero to an edge.
            val legacy = OverlayLayout.restore(usableArea(), p.width, p.height, x, y)
            placement.clamp(legacy.x, legacy.y)
        } else placement.restore(x, y)
        savePosition()
    }

    private fun saveCompactPosition() {
        val p = compactParams ?: return
        val (x, y) = OverlayLayout.fractions(usableArea(), p.width, p.height, compactPosition)
        prefs.edit().putFloat("compact_x", x).putFloat("compact_y", y).apply()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        detachWindows()
        measureWindows()
        restorePosition()
        compactPosition = OverlayLayout.restore(usableArea(), compactParams?.width ?: 1, compactParams?.height ?: 1,
            prefs.getFloat("compact_x", .04f), prefs.getFloat("compact_y", .75f))
        showIfUnlocked()
    }
    private fun removeBubble() {
        if (bubbleAdded) bubble?.let { runCatching { windows.removeViewImmediate(it) } }
        bubbleAdded = false
    }
    private fun detachWindows() {
        removeGestureWindow()
        stopMotion()
        cancelLongPress()
        cancelTouches.forEach { it() }
        removeBubble()
        if (heroAdded) heroWindow?.let { runCatching { windows.removeViewImmediate(it) } }
        heroAdded = false
        if (compactAdded) compact?.let { runCatching { windows.removeViewImmediate(it) } }
        compactAdded = false
    }
    private fun fail(message: String) {
        OverlaySession.disable(this)
        stopping = true
        stopAppMonitor()
        main.removeCallbacks(unlockCheck)
        mutableStatus.value = OverlayStatus(error = message)
        stopSelf()
    }
    private fun dismiss() {
        OverlaySession.disable(this)
        stopping = true
        stopAppMonitor()
        main.removeCallbacks(unlockCheck)
        load?.cancel()
        detachWindows()
        stopSelf()
    }
    override fun onDestroy() {
        destroyed = true
        stopAppMonitor()
        prefs.unregisterOnSharedPreferenceChangeListener(sizeListener)
        load?.cancel(); scope.cancel(); main.removeCallbacksAndMessages(null)
        detachWindows()
        cancelTouches.clear()
        heroWindow?.removeAllViews(); heroWindow = null
        hero = null; bubble = null; compact = null
        artwork?.recycle(); artwork = null
        runCatching { unregisterReceiver(screenReceiver) }
        getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionWatcher)
        stopForeground(STOP_FOREGROUND_REMOVE)
        mutableStatus.value = OverlayStatus(error = mutableStatus.value.error)
        super.onDestroy()
    }
    companion object {
        const val EXTRA_HERO = "overlay_hero"
        const val ACTION_STOP = "com.generativemascot.app.overlay.STOP"
        internal const val ACTION_RESTORE = "com.generativemascot.app.overlay.RESTORE"
        internal const val ACTION_WIDGET = "com.generativemascot.app.overlay.WIDGET"
        private const val CHANNEL = "mascot_overlay"
        private const val NOTIFICATION = 7601
        private val mutableStatus = MutableStateFlow(OverlayStatus())
        val status = mutableStatus.asStateFlow()
        fun start(context: Context, heroId: String) {
            ContextCompat.startForegroundService(context, Intent(context, MascotOverlayService::class.java)
                .putExtra(EXTRA_HERO, heroId))
        }
        internal fun showFromWidget(context: Context, heroId: String) {
            ContextCompat.startForegroundService(context, Intent(context, MascotOverlayService::class.java)
                .setAction(ACTION_WIDGET).putExtra(EXTRA_HERO, heroId))
        }
        fun stop(context: Context) {
            // Also clear intent when Android has already removed the process.
            OverlaySession.disable(context)
            context.stopService(Intent(context, MascotOverlayService::class.java))
        }
    }
}
