package com.generativemascot.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.*
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.ViewFlipper
import android.widget.AdapterViewFlipper
import android.widget.RemoteViews
import com.generativemascot.app.MascotApp
import com.generativemascot.app.R
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.SessionStore
import com.generativemascot.app.overlay.OverlaySession
import com.generativemascot.app.overlay.OverlayLaunchActivity
import com.generativemascot.app.overlay.MascotOverlayService
import com.generativemascot.app.state.BehaviorCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

/** Initialize real local stores without network, scheduled workers or generation recovery. */
@Implements(MascotApp::class)
class LocalWidgetApplication : org.robolectric.shadows.ShadowApplication() {
    @RealObject private lateinit var app: MascotApp
    @Implementation fun onCreate() {
        ReflectionHelpers.setField(app, "session", SessionStore(app))
        ReflectionHelpers.setField(app, "heroStore", HeroLocalStore(app))
        ReflectionHelpers.setField(app, "behavior", BehaviorCoordinator(app))
    }
}

/** Robolectric inflates inline; Android actually inflates these in the launcher's UI process. */
@Implements(AppWidgetManager::class)
class LocalWidgetHost : org.robolectric.shadows.ShadowAppWidgetManager() {
    private val host = android.os.HandlerThread("widget-test-launcher").apply { start() }
    @Implementation override fun updateAppWidget(id: Int, views: RemoteViews) {
        val task = java.util.concurrent.FutureTask {
            super.updateAppWidget(id, views)
        }
        android.os.Handler(host.looper).post(task)
        task.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }
    fun close() { host.quitSafely(); host.join(2_000) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MascotApp::class, shadows = [LocalWidgetApplication::class, LocalWidgetHost::class], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetOverlayTest {
    private val app get() = RuntimeEnvironment.getApplication() as MascotApp
    private val heroId = "9f226b89-df35-4d3a-9b36-96c28ea1bb62"

    @Test fun staticWidgetHidesAndReturnsOnSessionChanges() = verifyWidget(animated = false, samsung = true)
    @Test fun samsungAnimationIsRemovedWhileOverlayIsEnabledAndRestoredFromCache() = verifyWidget(animated = true, samsung = true)
    @Test fun fixedCollectionAlsoHidesAndRestores() = verifyWidget(animated = true, samsung = false)

    private fun verifyWidget(animated: Boolean, samsung: Boolean) = runBlocking {
        val folder = File(app.filesDir, "hero/$heroId").apply { mkdirs() }
        val base = File(folder, "base.png")
        app.assets.open("bundled-heroes/$heroId/base.png").use { input -> base.outputStream().use(input::copyTo) }
        if (animated) {
            val sequence = File(folder, "sequences/idle").apply { mkdirs() }
            repeat(3) { base.copyTo(File(sequence, "frame_%03d.png".format(it))) }
        }
        val original = base.readBytes()
        app.session.saveAcceptedMascot(heroId)
        app.behavior.store.update { it.copy(heroId = heroId) }
        val hostPackage = if (samsung) "com.sec.android.app.launcher" else "com.example.launcher"
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = hostPackage; name = "$hostPackage.Home" } })
        ShadowSettings.setCanDrawOverlays(true)
        val manager = AppWidgetManager.getInstance(app)
        val widgets = shadowOf(manager)
        val provider = AppWidgetProviderInfo().apply { this.provider = ComponentName(app, MascotWidgetReceiver::class.java) }
        widgets.addBoundWidget(101, provider)
        widgets.addBoundWidget(102, provider)
        fun view(id: Int = 101) = widgets.getViewFor(id)
        fun hidden(id: Int = 101) = (view(id) as? ImageView)?.let {
            it.drawable == null && it.contentDescription == app.getString(R.string.widget_overlay_active)
        } == true
        fun visible() = if (animated) view() is ViewFlipper || view() is AdapterViewFlipper
            else (view() as? ImageView)?.drawable != null
        fun awaitState(expected: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!expected() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)); Thread.sleep(20)
            }
            assertTrue("Widget did not follow overlay session", expected())
        }
        try {
            updateMascotWidgets(app)
            assertTrue("Widget ${view()?.javaClass?.simpleName}: " +
                org.robolectric.shadows.ShadowLog.getLogsForTag("MascotWidget").joinToString { "${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }, visible())
            if (animated) assertTrue(if (samsung) view() is ViewFlipper else view() is AdapterViewFlipper)
            if (!animated) render(view(), "widget-overlay-off")
            OverlaySession.enable(app, heroId)
            awaitState { hidden() && hidden(102) }
            assertTrue(view().isClickable)
            assertEquals(View.VISIBLE, view().visibility) // Transparent but still tappable.
            if (!animated) render(view(), "widget-overlay-on")
            view().performClick()
            val launch = shadowOf(app).nextStartedActivity
            assertEquals(OverlayLaunchActivity::class.java.name, launch.component!!.className)
            assertEquals(heroId, launch.getStringExtra(MascotOverlayService.EXTRA_HERO))
            // A new widget and a late frame-worker update must not resurrect a duplicate.
            widgets.addBoundWidget(103, provider)
            updateMascotWidgets(app, prepareAnimation = false, stateOverride = "obsolete", preparedMascotId = "old-hero")
            assertTrue(hidden(101) && hidden(102) && hidden(103))
            app.getSharedPreferences("mascot_overlay", 0).edit().putBoolean("collapsed", true).apply()
            updateMascotWidgets(app)
            assertTrue(hidden())
            OverlaySession.disable(app)
            awaitState { visible() }
            if (!animated) render(view(), "widget-overlay-restored")
            assertArrayEquals(original, base.readBytes())
            assertEquals(heroId, app.session.acceptedMascotId())
            // Revoked permission cannot leave the widget empty on a later launcher refresh.
            OverlaySession.enable(app, heroId)
            awaitState { hidden() }
            ShadowSettings.setCanDrawOverlays(false)
            updateMascotWidgets(app)
            assertTrue(visible())
        } finally { (widgets as LocalWidgetHost).close(); folder.deleteRecursively() }
    }

    private fun render(view: View, name: String) {
        val output = System.getenv("MASCOT3_OVERLAY_OUTPUT")?.let(::File) ?: return
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(220, 209, 247))
        view.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 180, 180)
        canvas.translate(10f, 10f); view.draw(canvas)
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
