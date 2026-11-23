package com.generativemascot.app.overlay

import android.app.Application
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.KeyguardManager
import android.app.Service
import android.content.Intent
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.PowerManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowUsageStatsManager
import com.generativemascot.app.physics.PhoneMotion
import com.generativemascot.app.physics.PhonePose
import com.generativemascot.app.physics.PhoneSample
import com.generativemascot.app.physics.InstalledPhysics
import com.generativemascot.app.physics.PhysicsPack
import com.generativemascot.app.physics.PhysicsView
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.generativemascot.app.data.HeroLocalStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID
import java.time.Duration
import kotlin.random.Random
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayTest {
    @Test fun menuFigmaIconsFillTheirDensityScaledBounds() {
        for (asset in listOf("overlay-go-home-figma.svg", "figma-home/hero-grid.svg")) {
            val bitmap = overlayMenuIcon(context, asset, 48).bitmap
            val bounds = alphaBounds(bitmap)
            assertTrue("$asset icon is too narrow: $bounds", bounds.width() >= 24)
            assertTrue("$asset icon is too short: $bounds", bounds.height() >= 24)
        }
    }
    @Test fun placementUsesVisibleBodyAtEveryScreenEdge() {
        val area = OverlayArea(8, 32, 382, 800)
        val placement = OverlayPlacement(area, OverlayDimensions(300, 600), RectF(100f, 250f, 200f, 450f), 10)
        for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
            val point = placement.restore(x, y)
            assertEquals(if (x == 0f) 18f else 372f, point.x + if (x == 0f) 100f else 200f, 0.01f)
            assertEquals(if (y == 0f) 42f else 790f, point.y + if (y == 0f) 250f else 450f, 0.01f)
            val fractions = placement.fractions(point)
            assertEquals(point, placement.restore(fractions.first, fractions.second))
        }
        val window = OverlayPlacement.window(area, OverlayDimensions(100, 100))
        assertEquals(OverlayPoint(8, 32), window.clamp(-1000, -1000))
        assertEquals(OverlayPoint(282, 700), window.clamp(1000, 1000))
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private val heroId = "9f226b89-df35-4d3a-9b36-96c28ea1bb62"

    private fun savedHero(id: String = heroId): File {
        val folder = File(context.filesDir, "hero/$id").apply { mkdirs() }
        context.assets.open("bundled-heroes/$id/base.png").use { input ->
            File(folder, "base.png").outputStream().use(input::copyTo)
        }
        com.generativemascot.app.ui.ChatHistoryStore(context).write(id, listOf(
            com.generativemascot.app.ui.ChatMessage(true, "Привет"),
            com.generativemascot.app.ui.ChatMessage(false, "Привет, я здесь."),
        ))
        return folder
    }

    private fun awaitOverlay() {
        val deadline = System.nanoTime() + 10_000_000_000
        while (MascotOverlayService.status.value.loading && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        assertEquals(OverlayStatus(running = true), MascotOverlayService.status.value)
    }

    @Test fun cachedVideoBoundsMustMatchTheCurrentClipAndCanvas() {
        val folder = File(context.cacheDir, "bounds-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(folder, "idle.mp4").apply { writeText("video") }
            val clip = OverlayVideo(file, Color.GREEN)
            val cache = File(folder, "idle.mp4.overlay-bounds-v1")
            assertNull(cachedVideoEnvelope(clip, 320, 320))
            cache.writeText("${file.length()}:${file.lastModified()}:${clip.keyColor}:320:320\n10,20,200,300")
            assertEquals(Rect(10, 20, 200, 300), cachedVideoEnvelope(clip, 320, 320))
            assertNull(cachedVideoEnvelope(clip, 321, 320))
            cache.writeText("invalid")
            assertNull(cachedVideoEnvelope(clip, 320, 320))
        } finally { folder.deleteRecursively() }
    }

    @Test fun nativeHeroWindowExcludesTransparentVideoMarginsWithoutChangingCanvas() {
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val art = OverlayArtwork(listOf(bitmap), android.graphics.Rect(130, 80, 190, 270), 100,
            OverlayVideo(File("unused.mp4"), Color.GREEN), canvasBounds = android.graphics.Rect(100, 50, 220, 290))
        val hero = OverlayHeroView(context, art)
        val host = OverlayHeroWindow(context, hero, art)
        val p = WindowManager.LayoutParams().apply { width = 1000; height = 1000; x = 100; y = 200 }
        val actual = host.configure(p)
        host.measure(View.MeasureSpec.makeMeasureSpec(actual.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(actual.height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, actual.width, actual.height)
        assertTrue(actual.width < 500)
        assertTrue(actual.x > p.x)
        assertEquals(1000, hero.width)
        assertEquals(1000, hero.height)
        assertEquals(p.x, actual.x + hero.left)
        assertEquals(p.y, actual.y + hero.top)
        assertTrue(host.crop.contains(350, 225, 650, 825))
        val compact = OverlayCompactView(context) {}
        assertTrue(compact.childCount >= 1) // drag handle and optional badge
        bitmap.recycle()
    }

    @Test fun widgetOpensOverlayDirectlyWhenPermissionsAreReady() {
        ShadowSettings.setCanDrawOverlays(true)
        usageAccess(true)
        OverlayPermissionIntro.seen(context)
        val intent = OverlayLaunchActivity.intent(context, heroId)
        assertEquals(OverlayLaunchActivity::class.java.name, intent.component!!.className)
        val controller = Robolectric.buildActivity(OverlayLaunchActivity::class.java, intent).setup()
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val start = shadowOf(context).nextStartedService
            assertNotNull(start)
            assertEquals(MascotOverlayService::class.java.name, start.component!!.className)
            assertEquals(MascotOverlayService.ACTION_WIDGET, start.action)
            assertEquals(heroId, start.getStringExtra(MascotOverlayService.EXTRA_HERO))
            assertTrue(controller.get().isFinishing)
            assertNull(shadowOf(context).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun widgetWithMissingPermissionsShowsChecklistAndNeverStartsService() {
        ShadowSettings.setCanDrawOverlays(false)
        usageAccess(false)
        val controller = Robolectric.buildActivity(OverlayLaunchActivity::class.java,
            OverlayLaunchActivity.intent(context, heroId)).setup()
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
            assertNull(shadowOf(context).nextStartedService)
            assertFalse(controller.get().isFinishing)
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertNotNull(dialog)
            assertTrue(dialog.isShowing)
            System.getenv("MASCOT3_OVERLAY_OUTPUT")?.let { directory ->
                val view = dialog.window!!.decorView
                repeat(10) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
                    view.layout(0, 0, 360, view.measuredHeight)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)); Thread.sleep(10)
                }
                val bitmap = Bitmap.createBitmap(360, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(directory, "permissions.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun permissionsRefreshAfterSettingsAndAllowOptionalNotificationDenial() {
        assertTrue(OverlayPermissionIntro.needed(context))
        OverlayPermissionIntro.seen(context)
        assertFalse(OverlayPermissionIntro.needed(context))
        ShadowSettings.setCanDrawOverlays(false); usageAccess(false)
        assertFalse(OverlayPermissionState.read(context).canShow(context))
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(OverlayPermissionState.read(context).copy(notifications = false).canShow(context))
        OverlayAppRules(OverlayAppMode.HIDE_SELECTED, setOf("bank")).save(context)
        assertFalse(OverlayPermissionState.read(context).canShow(context))
        usageAccess(true)
        assertTrue(OverlayPermissionState.read(context).canShow(context))
        usageAccess(false)
        assertFalse(OverlayPermissionState.read(context).canShow(context))
    }

    @Test fun widgetRevealsExpandedHeroOnHomeThenReturnsToSavedAppRules() {
        val folder = savedHero()
        ShadowSettings.setCanDrawOverlays(true); usageAccess(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val home = "org.example.home"
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        shadowOf(context.packageManager).addResolveInfoForIntent(homeIntent, android.content.pm.ResolveInfo().apply {
            isDefault = true
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = home; name = "$home.Launcher"
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = home }
            }
        })
        val rules = OverlayAppRules(OverlayAppMode.ONLY_SELECTED, setOf("org.example.chat"))
        rules.save(context)
        context.getSharedPreferences(OverlaySize.PREFERENCES, 0).edit()
            .putBoolean("collapsed", true).putBoolean("bubble_visible", false).apply()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        foregroundEvent(home)
        val controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        val service = controller.get()
        fun view(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service) as View
        fun waitFor(message: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000
            while (!predicate() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); Thread.sleep(10)
            }
            assertTrue(message, predicate())
        }
        try {
            service.onStartCommand(Intent().setAction(MascotOverlayService.ACTION_WIDGET)
                .putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
            awaitOverlay()
            waitFor("Widget reveals the hero", { view("hero").isAttachedToWindow })
            assertTrue(view("bubble").isAttachedToWindow); assertFalse(view("compact").isAttachedToWindow)
            try {
                MascotOverlayService.setAppVisible(true)
                waitFor("Mascot hides the floating hero", { !view("hero").isAttachedToWindow })
                assertFalse(view("bubble").isAttachedToWindow)
            } finally {
                MascotOverlayService.setAppVisible(false)
            }
            waitFor("The overlay returns when Mascot is left", { view("hero").isAttachedToWindow })
            assertEquals(rules, OverlayAppRules.read(context))
            foregroundEvent("org.example.bank")
            waitFor("Excluded app hides it", { !view("hero").isAttachedToWindow })
            foregroundEvent(home)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500)); Thread.sleep(30)
            assertFalse(view("hero").isAttachedToWindow)
            foregroundEvent("org.example.chat")
            waitFor("Included app shows it", { view("hero").isAttachedToWindow })
            view("bubble").findViewWithTag<View>("collapse").performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertTrue(view("compact").isAttachedToWindow)
            service.onStartCommand(Intent().setAction(MascotOverlayService.ACTION_WIDGET)
                .putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 2)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertTrue(view("hero").isAttachedToWindow); assertFalse(view("compact").isAttachedToWindow)
        } finally { controller.destroy(); folder.deleteRecursively() }
    }

    @Test fun appRulesPersistBothModesAndHideWhenTheForegroundIsUnknown() {
        val selected = setOf("org.example.chat", "org.example.bank")
        assertEquals(OverlayAppRules(), OverlayAppRules.read(context))
        assertTrue(OverlayAppRules().showsOver(null))
        assertTrue(OverlayAppRules(OverlayAppMode.HIDE_SELECTED).showsOver(null))
        assertFalse(OverlayAppRules(OverlayAppMode.ONLY_SELECTED).showsOver(null))
        OverlayAppMode.entries.forEach { mode ->
            val rules = OverlayAppRules(mode, selected)
            rules.save(context)
            assertEquals(rules, OverlayAppRules.read(context))
            assertEquals(mode != OverlayAppMode.HIDE_SELECTED, rules.showsOver("org.example.bank"))
            assertEquals(mode != OverlayAppMode.ONLY_SELECTED, rules.showsOver("org.example.home"))
            assertEquals(mode == OverlayAppMode.EVERYWHERE, rules.showsOver(null))
        }
        System.getenv("MASCOT3_OVERLAY_OUTPUT")?.let { directory ->
            val modes = org.json.JSONObject()
            OverlayAppMode.entries.forEach { mode ->
                val values = org.json.JSONObject()
                listOf("chat", "bank", "home").forEach { app ->
                    values.put(app, OverlayAppRules(mode, setOf("bank")).showsOver(app))
                }
                values.put("unknown", OverlayAppRules(mode, setOf("bank")).showsOver(null))
                modes.put(mode.name, values)
            }
            File(directory, "app-rules.json").writeText(modes.toString())
        }
    }

    @Test fun appPickerIncludesLaunchableAppsAndHomeWithoutDuplicatingPackages() {
        val manager = shadowOf(context.packageManager)
        fun app(pkg: String, label: String) = android.content.pm.ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = pkg; name = "$pkg.Main"
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg }
            }
        }
        manager.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            app("org.example.chat", "Чат"))
        manager.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            app("org.example.home", "One UI Home"))
        manager.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            app("org.example.home", "One UI Home"))
        val choices = overlayAppChoices(context)
        assertTrue(choices.any { it.packageName == "org.example.chat" && it.label == "Чат" })
        assertEquals(1, choices.count { it.packageName == "org.example.home" })
        assertTrue(choices.first { it.packageName == "org.example.home" }.label.startsWith("Главный экран"))
    }

    @Test
    @Config(application = com.generativemascot.app.MascotApp::class,
        shadows = [com.generativemascot.app.widget.LocalWidgetApplication::class])
    fun renderMinimalOverlaySettingsForCodexPreview() {
        val output = System.getenv("MASCOT3_OVERLAY_OUTPUT")?.let(::File) ?: return
        val folder = savedHero()
        ShadowSettings.setCanDrawOverlays(true)
        usageAccess(true)
        val controller = Robolectric.buildActivity(OverlaySettingsActivity::class.java,
            Intent(context, OverlaySettingsActivity::class.java).putExtra(MascotOverlayService.EXTRA_HERO, heroId)).setup()
        try {
            val view = controller.get().window.decorView
            repeat(40) {
                view.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 390, 844)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                Thread.sleep(15)
            }
            val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(output, "overlay-settings.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { controller.pause().stop().destroy(); folder.deleteRecursively() }
    }

    @Test fun renderActualAppVisibilitySettingsForCodexPreview() {
        val output = System.getenv("MASCOT3_OVERLAY_OUTPUT")?.let(::File) ?: return
        OverlayAppRules(OverlayAppMode.HIDE_SELECTED, setOf("org.example.bank")).save(context)
        usageAccess(false)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent {
                com.generativemascot.app.ui.MascotTheme {
                    Surface { Box(Modifier.padding(16.dp)) { OverlayAppRulesSection(resumed = true) } }
                }
            }
            val view = controller.get().window.decorView
            repeat(10) {
                view.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 390, 844)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                Thread.sleep(10)
            }
            val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(output, "app-rules-settings.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun foregroundEventsHandleActivitySwitchesHomeLockAndOverlappingQueries() {
        val tracker = OverlayForegroundTracker()
        fun event(type: Int, pkg: String?, cls: String?, time: Long) = tracker.accept(type, pkg, cls, time)
        event(UsageEvents.Event.ACTIVITY_RESUMED, "chat", "Main", 100)
        assertEquals("chat", tracker.packageName)
        event(UsageEvents.Event.ACTIVITY_RESUMED, "bank", "Main", 200)
        event(UsageEvents.Event.ACTIVITY_PAUSED, "chat", "Main", 201)
        assertEquals("bank", tracker.packageName) // Late pause of the previous app must not clear the new app.
        event(UsageEvents.Event.ACTIVITY_RESUMED, "bank", "Transfer", 300)
        event(UsageEvents.Event.ACTIVITY_STOPPED, "bank", "Main", 301)
        assertEquals("bank", tracker.packageName)
        event(UsageEvents.Event.ACTIVITY_PAUSED, "bank", "Transfer", 400)
        assertNull(tracker.packageName)
        event(UsageEvents.Event.ACTIVITY_RESUMED, "home", "Launcher", 400)
        assertEquals("home", tracker.packageName) // Different events may share a timestamp.
        event(UsageEvents.Event.ACTIVITY_PAUSED, "bank", "Transfer", 400)
        event(UsageEvents.Event.ACTIVITY_RESUMED, "bank", "Transfer", 300)
        assertEquals("home", tracker.packageName) // Overlap cannot replay an old app.
        event(UsageEvents.Event.KEYGUARD_SHOWN, null, null, 500)
        assertNull(tracker.packageName)
        event(UsageEvents.Event.USER_INTERACTION, "bank", "Transfer", 600)
        assertNull(tracker.packageName)
        event(UsageEvents.Event.ACTIVITY_RESUMED, "home", "Launcher", 550)
        assertEquals("home", tracker.packageName) // Non-lifecycle events do not advance the cursor.
        event(UsageEvents.Event.SCREEN_NON_INTERACTIVE, null, null, 700)
        assertNull(tracker.packageName)
    }

    private fun usageAccess(allowed: Boolean) {
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName,
            if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }

    private fun foregroundEvent(pkg: String, type: Int = UsageEvents.Event.ACTIVITY_RESUMED): Long {
        Thread.sleep(2)
        val now = System.currentTimeMillis()
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).addEvent(
            ShadowUsageStatsManager.EventBuilder.buildEvent().setPackage(pkg).setClass("Main")
                .setEventType(type).setTimeStamp(now).build())
        return now
    }

    @Test fun usageReaderRecoversAfterGrantAndClearsCachedAppAfterRevocation() {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        val reader = OverlayForegroundApps(context)
        val now = foregroundEvent("org.example.chat")
        usageAccess(false)
        assertNull(reader.currentPackage(now + 1))
        usageAccess(true)
        assertEquals("org.example.chat", reader.currentPackage(now + 2))
        assertEquals("org.example.chat", reader.currentPackage(now + 3)) // No event does not mean no foreground app.
        usageAccess(false)
        assertNull(reader.currentPackage(now + 4))
        val next = foregroundEvent("org.example.bank")
        usageAccess(true)
        assertEquals("org.example.bank", reader.currentPackage(next + 10))
        val paused = foregroundEvent("org.example.bank", UsageEvents.Event.ACTIVITY_PAUSED)
        assertNull(reader.currentPackage(paused + 20))
    }

    @Test fun serviceAppRulesHideBothWindowsAndCompactWithoutDisablingTheSession() {
        val folder = savedHero()
        ShadowSettings.setCanDrawOverlays(true)
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsInteractive(true)
        usageAccess(true)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        foregroundEvent("org.example.chat")
        OverlayAppRules(OverlayAppMode.HIDE_SELECTED, setOf("org.example.bank")).save(context)
        val looper = shadowOf(Looper.getMainLooper())
        val controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        val service = controller.get()
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
        fun waitFor(message: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000
            while (!predicate() && System.nanoTime() < deadline) {
                looper.idleFor(Duration.ofMillis(100))
                Thread.sleep(10)
            }
            assertTrue(message, predicate())
        }
        try {
            service.onStartCommand(Intent().putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
            awaitOverlay()
            val hero = field("hero") as View
            val card = field("bubble") as View
            val compact = field("compact") as View
            waitFor("Allowed app should show both windows") { hero.isAttachedToWindow && card.isAttachedToWindow }
            val before = (hero.layoutParams as WindowManager.LayoutParams).let { it.x to it.y }
            foregroundEvent("org.example.bank")
            waitFor("Selected app should hide all windows") { !hero.isAttachedToWindow && !card.isAttachedToWindow }
            assertFalse(compact.isAttachedToWindow)
            assertEquals(heroId, OverlaySession.hero(context)); assertTrue(MascotOverlayService.status.value.running)
            assertNotNull(field("appMonitor"))
            foregroundEvent("org.example.chat")
            waitFor("Leaving selected app should restore") { hero.isAttachedToWindow }
            assertEquals(before, (hero.layoutParams as WindowManager.LayoutParams).let { it.x to it.y })
            card.findViewWithTag<View>("collapse").performClick()
            looper.idleFor(Duration.ofMillis(32))
            assertTrue(compact.isAttachedToWindow)
            foregroundEvent("org.example.bank")
            waitFor("Compact card also hides") { !compact.isAttachedToWindow }
            foregroundEvent("org.example.home")
            waitFor("Compact card should restore as compact") { compact.isAttachedToWindow }
            assertTrue(hero.isAttachedToWindow)
            OverlayAppRules(OverlayAppMode.ONLY_SELECTED, setOf("org.example.bank")).save(context)
            waitFor("Switching rule while running applies immediately") { !compact.isAttachedToWindow }
            foregroundEvent("org.example.bank")
            waitFor("Only-selected should show in selected app") { compact.isAttachedToWindow }
            usageAccess(false)
            waitFor("Revoking access hides instead of showing in an unknown app") { !compact.isAttachedToWindow }
            usageAccess(true)
            waitFor("Grant restores without restarting service") { compact.isAttachedToWindow }
            power.setIsInteractive(false)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            looper.idleFor(Duration.ofMillis(32))
            assertNull(field("appMonitor")); assertFalse(compact.isAttachedToWindow)
            power.setIsInteractive(true)
            foregroundEvent("org.example.home")
            context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            looper.idleFor(Duration.ofMillis(32))
            waitFor("Monitor should restart after unlock") { field("appMonitor") != null }
            // The IO query can complete on a later traversal than starting its job.
            waitFor("Unlock should resolve the home app and hide the card") {
                field("foregroundPackage") == "org.example.home" && !compact.isAttachedToWindow
            }
            assertFalse(compact.isAttachedToWindow)
            OverlayAppRules().save(context)
            waitFor("Everywhere restores without usage access or tracking") { compact.isAttachedToWindow }
            assertNull(field("appMonitor"))
            service.onStartCommand(Intent().setAction(MascotOverlayService.ACTION_STOP), 0, 2)
            assertNull(field("appMonitor")); assertFalse(compact.isAttachedToWindow)
        } finally { controller.destroy(); folder.deleteRecursively() }
    }

    @Test fun delayedUnlockRestoresFullAndCompactWindowsWithoutAnotherBroadcast() {
        val folder = savedHero()
        ShadowSettings.setCanDrawOverlays(true)
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        val looper = shadowOf(Looper.getMainLooper())
        power.setIsInteractive(true)
        val controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        val service = controller.get()
        fun view(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service) as View
        try {
            service.onStartCommand(Intent().putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
            awaitOverlay()
            val hero = view("hero"); val card = view("bubble"); val compact = view("compact")
            // Exercise full / compact / full, repeated unlock broadcasts and a
            // KeyguardManager state that clears AFTER USER_PRESENT was delivered.
            listOf(false, true, false).forEach { collapsed ->
                if (collapsed) card.findViewWithTag<View>("collapse").performClick()
                else if (compact.isAttachedToWindow) compact.performClick()
                looper.idleFor(Duration.ofMillis(32))
                val visible = if (collapsed) compact else hero
                val before = (visible.layoutParams as WindowManager.LayoutParams).let { it.x to it.y }
                power.setIsInteractive(false); keyguard.setKeyguardLocked(true)
                context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
                looper.idleFor(Duration.ofMillis(32))
                assertFalse(hero.isAttachedToWindow); assertFalse(card.isAttachedToWindow); assertFalse(compact.isAttachedToWindow)
                power.setIsInteractive(true)
                context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
                context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
                looper.idleFor(Duration.ofSeconds(3))
                assertFalse(visible.isAttachedToWindow)
                // No second broadcast: the pending visibility check must recover.
                keyguard.setKeyguardLocked(false)
                looper.idleFor(Duration.ofMillis(1_100))
                assertTrue(visible.isAttachedToWindow)
                assertEquals(!collapsed, card.isAttachedToWindow)
                assertEquals(before, (visible.layoutParams as WindowManager.LayoutParams).let { it.x to it.y })
                context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
                looper.idleFor(Duration.ofMillis(32))
                assertTrue(visible.isAttachedToWindow)
                assertFalse(shadowOf(service).isStoppedBySelf)
            }
            // A pending unlock retry must not resurrect a window after screen off.
            keyguard.setKeyguardLocked(true)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
            looper.idleFor(Duration.ofMillis(32))
            power.setIsInteractive(false)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            keyguard.setKeyguardLocked(false)
            looper.idleFor(Duration.ofSeconds(3))
            assertFalse(hero.isAttachedToWindow)
            assertFalse((service.javaClass.getDeclaredField("motionRunning").apply { isAccessible = true }.get(service)) as Boolean)
            assertTrue(MascotOverlayService.status.value.running)
        } finally { controller.destroy(); folder.deleteRecursively() }
    }

    @Test fun stickyRestartWhileLockedRestoresTheSelectedHeroAndCompactState() {
        val selectedId = "a83e9d30-1270-40e6-902c-b44774e84c17"
        val folder = savedHero(selectedId)
        ShadowSettings.setCanDrawOverlays(true)
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsInteractive(true)
        val looper = shadowOf(Looper.getMainLooper())
        var controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        var service = controller.get()
        fun view(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service) as View
        try {
            assertEquals(Service.START_STICKY, service.onStartCommand(
                Intent().putExtra(MascotOverlayService.EXTRA_HERO, selectedId), 0, 1))
            awaitOverlay()
            OverlaySize.save(context, 1.25f)
            looper.idleFor(Duration.ofMillis(32))
            view("hero").performLongClick() // Hidden tip survives process loss as well.
            view("bubble").findViewWithTag<View>("collapse").performClick()
            looper.idleFor(Duration.ofMillis(32))
            val oldHero = view("hero")
            val oldCompact = view("compact")
            val before = (oldCompact.layoutParams as WindowManager.LayoutParams).let { it.x to it.y }
            controller.destroy() // Lifecycle cleanup must not clear the user's enabled mode.
            assertEquals(selectedId, OverlaySession.hero(context))
            power.setIsInteractive(false)
            controller = Robolectric.buildService(MascotOverlayService::class.java).create()
            service = controller.get()
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            awaitOverlay()
            assertNotSame(oldHero, view("hero"))
            assertFalse(view("hero").isAttachedToWindow); assertFalse(view("compact").isAttachedToWindow)
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals(1.2f, OverlaySize.read(context))
            power.setIsInteractive(true)
            context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            looper.idleFor(Duration.ofMillis(32))
            val compact = view("compact")
            assertTrue(compact.isAttachedToWindow)
            assertEquals(before, (compact.layoutParams as WindowManager.LayoutParams).let { it.x to it.y })
            compact.performClick()
            looper.idleFor(Duration.ofMillis(32))
            assertTrue(view("hero").isAttachedToWindow)
            assertTrue(view("bubble").isAttachedToWindow)
            assertEquals(selectedId, service.javaClass.getDeclaredField("activeHeroId").apply { isAccessible = true }.get(service))
            // A repeated system restore must keep the same view and player.
            val restoredHero = view("hero")
            assertEquals(Service.START_STICKY, service.onStartCommand(Intent().setAction(MascotOverlayService.ACTION_RESTORE), 0, 2))
            assertSame(restoredHero, view("hero"))
        } finally { controller.destroy(); folder.deleteRecursively() }
    }

    @Test fun everyExplicitDismissalPreventsStaleRestartsAndUnlockCallbacks() {
        val folder = savedHero()
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        val looper = shadowOf(Looper.getMainLooper())
        fun labels(view: View): List<TextView> = if (view is TextView) listOf(view)
            else if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()
        try {
            listOf("notification", "settings").forEach { route ->
                keyguard.setKeyguardLocked(false)
                var controller = Robolectric.buildService(MascotOverlayService::class.java).create()
                var service = controller.get()
                try {
                    service.onStartCommand(Intent().putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
                    awaitOverlay()
                    val hero = service.javaClass.getDeclaredField("hero").apply { isAccessible = true }.get(service) as View
                    val card = service.javaClass.getDeclaredField("bubble").apply { isAccessible = true }.get(service) as View
                    keyguard.setKeyguardLocked(true)
                    context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
                    looper.idleFor(Duration.ofMillis(32))
                    when (route) {
                        "notification" -> service.onStartCommand(Intent().setAction(MascotOverlayService.ACTION_STOP), 0, 2)
                        else -> MascotOverlayService.stop(context)
                    }
                    assertNull(OverlaySession.hero(context))
                    controller.destroy()
                    keyguard.setKeyguardLocked(false)
                    looper.idleFor(Duration.ofSeconds(2))
                    assertFalse(hero.isAttachedToWindow)
                    controller = Robolectric.buildService(MascotOverlayService::class.java).create()
                    service = controller.get()
                    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
                    assertTrue(shadowOf(service).isStoppedBySelf)
                    assertNull(shadowOf(service).lastForegroundNotification)
                    assertNull(MascotOverlayService.status.value.error)
                    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
                        Intent().setAction(MascotOverlayService.ACTION_RESTORE), 0, 2))
                } finally { controller.destroy() }
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun bootAndUpdateRestoreOnlyEnabledPermittedSessions() {
        ShadowSettings.setCanDrawOverlays(true)
        val app = shadowOf(context)
        val receiver = OverlayRestoreReceiver()
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
            OverlaySession.disable(context)
            receiver.onReceive(context, Intent(action))
            assertNull(app.nextStartedService)
            OverlaySession.enable(context, heroId)
            receiver.onReceive(context, Intent(action))
            val start = app.nextStartedService
            assertEquals(MascotOverlayService::class.java.name, start.component?.className)
            assertEquals(MascotOverlayService.ACTION_RESTORE, start.action)
            // The receiver queues a gated restore, not a stale explicit hero start.
            assertFalse(start.hasExtra(MascotOverlayService.EXTRA_HERO))
            OverlaySession.disable(context)
            val controller = Robolectric.buildService(MascotOverlayService::class.java).create()
            try {
                assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(start, 0, 1))
                assertNull(shadowOf(controller.get()).lastForegroundNotification)
            } finally { controller.destroy() }
        }
        OverlaySession.enable(context, heroId)
        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))
        assertNull(app.nextStartedService) // Never launch an FGS from an arbitrary unlock broadcast.
        ShadowSettings.setCanDrawOverlays(false)
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(app.nextStartedService); assertNull(OverlaySession.hero(context))
        ShadowSettings.setCanDrawOverlays(true)
        context.getSharedPreferences(OverlaySize.PREFERENCES, 0).edit()
            .putBoolean("enabled", true).putString("hero_id", "../invalid").apply()
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(app.nextStartedService)
    }

    @Test fun heroAndCardRemainReachableAfterDragAndRotation() {
        listOf(OverlayArea(8, 36, 382, 812), OverlayArea(36, 8, 812, 382), OverlayArea(8, 40, 312, 432)).forEach { area ->
            listOf(-300 to -500, 9999 to 9999, 180 to 200).forEach { (x, y) ->
                val p = OverlayLayout.clamp(area, 134, 210, x, y)
                assertTrue(p.x >= area.left && p.x + 134 <= area.right)
                assertTrue(p.y >= area.top && p.y + 210 <= area.bottom)
                val b = OverlayLayout.bubble(area, p, 134, 216, 180, 2)
                assertTrue(b.x >= area.left && b.x + 216 <= area.right)
                assertTrue(b.y >= area.top && b.y + 180 <= area.bottom)
                val (fx, fy) = OverlayLayout.fractions(area, 134, 210, p)
                assertEquals(p, OverlayLayout.restore(area, 134, 210, fx, fy))
            }
        }
        val safe = OverlayLayout.restore(OverlayArea(8, 36, 382, 812), 134, 210, Float.NaN, Float.POSITIVE_INFINITY)
        assertTrue(safe.x in 8..248 && safe.y in 36..602)
    }

    @Test fun missingPermissionDoesNotCreateAWindowOrForegroundNotification() {
        ShadowSettings.setCanDrawOverlays(false)
        val controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent(context, MascotOverlayService::class.java)
                .putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertNull(shadowOf(service).lastForegroundNotification)
            assertFalse(MascotOverlayService.status.value.running)
        } finally { controller.destroy() }
    }

    @Test fun actualServiceUsesTwoSmallWindowsAndCleansUpOnLockAndStop() {
        val source = savedHero()
        val original = File(source, "base.png").readBytes()
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        val service = controller.get()
        fun field(name: String): Any? = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
        try {
            service.onStartCommand(Intent(context, MascotOverlayService::class.java)
                .putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
            val deadline = System.nanoTime() + 10_000_000_000
            while (MascotOverlayService.status.value.loading && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayStatus(running = true), MascotOverlayService.status.value)
            val hero = field("hero") as View
            val card = field("bubble") as View
            assertTrue(hero.isAttachedToWindow)
            assertTrue(card.isAttachedToWindow)
            val params = hero.layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0)
            assertEquals(134, params.width)
            assertEquals(210, params.height)
            assertNotNull(shadowOf(service).lastForegroundNotification)
            var taps = 0
            hero.setOnClickListener { taps++ }
            // A drag must not be mistaken for a click; the card remains shown.
            fun touch(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(0, 100, action, x, y, 0)
                hero.dispatchTouchEvent(event); event.recycle()
            }
            touch(MotionEvent.ACTION_DOWN, 20f, 30f)
            touch(MotionEvent.ACTION_MOVE, 600f, -800f)
            touch(MotionEvent.ACTION_UP, 600f, -800f)
            assertTrue(card.isAttachedToWindow)
            assertEquals(0, taps)
            val draggedBody = OverlayFraming.visibleBounds(field("artwork") as OverlayArtwork, params.width, params.height)
            assertTrue(params.x + draggedBody.left >= 0 && params.y + draggedBody.top >= 0)
            touch(MotionEvent.ACTION_DOWN, 20f, 30f)
            touch(MotionEvent.ACTION_UP, 20f, 30f)
            assertEquals(1, taps); assertTrue(card.isAttachedToWindow)
            touch(MotionEvent.ACTION_DOWN, 20f, 30f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1L))
            assertFalse(card.isAttachedToWindow)
            touch(MotionEvent.ACTION_UP, 20f, 30f)
            assertEquals(1, taps) // A long press must not also trigger a reaction.
            hero.performLongClick(); shadowOf(Looper.getMainLooper()).idle(); assertTrue(card.isAttachedToWindow)
            touch(MotionEvent.ACTION_DOWN, 20f, 30f) // Lock must cancel a pending long press.
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1L))
            assertFalse(hero.isAttachedToWindow)
            assertFalse(card.isAttachedToWindow)
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
            context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(hero.isAttachedToWindow)
            assertTrue(card.isAttachedToWindow)
            val art = field("artwork") as OverlayArtwork
            service.onStartCommand(Intent().setAction(MascotOverlayService.ACTION_STOP), 0, 2)
            assertTrue(shadowOf(service).isStoppedBySelf)
            controller.destroy()
            assertFalse(hero.isAttachedToWindow)
            assertFalse(card.isAttachedToWindow)
            assertTrue(art.frames.all { it.isRecycled })
            assertTrue(shadowOf(service).isForegroundStopped)
            assertArrayEquals(original, File(source, "base.png").readBytes())
        } finally {
            if (field("hero") != null) controller.destroy()
            source.deleteRecursively()
        }
    }

    @Test fun dismissalDuringDecodeCannotResurrectAnOverlay() {
        val folder = savedHero()
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        controller.get().onStartCommand(Intent().putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
        controller.destroy()
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(MascotOverlayService.status.value.running)
        folder.deleteRecursively()
    }

    @Test fun videoCanvasMarginsDoNotStopHeroGrowingBeforeTheCard() {
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val art = OverlayArtwork(listOf(bitmap), Rect(130, 90, 190, 250), 100,
            OverlayVideo(File("idle.mp4"), Color.GREEN))
        try {
            val area = OverlayArea(0, 0, 390, 800)
            val small = OverlaySize.dimensions(.7f, 1f, area, art)
            val large = OverlaySize.dimensions(1.2f, 1f, area, art)
            val smallBody = OverlayFraming.visibleBounds(art, small.width, small.height)
            val largeBody = OverlayFraming.visibleBounds(art, large.width, large.height)
            val ratio = OverlaySize.effectiveScale(1.2f, 1f, area, art) /
                OverlaySize.effectiveScale(.7f, 1f, area, art)
            assertEquals(ratio, largeBody.height() / smallBody.height(), .02f)
            assertTrue(largeBody.height() > smallBody.height() * 1.65f)
            assertTrue(large.width > largeBody.width()) // transparent pixels may leave the display
            val placement = OverlayPlacement.hero(area, large, art, 0)
            val point = placement.clamp(0, 9999)
            assertTrue(point.y + largeBody.bottom <= area.bottom + 1)
        } finally { art.recycle() }
    }

    @Test fun stoppingOverlayDisablesRestoration() {
        val folder = savedHero()
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent().putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
            awaitOverlay()
            val card = service.javaClass.getDeclaredField("bubble").apply { isAccessible = true }.get(service) as View
            assertNotNull(OverlaySession.hero(context))
            assertNull(card.findViewWithTag<View>("hide-overlay"))
            service.onStartCommand(Intent().setAction(MascotOverlayService.ACTION_STOP), 0, 2)
            assertNull(OverlaySession.hero(context))
            assertFalse(card.isAttachedToWindow)
        } finally { controller.destroy(); folder.deleteRecursively() }
    }

    @Test fun firstOverlayWindowDoesNotWaitForDepthLoading() {
        val folder = savedHero()
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            val service = controller.get()
            service.loadPhysics = { entered.complete(Unit); gate.await(); null }
            service.onStartCommand(Intent().putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
            awaitOverlay()
            val deadline = System.nanoTime() + 3_000_000_000
            while (!entered.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                Thread.sleep(20)
            }
            assertTrue("Depth loading must actually have started", entered.isCompleted)
            val hero = service.javaClass.getDeclaredField("hero").apply { isAccessible = true }.get(service) as View
            assertTrue("First frame must appear while the depth pack is still loading", hero.isAttachedToWindow)
            assertFalse(gate.isCompleted)
        } finally { gate.complete(Unit); controller.destroy(); folder.deleteRecursively() }
    }

    @Test fun sizesKeepProportionsStayOnScreenAndKeepFeetAnchored() {
        val area = OverlayArea(8, 36, 382, 812)
        assertEquals(OverlayDimensions(94, 147), OverlaySize.dimensions(.7f, 1f, area))
        assertEquals(OverlayDimensions(161, 252), OverlaySize.dimensions(1.5f, 1f, area))
        assertEquals(1f, OverlaySize.normalize(Float.NaN))
        listOf(area, OverlayArea(36, 8, 812, 382), OverlayArea(0, 0, 120, 150)).forEach { available ->
            listOf(-1f, .7f, 1f, 1.5f, 20f, Float.POSITIVE_INFINITY).forEach { scale ->
                val size = OverlaySize.dimensions(scale, 3f, available)
                assertTrue(size.width in 1..available.width && size.height in 1..available.height)
                assertEquals(134f / 210f, size.width.toFloat() / size.height, .01f)
            }
        }
        val small = OverlayDimensions(134, 210); val large = OverlayDimensions(201, 315)
        assertEquals(OverlayPoint(12, 295), OverlaySize.reposition(area, OverlayPoint(12, 400), small, large))
        assertEquals(OverlayPoint(181, 295), OverlaySize.reposition(area, OverlayPoint(248, 400), small, large))
    }

    @Test fun resizingAndCollapsingPreserveHeroAndPersistWithoutInvisibleWindows() {
        val folder = savedHero()
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        var controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        var service = controller.get()
        fun field(name: String): Any? = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
        fun start() {
            service.onStartCommand(Intent().putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
            val deadline = System.nanoTime() + 10_000_000_000
            while (MascotOverlayService.status.value.loading && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(MascotOverlayService.status.value.running)
        }
        try {
            start()
            val hero = field("hero") as View
            val card = field("bubble") as View
            val art = field("artwork") as OverlayArtwork
            OverlaySize.save(context, 1.2f)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(hero, field("hero")); assertSame(art, field("artwork"))
            assertEquals(161, (hero.layoutParams as WindowManager.LayoutParams).width)
            assertEquals(252, (hero.layoutParams as WindowManager.LayoutParams).height)
            card.findViewWithTag<View>("collapse").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val compact = field("compact") as View
            assertTrue(hero.isAttachedToWindow); assertFalse(card.isAttachedToWindow)
            assertTrue(compact.isAttachedToWindow)
            assertEquals(48, (compact.layoutParams as WindowManager.LayoutParams).width)
            assertEquals(48, (compact.layoutParams as WindowManager.LayoutParams).height)
            assertFalse(art.frames.first().isRecycled)
            fun touch(action: Int, x: Float, y: Float) {
                MotionEvent.obtain(0, 100, action, x, y, 0).also { compact.dispatchTouchEvent(it); it.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 10f, 20f)
            touch(MotionEvent.ACTION_MOVE, 6000f, 8000f)
            touch(MotionEvent.ACTION_UP, 6000f, 8000f)
            assertTrue(compact.isAttachedToWindow); assertTrue(hero.isAttachedToWindow)
            OverlaySize.save(context, .7f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(48, (compact.layoutParams as WindowManager.LayoutParams).width)
            compact.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(compact.isAttachedToWindow)
            assertTrue(hero.isAttachedToWindow); assertTrue(card.isAttachedToWindow)
            val p = hero.layoutParams as WindowManager.LayoutParams
            assertEquals(94, p.width); assertEquals(147, p.height)
            val restoredBody = OverlayFraming.visibleBounds(field("artwork") as OverlayArtwork, p.width, p.height)
            assertTrue(p.x + restoredBody.left >= 0 && p.x + restoredBody.right <= 390 && p.y + restoredBody.bottom <= 844)
            card.findViewWithTag<View>("collapse").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(compact.isAttachedToWindow)
            context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(compact.isAttachedToWindow); assertTrue(hero.isAttachedToWindow)
            controller.destroy()
            assertTrue(art.frames.all { it.isRecycled })
            controller = Robolectric.buildService(MascotOverlayService::class.java).create()
            service = controller.get()
            start()
            assertEquals(.7f, OverlaySize.read(context))
            assertTrue((field("compact") as View).isAttachedToWindow)
            assertTrue((field("hero") as View).isAttachedToWindow)
            assertEquals(94, (field("heroParams") as WindowManager.LayoutParams).width)
        } finally { controller.destroy(); folder.deleteRecursively() }
    }

    @Test fun savedImagesAreNormalizedWithoutGeneratingOrChangingMedia() {
        listOf(heroId, "a83e9d30-1270-40e6-902c-b44774e84c17", "716cdb43-703b-4d7e-8a79-27f03d92b99c").forEach { id ->
            val folder = savedHero(id)
            val before = File(folder, "base.png").readBytes()
            val art = loadOverlayArtwork(context, id)
            try {
                assertFalse(art.bounds.isEmpty)
                assertTrue(art.frames.sumOf { it.allocationByteCount } < 20 * 1024 * 1024)
                assertTrue(art.frames.all { maxOf(it.width, it.height) <= 320 })
                assertArrayEquals(before, File(folder, "base.png").readBytes())
            } finally { art.recycle(); folder.deleteRecursively() }
        }
        assertTrue(runCatching { loadOverlayArtwork(context, "../escape") }.isFailure)
    }

    @Test fun pinchResizesExistingVideoWithoutTapHoldOrPointerLiftJump() {
        val folder = savedHero()
        val videos = File(folder, "videos").apply { mkdirs() }
        val clip = File(videos, "idle.mp4")
        context.assets.open("bundled-heroes/$heroId/videos/idle.mp4").use { input ->
            clip.outputStream().use(input::copyTo)
        }
        File(videos, "matte-color.txt").writeText("00ff00")
        val frame = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        Canvas(frame).drawRect(50f, 30f, 150f, 280f, Paint().apply { color = Color.RED })
        ShadowMediaMetadataRetriever.addFrame(clip.absolutePath, 0L, frame)
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        val service = controller.get()
        fun field(name: String): Any? = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
        try {
            service.onStartCommand(Intent().putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
            val deadline = System.nanoTime() + 10_000_000_000
            while (MascotOverlayService.status.value.loading && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(MascotOverlayService.status.value.running)
            val hero = field("hero") as View
            val card = field("bubble") as View
            fun player() = hero.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(hero)
            val originalPlayer = player()
            assertNotNull(originalPlayer)
            var taps = 0; var holds = 0
            hero.setOnClickListener { taps++ }; hero.setOnLongClickListener { holds++; true }
            fun params() = hero.layoutParams as WindowManager.LayoutParams
            val initialWidth = params().width
            // Keep both pointers in this small overlay, even when the second is outside it.
            assertEquals(0, params().flags and WindowManager.LayoutParams.FLAG_SPLIT_TOUCH)
            var time = 10L
            var touchTarget: View = hero
            fun touch(action: Int, vararg pointers: Pair<Int, Pair<Float, Float>>) {
                time += 20
                val properties = pointers.map { (id, _) ->
                    MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
                }.toTypedArray()
                val coordinates = pointers.map { (_, point) ->
                    MotionEvent.PointerCoords().apply { x = point.first; y = point.second; pressure = 1f; size = 1f }
                }.toTypedArray()
                val event = MotionEvent.obtain(0, time, action, pointers.size, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                // Window-local coordinates change during a pinch; raw screen coordinates must not.
                val origin = touchTarget.layoutParams as WindowManager.LayoutParams
                event.offsetLocation(-origin.x.toFloat(), -origin.y.toFloat())
                touchTarget.dispatchTouchEvent(event); event.recycle()
                shadowOf(Looper.getMainLooper()).idle()
            }
            touch(MotionEvent.ACTION_DOWN, 4 to (40f to 450f))
            touch(MotionEvent.ACTION_MOVE, 4 to (145f to 380f))
            touch(MotionEvent.ACTION_UP, 4 to (145f to 380f))
            val centerX = params().x + params().width / 2f
            val centerY = params().y + params().height / 2f
            val upSecond = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            val downSecond = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            fun pairTouch(action: Int, span: Float) = touch(action,
                4 to (centerX to centerY - span / 2), 9 to (centerX to centerY + span / 2))
            touch(MotionEvent.ACTION_DOWN, 4 to (centerX to centerY - 50))
            pairTouch(downSecond, 100f)
            pairTouch(MotionEvent.ACTION_MOVE, 110f) // The first movement already resizes.
            pairTouch(MotionEvent.ACTION_MOVE, 117f)
            val enlarged = params().width
            assertTrue("Pinch width: $enlarged", enlarged > initialWidth && enlarged < initialWidth * 1.5f)
            assertEquals(centerX, params().x + params().width / 2f, 1f)
            assertEquals(centerY, params().y + params().height / 2f, 1f)
            assertSame(originalPlayer, player())
            assertTrue(card.isAttachedToWindow)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1L))
            assertEquals(0, holds)
            pairTouch(upSecond, 117f)
            val afterPinch = field("position")
            touch(MotionEvent.ACTION_MOVE, 4 to (centerX + 100 to centerY))
            touch(MotionEvent.ACTION_UP, 4 to (centerX + 100 to centerY))
            assertEquals(afterPinch, field("position")); assertEquals(0, taps)
            val saved = OverlaySize.read(context)
            assertTrue(saved in 1.01f..1.20f)
            assertTrue(kotlin.math.abs(saved - OverlaySize.normalize(saved)) > .001f) // No 5% stepping.
            assertEquals(enlarged, params().width)
            // A fresh one-finger tap still triggers exactly one reaction.
            touch(MotionEvent.ACTION_DOWN, 4 to (centerX to centerY))
            touch(MotionEvent.ACTION_UP, 4 to (centerX to centerY))
            assertEquals(1, taps)

            touchTarget = card
            val cardWidthBefore = (card.layoutParams as WindowManager.LayoutParams).width
            touch(MotionEvent.ACTION_DOWN, 4 to (centerX to centerY - 90))
            pairTouch(downSecond, 180f)
            pairTouch(MotionEvent.ACTION_MOVE, 140f)
            pairTouch(MotionEvent.ACTION_MOVE, 60f)
            pairTouch(upSecond, 60f)
            touch(MotionEvent.ACTION_UP, 4 to (centerX to centerY - 30))
            assertEquals(cardWidthBefore, (card.layoutParams as WindowManager.LayoutParams).width)
            touchTarget = hero
            assertEquals(.7f, OverlaySize.read(context))
            assertEquals(initialWidth * .7f, params().width.toFloat(), 1f)
            assertSame(originalPlayer, player())

            // Cancel/save a gesture in progress on lock; stale events after unlock are ignored.
            touch(MotionEvent.ACTION_DOWN, 4 to (centerX to centerY - 50))
            pairTouch(downSecond, 100f)
            pairTouch(MotionEvent.ACTION_MOVE, 140f)
            pairTouch(MotionEvent.ACTION_MOVE, 800f)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1.2f, OverlaySize.read(context))
            assertFalse(hero.isAttachedToWindow)
            context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(hero.isAttachedToWindow)
            assertEquals(initialWidth * 1.2f, params().width.toFloat(), 1f)
            val restored = field("position")
            touch(MotionEvent.ACTION_UP, 4 to (centerX to centerY))
            assertEquals(1, taps); assertEquals(restored, field("position"))
            val p = params()
            val visible = OverlayFraming.visibleBounds(field("artwork") as OverlayArtwork, p.width, p.height)
            assertTrue(p.x + visible.left >= 8 && p.x + visible.right <= 382)
            assertTrue(p.y + visible.top >= 0 && p.y + visible.bottom <= 844)

            // Collapse/expand keeps the chosen size.
            card.findViewWithTag<View>("collapse").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val compact = field("compact") as View
            assertTrue("Minus keeps the hero attached", hero.isAttachedToWindow)
            assertFalse("Minus hides only the dialog", card.isAttachedToWindow)
            val retainedPosition = field("position")
            val compactParams = compact.layoutParams as WindowManager.LayoutParams
            assertEquals(48, compactParams.width); assertEquals(48, compactParams.height)
            compact.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(retainedPosition, field("position"))
            assertTrue(card.isAttachedToWindow)
            assertEquals(initialWidth * 1.2f, params().width.toFloat(), 1f)
            // The card owns drag and pinch; the visible body is not an input target.
            OverlaySize.save(context, 1.2f)
            shadowOf(Looper.getMainLooper()).idle()
            listOf(hero, card).forEach { view ->
                val vp = view.layoutParams as WindowManager.LayoutParams
                view.measure(View.MeasureSpec.makeMeasureSpec(vp.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(vp.height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, vp.width, vp.height)
            }
            val activePlayer = player()
            assertNull(field("gestureWindow"))
            val hp = params()
            val cp = card.layoutParams as WindowManager.LayoutParams
            val body = (hero as OverlayHeroView).hintBounds(hp.width, hp.height)
            val hostParams = (field("heroWindow") as View).layoutParams as WindowManager.LayoutParams
            assertTrue(hostParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue("Keep the hero as opaque as Android permits", hostParams.alpha >= .75f && hostParams.alpha <= .8f)
            val heroX = (hp.x + body.centerX()).toInt()
            val heroY = (hp.y + body.top + 10f).toInt()
            assertFalse("The card must not capture the hero's head", (card as OverlayHintView).touchRegion().apply { translate(cp.x, cp.y) }.contains(heroX, heroY))
            val x = cp.x + cp.width * .45f
            val y = cp.y + cp.height * .65f
            assertTrue((card as OverlayHintView).touchRegion().contains((x - cp.x).toInt(), (y - cp.y).toInt()))
            touchTarget = card
            touch(MotionEvent.ACTION_DOWN, 4 to (x to y))
            touch(downSecond, 4 to (x to y), 9 to (x + 500f to y))
            touch(MotionEvent.ACTION_MOVE, 4 to (x to y), 9 to (x + 250f to y))
            touch(upSecond, 4 to (x to y), 9 to (x + 250f to y))
            touch(MotionEvent.ACTION_UP, 4 to (x to y))
            assertEquals(.7f, OverlaySize.read(context), .001f)
            assertSame(activePlayer, player())
            val currentCard = card.layoutParams as WindowManager.LayoutParams
            val cx = currentCard.x + currentCard.width * .4f
            val cy = currentCard.y + currentCard.height * .35f
            val beforeDrag = field("position")
            touch(MotionEvent.ACTION_DOWN, 4 to (cx to cy))
            touch(MotionEvent.ACTION_MOVE, 4 to (cx + 30f to cy + 30f))
            touch(MotionEvent.ACTION_UP, 4 to (cx + 30f to cy + 30f))
            assertNotEquals("Dragging the card moves the hero", beforeDrag, field("position"))
            assertTrue(hero.isAttachedToWindow && card.isAttachedToWindow)
        } finally { controller.destroy(); frame.recycle(); folder.deleteRecursively() }
    }

    @Test fun wideVideoDragsToBothEdgesAndKeepsPositionThroughResizeFoldAndRestart() {
        val id = "716cdb43-703b-4d7e-8a79-27f03d92b99c"
        val folder = savedHero(id)
        val videos = File(folder, "videos").apply { mkdirs() }
        val clip = File(videos, "idle.mp4")
        context.assets.open("bundled-heroes/$id/videos/idle.mp4").use { input ->
            clip.outputStream().use(input::copyTo)
        }
        File(videos, "matte-color.txt").writeText("00ff00")
        // Off-centre body in a wide transparent canvas, as in Una's existing clip.
        val frame = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        Canvas(frame).drawRect(120f, 80f, 210f, 250f, Paint().apply { color = Color.RED })
        ShadowMediaMetadataRetriever.addFrame(clip.path, 0L, frame)
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val prefs = context.getSharedPreferences(OverlaySize.PREFERENCES, 0)
        prefs.edit().putFloat("x", .9f).putFloat("y", .58f).apply() // Legacy position from 0.1.71.
        var controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        var service = controller.get()
        fun field(name: String): Any? = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
        fun start() {
            service.onStartCommand(Intent().putExtra(MascotOverlayService.EXTRA_HERO, id), 0, 1)
            awaitOverlay()
        }
        fun params() = (field("hero") as View).layoutParams as WindowManager.LayoutParams
        fun visible() = OverlayFraming.visibleBounds(field("artwork") as OverlayArtwork, params().width, params().height)
        var time = 0L
        fun touch(action: Int, x: Float, y: Float) {
            time += 20
            MotionEvent.obtain(0, time, action, x, y, 0).also {
                it.offsetLocation(-params().x.toFloat(), -params().y.toFloat())
                (field("hero") as View).dispatchTouchEvent(it); it.recycle()
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        try {
            start()
            assertTrue("Transparent video canvas can exceed the display", params().width > 374)
            assertEquals("Migrate without a jump", 8, params().x)
            assertTrue(prefs.getBoolean("body_position", false))
            assertTrue(params().flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
            val card = field("bubble") as View
            assertEquals(0, (card.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            listOf(false, true).forEach { right ->
                val hero = field("hero") as View
                val player = hero.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(hero)
                val beforeWidth = params().width
                val centerX = params().x + visible().centerX()
                val centerY = params().y + visible().centerY()
                touch(MotionEvent.ACTION_DOWN, centerX, centerY)
                touch(MotionEvent.ACTION_MOVE, if (right) 1400f else -1000f, centerY)
                touch(MotionEvent.ACTION_UP, if (right) 1400f else -1000f, centerY)
                assertEquals(beforeWidth, params().width)
                assertSame(player, hero.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(hero))
                if (right) assertEquals(366f, params().x + visible().right, 1f)
                else assertEquals(24f, params().x + visible().left, 1f)
                val edge = field("position") as OverlayPoint
                if (!right) assertTrue("Transparent left padding can leave the display", edge.x < 0)
                val bubbleParams = (field("bubble") as View).layoutParams as WindowManager.LayoutParams
                assertTrue(bubbleParams.x >= 8 && bubbleParams.x + bubbleParams.width <= 382)
                // The same visible side stays anchored when settings change the size.
                listOf(.7f, 1f).forEach { scale ->
                    OverlaySize.save(context, scale)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                    if (right) assertEquals(366f, params().x + visible().right, 1f)
                    else assertEquals(24f, params().x + visible().left, 1f)
                }
                (field("bubble") as View).findViewWithTag<View>("collapse").performClick()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                (field("compact") as View).performClick()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                assertEquals(edge, field("position"))
                service.onConfigurationChanged(service.resources.configuration)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                assertEquals(edge, field("position"))
                controller.destroy()
                controller = Robolectric.buildService(MascotOverlayService::class.java).create()
                service = controller.get()
                start()
                assertEquals("Saved edge survives process restart", edge, field("position"))
            }
        } finally { controller.destroy(); frame.recycle(); folder.deleteRecursively() }
    }

    @Test fun previewShrinksFromMaximumStartingAnywhereAndDoesNotClickButtons() {
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        val art = OverlayArtwork(listOf(bitmap), Rect(40, 20, 160, 280), 100)
        try {
            listOf(20f to 20f, 150f to 150f, 120f to 300f, 300f to 340f).forEach { (x,y) ->
                val preview = OverlayPreviewView(context, art).apply { heroScale = 1.2f; animationEnabled = false }
                preview.measure(View.MeasureSpec.makeMeasureSpec(338, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
                preview.layout(0, 0, 338, 500)
                fun event(action: Int, span: Float, count: Int) {
                    val props = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i } }
                    val coords = Array(count) { i -> MotionEvent.PointerCoords().apply { this.x = x + i * span; this.y = y; pressure = 1f; size = 1f } }
                    MotionEvent.obtain(0, 20, action, count, props, coords, 0, 0, 1f, 1f, 0, 0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also { preview.dispatchTouchEvent(it); it.recycle() }
                }
                event(MotionEvent.ACTION_DOWN, 0f, 1)
                // The second finger can be well outside the preview's own bounds.
                event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 400f, 2)
                event(MotionEvent.ACTION_MOVE, 300f, 2)
                assertEquals(.9f, preview.heroScale, .001f)
                event(MotionEvent.ACTION_UP, 0f, 1)
                assertEquals(.9f, OverlaySize.read(context), .001f)
                assertEquals(View.VISIBLE, preview.getChildAt(0).visibility)
            }
        } finally { art.recycle() }
    }

    @Test fun pinchTracksPointerIdsAndRebasesAfterFingerReplacement() {
        val factors = mutableListOf<Float>()
        var ends = 0
        val gesture = OverlayPinchGesture(8f, { _, _ -> true }, { factor, _, _ -> factors += factor }, { ends++ })
        fun touch(action: Int, vararg pointers: Pair<Int, Float>) {
            val properties = pointers.map { (id, _) ->
                MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }.toTypedArray()
            val coordinates = pointers.map { (_, px) ->
                MotionEvent.PointerCoords().apply { x = px; y = 100f; pressure = 1f; size = 1f }
            }.toTypedArray()
            MotionEvent.obtain(0, 20, action, pointers.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also {
                gesture.onTouchEvent(it); it.recycle()
            }
        }
        val downSecond = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        touch(MotionEvent.ACTION_DOWN, 4 to 20f)
        touch(downSecond, 4 to 20f, 9 to 80f)
        touch(MotionEvent.ACTION_MOVE, 4 to 10f, 9 to 90f)
        touch(MotionEvent.ACTION_MOVE, 9 to 100f, 4 to 0f) // Array order is not pointer identity.
        assertEquals(listOf(80f / 60f, 1.25f), factors)
        touch(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            9 to 100f, 4 to 0f, 13 to 400f)
        touch(MotionEvent.ACTION_MOVE, 9 to 100f, 4 to 0f, 13 to 400f)
        assertEquals(1.25f, factors.last())
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            9 to 100f, 4 to 0f, 13 to 400f)
        assertEquals(1, ends)
        val before = factors.size
        touch(MotionEvent.ACTION_MOVE, 13 to 400f, 9 to 100f)
        assertEquals(before, factors.size) // Replacement must not resize on its own.
        touch(MotionEvent.ACTION_MOVE, 13 to 420f, 9 to 100f)
        touch(MotionEvent.ACTION_MOVE, 13 to 452f, 9 to 100f)
        assertEquals(1.1f, factors.last(), .0001f)
        touch(MotionEvent.ACTION_CANCEL, 9 to 100f, 13 to 452f)
        assertEquals(2, ends)
        val stopped = factors.size
        touch(MotionEvent.ACTION_MOVE, 9 to 100f, 13 to 600f)
        assertEquals(stopped, factors.size)
    }

    @Test fun pinchIgnoresSmallAccidentalChangesBeforeScaling() {
        val factors = mutableListOf<Float>()
        val gesture = OverlayPinchGesture(8f, { _, _ -> true }, { factor, _, _ -> factors += factor }, {})
        fun touch(action: Int, x: Float, y: Float) {
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 1 })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = 0f }, MotionEvent.PointerCoords().apply { this.x = y; this.y = 0f })
            MotionEvent.obtain(0, 20, action, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also {
                gesture.onTouchEvent(it); it.recycle()
            }
        }
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0f, 100f)
        touch(MotionEvent.ACTION_MOVE, 1f, 98f)
        assertTrue(factors.isEmpty())
        touch(MotionEvent.ACTION_MOVE, 5f, 85f)
        assertEquals(.8f, factors.single(), .001f)
    }

    @Test fun savedVideoTakesPriorityWithoutAWidgetCacheAndBadVideoFallsBack() {
        val folder = savedHero()
        val videos = File(folder, "videos").apply { mkdirs() }
        val original = File(folder, "base.png").readBytes()
        try {
            val idle = File(videos, "idle.mp4").apply { writeBytes(ByteArray(2048)) }
            File(videos, "greeting.mp4").writeBytes(ByteArray(2048))
            File(videos, "matte-color.txt").writeText("00ff00")
            assertEquals(idle, overlayVideoFile(HeroLocalStore(context), heroId))
            val source = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            Canvas(source).drawRect(50f, 30f, 150f, 280f, Paint().apply { color = Color.RED })
            ShadowMediaMetadataRetriever.addFrame(idle.path, 0, source)
            val quick = loadOverlayArtwork(context, heroId, fast = true)
            try {
                assertEquals(idle, quick.video?.file)
                assertTrue("Reactions are inspected after the first frame", quick.reactions.isEmpty())
                assertEquals(0, Color.alpha(quick.frames.first().getPixel(0, 0)))
            } finally { quick.recycle() }
            val art = loadOverlayArtwork(context, heroId)
            try {
                assertEquals(idle, art.video?.file)
                assertEquals(0, Color.alpha(art.frames.first().getPixel(0, 0)))
                assertEquals(Color.RED, art.frames.first().getPixel(100, 100))
                assertTrue(art.bounds.contains(50, 30))
                assertEquals(1, art.frames.size) // No per-frame PNG extraction/cache prerequisite.
                assertEquals(listOf("greeting.mp4"), art.reactions.map { it.file.name })
            } finally { art.recycle() }
            ShadowMediaMetadataRetriever.reset()
            val fallback = loadOverlayArtwork(context, heroId)
            try { assertNull(fallback.video); assertFalse(fallback.bounds.isEmpty) }
            finally { fallback.recycle() }
            assertArrayEquals(original, File(folder, "base.png").readBytes())
        } finally { folder.deleteRecursively() }
    }

    @Test fun reactionsDoNotRepeatOrQueueAndBadClipsAreSkipped() {
        val clips = listOf("joyful", "greeting", "dancing").map { OverlayVideo(File("$it.mp4"), Color.GREEN) }
        val reactions = OverlayReactions(clips, Random(42))
        val seen = mutableListOf<OverlayVideo>()
        repeat(12) {
            val clip = requireNotNull(reactions.take())
            assertNotEquals(seen.lastOrNull(), clip)
            seen += clip
            repeat(5) { assertNull(reactions.take()) }
            reactions.finish()
        }
        seen.chunked(3).forEach { assertEquals(clips.toSet(), it.toSet()) }
        val bad = reactions.take()
        reactions.discardActive()
        repeat(8) { assertNotEquals(bad, reactions.take()); reactions.finish() }
        val single = OverlayReactions(clips.take(1))
        assertNotNull(single.take()); single.discardActive(); assertNull(single.take())
        assertNull(OverlayReactions(emptyList()).take())
    }

    @Test fun tapPlaysOneSavedReactionThenReturnsToIdleAndLockCancelsIt() {
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val idle = OverlayVideo(File(context.filesDir, "idle.mp4"), Color.GREEN)
        val clips = listOf("joyful", "dancing").map { OverlayVideo(File(context.filesDir, "$it.mp4"), Color.GREEN) }
        val art = OverlayArtwork(listOf(bitmap), Rect(0, 0, 100, 100), 100, idle, clips)
        val view = OverlayHeroView(controller.get(), art)
        fun field(name: String) = view.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(view)
        fun player() = field("player") as ExoPlayer
        try {
            controller.get().setContentView(view)
            view.performClick()
            val reactionUri = player().currentMediaItem!!.localConfiguration!!.uri
            assertTrue(clips.any { android.net.Uri.fromFile(it.file) == reactionUri })
            assertEquals(Player.REPEAT_MODE_OFF, player().repeatMode)
            repeat(5) { view.performClick(); assertEquals(reactionUri, player().currentMediaItem!!.localConfiguration!!.uri) }
            (field("playbackListener") as Player.Listener).onPlaybackStateChanged(Player.STATE_ENDED)
            assertEquals(android.net.Uri.fromFile(idle.file), player().currentMediaItem!!.localConfiguration!!.uri)
            assertEquals(Player.REPEAT_MODE_ONE, player().repeatMode)
            view.performClick()
            assertNotEquals(reactionUri, player().currentMediaItem!!.localConfiguration!!.uri)
            view.animationEnabled = false
            assertNull(field("player"))
            view.animationEnabled = true
            assertEquals(android.net.Uri.fromFile(idle.file), player().currentMediaItem!!.localConfiguration!!.uri)
            assertEquals(0f, player().volume)
        } finally { controller.pause().stop().destroy(); art.recycle() }
    }

    @Test fun videoDecoderIsSilentLoopingAndReleasedWhenWindowDetaches() {
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val art = OverlayArtwork(listOf(bitmap), Rect(0, 0, 100, 100), 100,
            OverlayVideo(File(context.filesDir, "offline-idle.mp4"), Color.GREEN))
        val view = OverlayHeroView(controller.get(), art)
        fun player() = view.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(view) as? ExoPlayer
        try {
            controller.get().setContentView(view)
            assertNotNull(player())
            assertEquals(Player.REPEAT_MODE_ONE, player()!!.repeatMode)
            assertEquals(0f, player()!!.volume)
            view.animationEnabled = false
            assertNull(player())
            view.animationEnabled = true
            assertNotNull(player())
            (view.parent as ViewGroup).removeView(view)
            assertNull(player())
        } finally { controller.pause().stop().destroy(); art.recycle() }
    }

    @Test fun depthIdleGivesWayToReactionAndReturnsWithoutLosingPlayer() {
        val assets = File("src/main/assets").let { if (it.isDirectory) it else File("app/src/main/assets") }
        val root = File(assets, "bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/physics")
        val packDir = File(root, File(root, "active").readText().trim())
        val pack = Json { ignoreUnknownKeys = true }.decodeFromString<PhysicsPack>(File(packDir, "manifest.json").readText())
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val idle = OverlayVideo(File(context.filesDir, "idle.mp4"), Color.GREEN)
        val reaction = OverlayVideo(File(context.filesDir, "joyful.mp4"), Color.GREEN)
        val art = OverlayArtwork(listOf(bitmap), Rect(0, 0, 100, 100), 100, idle, listOf(reaction))
        val hero = OverlayHeroView(controller.get(), art, InstalledPhysics(pack, packDir), PhoneMotion(controller.get()))
        fun field(name: String) = hero.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(hero)
        try {
            controller.get().setContentView(hero)
            val physical = field("physicsView") as PhysicsView
            val player = field("player") as ExoPlayer
            val listener = field("playbackListener") as Player.Listener
            val video = field("videoView") as View
            physical.onReady()
            assertEquals(1f, physical.alpha)
            assertEquals(0f, video.alpha)
            assertTrue(hero.react())
            assertEquals(android.net.Uri.fromFile(reaction.file), player.currentMediaItem!!.localConfiguration!!.uri)
            listener.onRenderedFirstFrame()
            assertEquals(1f, video.alpha)
            listener.onPlaybackStateChanged(Player.STATE_ENDED)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(1f, video.alpha)
            assertEquals(android.net.Uri.fromFile(idle.file), player.currentMediaItem!!.localConfiguration!!.uri)
            assertSame(player, field("player"))
        } finally { controller.pause().stop().destroy(); art.recycle() }
    }

    @Test fun depthMeshIsAlignedToVideoSubject() {
        val assets = File("src/main/assets").let { if (it.isDirectory) it else File("app/src/main/assets") }
        val root = File(assets, "bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/physics")
        val packDir = File(root, File(root, "active").readText().trim())
        val pack = Json { ignoreUnknownKeys = true }.decodeFromString<PhysicsPack>(File(packDir, "manifest.json").readText())
        val bounds = com.generativemascot.app.physics.SubjectBounds(pack.width, pack.height,
            pack.width / 4, pack.height / 5, pack.width * 3 / 4, pack.height * 4 / 5)
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val art = OverlayArtwork(listOf(bitmap), Rect(110, 65, 210, 260), 100,
            OverlayVideo(File("idle.mp4"), Color.GREEN))
        try {
            val hero = OverlayHeroView(context, art, InstalledPhysics(pack, packDir, bounds = bounds), PhoneMotion(context))
            hero.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            hero.layout(0, 0, 400, 400)
            val view = hero.javaClass.getDeclaredField("physicsView").apply { isAccessible = true }.get(hero) as PhysicsView
            val fitted = minOf(view.width.toFloat() / pack.width, view.height.toFloat() / pack.height)
            val sourceCenterX = view.width / 2f + ((bounds.left + bounds.right) / 2f - pack.width / 2f) * fitted
            val sourceBottom = view.height / 2f + (bounds.bottom - pack.height / 2f) * fitted
            val displayedX = view.left + view.width / 2f + (sourceCenterX - view.width / 2f) * view.scaleX + view.translationX
            val displayedBottom = view.top + view.height / 2f + (sourceBottom - view.height / 2f) * view.scaleY + view.translationY
            val target = OverlayFraming.visibleBounds(art, 400, 400)
            assertEquals(target.centerX(), displayedX, 1f)
            assertEquals(target.bottom, displayedBottom, 1f)
        } finally { art.recycle() }
    }

    @Test fun depthPackCanArriveAfterOverlayWasOpened() {
        val assets = File("src/main/assets").let { if (it.isDirectory) it else File("app/src/main/assets") }
        val root = File(assets, "bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/physics")
        val packDir = File(root, File(root, "active").readText().trim())
        val pack = Json { ignoreUnknownKeys = true }.decodeFromString<PhysicsPack>(File(packDir, "manifest.json").readText())
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val art = OverlayArtwork(listOf(bitmap), Rect(0, 0, 100, 100), 100,
            OverlayVideo(File(context.filesDir, "idle.mp4"), Color.GREEN))
        val hero = OverlayHeroView(controller.get(), art)
        fun field(name: String) = hero.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(hero)
        try {
            controller.get().setContentView(hero)
            val player = field("player") as ExoPlayer
            assertNull(field("physicsView"))
            val installed = InstalledPhysics(pack, packDir)
            val sensors = PhoneMotion(controller.get())
            hero.installPhysics(installed, sensors)
            val physical = field("physicsView") as PhysicsView
            hero.installPhysics(installed, sensors)
            assertSame(physical, field("physicsView"))
            physical.onReady()
            assertEquals(1f, physical.alpha)
            physical.onFailure("temporary failure")
            assertTrue(hero.needsPhysicsRetry)
            hero.installPhysics(installed, sensors)
            val recovered = field("physicsView") as PhysicsView
            assertNotSame("A failed surface must be recreated", physical, recovered)
            physical.onReady() // A stale GL callback must not re-enable an old surface.
            assertFalse(hero.needsPhysicsRetry)
            recovered.onReady()
            assertEquals(1f, recovered.alpha)
            assertSame(player, field("player"))
        } finally { controller.pause().stop().destroy(); art.recycle() }
    }

    @Test fun overlayTouchRegionsExcludeTransparentPaddingAndKeepControls() {
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val art = OverlayArtwork(listOf(bitmap), Rect(120, 80, 200, 260), 100)
        try {
            val hero = OverlayHeroView(context, art)
            hero.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
            hero.layout(0, 0, 240, 320)
            assertFalse(hero.touchRegion().contains(0, 0))
            assertTrue(hero.touchRegion().contains(120, 160))
            listOf(.7f, 1f, 1.5f).forEach { scale ->
                val card = OverlayHintView(context, {}, {}).apply { compositionScale = scale }
                card.measure(View.MeasureSpec.makeMeasureSpec((248 * scale).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
                card.layout(0, 0, card.measuredWidth, card.measuredHeight)
                val region = card.touchRegion()
                assertFalse(region.contains(0, 0))
                assertFalse(region.contains(card.width/2, card.height-1))
                assertTrue(region.contains((100*scale).toInt(), (70*scale).toInt()))
                assertTrue(region.contains(card.width-(24*scale).toInt(), (24*scale).toInt()))
            }
        } finally { art.recycle() }
    }

    @Test fun overlayChatTargetsTheRightHeroAndWaitsForSafeNavigation() {
        val hero = com.generativemascot.app.data.MascotDto(id = heroId, name = "Герой", status = "READY", promptVersion = "test")
        val waiting = com.generativemascot.app.ui.AppUiState(mascot = hero)
        assertEquals(com.generativemascot.app.OverlayChatNavigation.WAIT, com.generativemascot.app.overlayChatNavigation(waiting, heroId))
        val ready = waiting.copy(ready = true)
        assertEquals(com.generativemascot.app.OverlayChatNavigation.OPEN, com.generativemascot.app.overlayChatNavigation(ready, heroId))
        assertEquals(com.generativemascot.app.OverlayChatNavigation.SELECT, com.generativemascot.app.overlayChatNavigation(ready, "other"))
        assertEquals(com.generativemascot.app.OverlayChatNavigation.WAIT,
            com.generativemascot.app.overlayChatNavigation(ready.copy(creating = true), "other"))
        assertEquals(com.generativemascot.app.OverlayChatNavigation.WAIT,
            com.generativemascot.app.overlayChatNavigation(ready.copy(backgroundCreation = true), "other"))
    }

    @Test fun overlayShowsRecentHeroRepliesFromActualConversations() {
        fun message(text: String, user: Boolean = false) = com.generativemascot.app.ui.ChatMessage(user, text)
        val intro = message("Привет! Рад познакомиться.")
        assertTrue(overlayChatReplies(listOf(intro)).isEmpty())
        val history = listOf(intro, message("Привет", user = true)) +
            (1..6).map { message("Ответ $it") }
        assertEquals((6 downTo 2).map { "Ответ $it" }, overlayChatReplies(history))
    }

    @Test fun onlyCollapseIsPresentAndTouchable() {
        val card = OverlayHintView(context, {}, {})
        card.measure(View.MeasureSpec.makeMeasureSpec(248, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        val close = card.findViewWithTag<View>("hide-overlay")
        val collapse = card.findViewWithTag<View>("collapse")
        assertNull(close)
        assertTrue(card.touchRegion().contains(collapse.left + collapse.width / 2, collapse.top + collapse.height / 2))
    }

    @Test fun cardActionsRemainIndependentAndLargeTextIsNotClipped() {
        var next = 0; var closed = 0; var collapsed = 0
        val enlarged = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration)
            .apply { fontScale = 1.5f })
        val card = OverlayHintView(enlarged, { next++ }, { closed++ }, { collapsed++ }, {
            enlarged.startActivity(com.generativemascot.app.MainActivity.overlayChatIntent(enlarged, heroId))
        })
        fun labels(view: View): List<TextView> = if (view is TextView) listOf(view)
            else if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()
        card.setMessage("Давай на минутку выдохнем", hasMore = true)
        labels(card).first { it.text == "Дальше" }.performClick()
        labels(card).first { it.text == "Скрыть" }.performClick()
        assertEquals(1, next); assertEquals(1, closed); assertEquals(0, collapsed)
        card.findViewWithTag<View>("collapse").performClick()
        assertEquals(1, collapsed)
        card.findViewWithTag<View>("hint-chat").performClick()
        val chatIntent = shadowOf(context).nextStartedActivity
        assertEquals(com.generativemascot.app.MainActivity::class.java.name, chatIntent.component?.className)
        assertEquals(heroId, chatIntent.getStringExtra(com.generativemascot.app.MainActivity.EXTRA_OVERLAY_CHAT_HERO))
        card.measure(View.MeasureSpec.makeMeasureSpec(248, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        labels(card).forEach { text ->
            assertTrue(text.bottom <= (text.parent as View).height)
            assertTrue(text.layout.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
        }
    }

    @Test fun wideVideoEdgesRemainVisibleOutsideTheNeutralSilhouetteAndAtMaximumTilt() {
        listOf(300 to 180, 180 to 300, 240 to 240).forEach { (w, h) ->
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            Canvas(bitmap).apply {
                val paint = Paint()
                listOf(Rect(0, 0, 24, 24), Rect(w - 24, 0, w, 24),
                    Rect(0, h - 24, 24, h), Rect(w - 24, h - 24, w, h)).forEachIndexed { i, r ->
                    paint.color = colors[i]; drawRect(r, paint)
                }
                paint.color = Color.WHITE
                drawRect(w * .4f, h * .25f, w * .6f, h * .75f, paint)
            }
            // Later poses can reach the source edges even when the idle body is narrow.
            val art = OverlayArtwork(listOf(bitmap), Rect(w / 3, h / 4, w * 2 / 3, h * 3 / 4),
                100, OverlayVideo(File(context.filesDir, "wide.mp4"), Color.MAGENTA))
            val hero = OverlayHeroView(context, art).apply { animationEnabled = false }
            val content = hero.javaClass.getDeclaredField("content").apply { isAccessible = true }.get(hero) as View
            try {
                listOf(94 to 147, 134 to 210, 201 to 315, 315 to 134).forEach { (width, height) ->
                    hero.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    hero.layout(0, 0, width, height)
                    listOf(-1f, 0f, 1f).forEach { x -> listOf(-1f, 0f, 1f).forEach { y ->
                        listOf(-9f, 0f, 9f).forEach { wobble ->
                            hero.applyPhonePose(PhonePose(x, y, wobble, 1f))
                            val projected = OverlayFraming.destination(art, width, height)
                            content.matrix.mapRect(projected)
                            assertTrue("Canvas clipped: $projected in $width × $height",
                                projected.left >= -.1f && projected.top >= -.1f &&
                                    projected.right <= width + .1f && projected.bottom <= height + .1f)
                        }
                    } }
                    hero.applyPhonePose(PhonePose())
                    val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    hero.draw(Canvas(rendered))
                    val pixels = IntArray(width * height)
                    rendered.getPixels(pixels, 0, width, 0, 0, width, height)
                    colors.forEach { color -> assertTrue("Missing source corner $color", pixels.any { it == color }) }
                    rendered.recycle()
                }
            } finally { art.recycle() }
        }
    }

    @Test fun sensorsDriveTheOverlayAndStopForCompactModeLockAndDestroy() {
        // Drive a bounded sequence of display frames below; never auto-drain a perpetual sensor loop.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        val folder = savedHero()
        val manager = shadowOf(context.getSystemService(SensorManager::class.java))
        val rotation = ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val acceleration = ShadowSensor.newInstance(Sensor.TYPE_LINEAR_ACCELERATION)
        manager.addSensor(rotation); manager.addSensor(acceleration)
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val controller = Robolectric.buildService(MascotOverlayService::class.java).create()
        val service = controller.get()
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
        try {
            service.onStartCommand(Intent().putExtra(MascotOverlayService.EXTRA_HERO, heroId), 0, 1)
            val deadline = System.nanoTime() + 10_000_000_000
            while (MascotOverlayService.status.value.loading && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(MascotOverlayService.status.value.running)
            val hero = field("hero") as OverlayHeroView
            val card = field("bubble") as View
            val window = hero.layoutParams as WindowManager.LayoutParams
            hero.measure(View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(window.height, View.MeasureSpec.EXACTLY))
            hero.layout(0, 0, window.width, window.height)
            val sensor = manager.listeners.single() as PhoneMotion
            assertTrue(manager.hasListener(sensor, rotation)); assertTrue(manager.hasListener(sensor, acceleration))
            PhoneMotion::class.java.getDeclaredField("sample").apply { isAccessible = true }
                .set(sensor, PhoneSample(tiltX = .3f, tiltY = -.2f))
            val callback = field("motionFrame") as android.view.Choreographer.FrameCallback
            val clock = android.view.Choreographer.getInstance()
            val cardBefore = (card.layoutParams as WindowManager.LayoutParams).let { it.x to it.y }
            val headBefore = hero.hintAnchorY(window.width, window.height)
            repeat(40) { i ->
                clock.removeFrameCallback(callback)
                callback.doFrame(1_000_000_000L + i * 16_666_667L)
            }
            val content = hero.javaClass.getDeclaredField("content").apply { isAccessible = true }.get(hero) as View
            assertTrue("Gyro rotation: ${content.rotationY}", content.rotationY > 5f)
            assertTrue("Gyro translation: ${content.translationX}", content.translationX > 0)
            val cardAfter = card.layoutParams as WindowManager.LayoutParams
            assertNotEquals("The hint follows the moving hero", cardBefore, cardAfter.x to cardAfter.y)
            assertEquals("The head and hint move together vertically",
                hero.hintAnchorY(window.width, window.height) - headBefore, cardAfter.y - cardBefore.second)
            val position = field("position") // Tilt changes artwork, never the saved window position.
            card.findViewWithTag<View>("collapse").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, manager.listeners.size); assertTrue(content.rotationY > 5f)
            (field("compact") as View).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, manager.listeners.size)
            assertEquals(position, field("position"))
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertTrue("Sensors after screen off: ${manager.listeners}", manager.listeners.isEmpty())
            assertFalse(field("motionRunning") as Boolean)
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
            context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertEquals(1, manager.listeners.size)
        } finally {
            controller.destroy()
            assertTrue(manager.listeners.isEmpty())
            org.robolectric.shadows.ShadowChoreographer.setPaused(false)
            folder.deleteRecursively()
        }
    }

    @Test fun renderActualAndroidOverlayForAllThreeHeroes() {
        val output = System.getenv("MASCOT3_OVERLAY_OUTPUT")?.let(::File) ?: return
        output.mkdirs()
        listOf("akim" to heroId, "mars" to "a83e9d30-1270-40e6-902c-b44774e84c17",
            "una" to "716cdb43-703b-4d7e-8a79-27f03d92b99c").forEach { (name, id) ->
            val folder = savedHero(id)
            val source = File(output, "$name-source.png")
            if (source.exists()) {
                val videos = File(folder, "videos").apply { mkdirs() }
                val video = File(videos, "idle.mp4")
                context.assets.open("bundled-heroes/$id/videos/idle.mp4").use { input ->
                    video.outputStream().use(input::copyTo)
                }
                val color = com.generativemascot.app.ui.detectBackgroundColor(source.toURI().toString())
                File(videos, "matte-color.txt").writeText("%06x".format(color and 0xffffff))
                ShadowMediaMetadataRetriever.addFrame(video.path, 0L, BitmapFactory.decodeFile(source.path))
            }
            val art = loadOverlayArtwork(context, id)
            try {
                listOf(70, 100, 120).forEach { percent ->
                    render(output, "$name-$percent", art, 390, 844, false, percent / 100f)
                    if (name == "una") render(output, "una-right-$percent", art, 390, 844, true, percent / 100f)
                    renderSettingsPreview(output, "settings-$name-$percent", art, percent / 100f)
                    if (name == "akim") {
                        render(output, "right-$percent", art, 390, 844, true, percent / 100f)
                        render(output, "landscape-$percent", art, 844, 390, true, percent / 100f)
                    }
                }
            } finally { art.recycle(); folder.deleteRecursively() }
        }
    }

    @Test fun clampedSettingsCardAimsAtOffsetHeroInsteadOfAnEmptyCorner() {
        // The video's visible body is off-centre, just like generated clips with
        // a lot of transparent padding. The narrow settings block clamps the card.
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val body = Rect(164, 105, 292, 292)
        Canvas(bitmap).drawRoundRect(RectF(body), 36f, 36f, Paint().apply { color = Color.RED })
        val art = OverlayArtwork(listOf(bitmap), body, 100,
            OverlayVideo(File(context.filesDir, "off-centre.mp4"), Color.GREEN))
        try {
            listOf(280, 338, 390).forEach { width ->
                listOf(.7f, 1f, 1.2f).forEach { scale ->
                    val preview = OverlayPreviewView(context, art).apply {
                        animationEnabled = false; heroScale = scale
                    }
                    preview.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
                    preview.layout(0, 0, width, 360)
                    val hero = preview.getChildAt(0) as OverlayHeroView
                    val card = preview.getChildAt(1) as OverlayHintView
                    val bodyOnScreen = hero.hintBounds(hero.width, hero.height).apply {
                        offset(hero.left.toFloat(), hero.top.toFloat())
                    }
                    val tipX = card.left + card.tailCenters().last().x
                    assertTrue("Tail $tipX misses the hero $bodyOnScreen at $width / $scale",
                        tipX in bodyOnScreen.left..bodyOnScreen.right)
                    assertTrue(card.left >= 0 && card.right <= width)
                    assertTrue("The card must not cover the head",
                        card.top + card.tailHeight >= bodyOnScreen.top + bodyOnScreen.height() * .55f)
                    assertTrue(bodyOnScreen.left >= 0 && bodyOnScreen.right <= width)
                    assertTrue("The padded source stays visible", hero.bottom <= 360)
                }
            }
        } finally { art.recycle() }
    }

    private fun renderSettingsPreview(output: File, name: String, art: OverlayArtwork, scale: Float) {
        val preview = OverlayPreviewView(context, art).apply {
            animationEnabled = false; heroScale = scale
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(255, 218, 175), Color.rgb(224, 205, 255), Color.rgb(171, 218, 217)))
        }
        preview.measure(View.MeasureSpec.makeMeasureSpec(338, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        preview.layout(0, 0, 338, preview.measuredHeight)
        val bitmap = Bitmap.createBitmap(338, preview.measuredHeight, Bitmap.Config.ARGB_8888)
        preview.draw(Canvas(bitmap))
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun render(output: File, name: String, art: OverlayArtwork, width: Int, height: Int, right: Boolean, scale: Float) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.rgb(245, 164, 191), Color.rgb(255, 213, 133), Color.rgb(184, 171, 250),
                    Color.rgb(126, 203, 207)), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null; paint.color = Color.rgb(40, 32, 45); paint.textSize = 14f
        canvas.drawText("12:13", 24f, 28f, paint)
        canvas.drawCircle(width / 2f, 20f, 5f, paint)
        paint.textSize = 12f
        canvas.drawText("●  ▰", width - 55f, 28f, paint)
        paint.color = Color.rgb(80, 34, 43)
        canvas.drawRoundRect(28f, 65f, 181f, 218f, 65f, 65f, paint)
        paint.color = Color.rgb(255, 159, 167); paint.textSize = 58f; paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("21°", 60f, 153f, paint)
        paint.color = Color.rgb(255, 207, 0); canvas.drawCircle(58f, 181f, 25f, paint)
        paint.typeface = Typeface.DEFAULT; paint.color = Color.WHITE
        val names = listOf("☎", "•••", "△", "◎", "M")
        names.forEachIndexed { i, label ->
            val x = 44f + i * (width - 88f) / 4
            canvas.drawCircle(x, height - 117f, 22f, paint)
            paint.color = Color.rgb(76, 110, 217); paint.textSize = 20f
            canvas.drawText(label, x - 10, height - 110f, paint); paint.color = Color.WHITE
        }
        paint.color = Color.rgb(47, 37, 42)
        canvas.drawRoundRect(24f, height - 75f, width - 24f, height - 28f, 24f, 24f, paint)
        paint.color = Color.rgb(182, 164, 169); paint.textSize = 18f
        canvas.drawText("G", 42f, height - 45f, paint)
        paint.textSize = 13f; canvas.drawText("Поиск", 76f, height - 45f, paint)
        val area = OverlayArea(8, 36, width - 8, height - 32)
        val size = OverlaySize.dimensions(scale, 1f, area, art)
        val placement = OverlayPlacement.hero(area, size, art, 16)
        val heroPos = placement.restore(if (right) 1f else 0f, if (height > 500) .60f else .8f)
        val body = OverlayFraming.visibleBounds(art, size.width, size.height)
        assertTrue(heroPos.x + body.left >= area.left && heroPos.x + body.right <= area.right)
        assertEquals(heroPos, placement.restore(placement.fractions(heroPos).first, placement.fractions(heroPos).second))
        val hero = OverlayHeroView(context, art).apply { animationEnabled = false }
        hero.measure(View.MeasureSpec.makeMeasureSpec(size.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size.height, View.MeasureSpec.EXACTLY))
        hero.layout(0, 0, size.width, size.height)
        val backdrop = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        File(output, "$name-empty.png").outputStream().use { backdrop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val compactBackdrop = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(compactBackdrop).apply { translate(heroPos.x.toFloat(), heroPos.y.toFloat()); hero.draw(this) }
        canvas.save(); canvas.translate(heroPos.x.toFloat(), heroPos.y.toFloat()); hero.draw(canvas); canvas.restore()
        val card = OverlayHintView(context, {}, {})
        val visible = hero.hintBounds(size.width, size.height)
        val anchor = OverlayPoint(heroPos.x + visible.left.roundToInt(), heroPos.y)
        val visibleWidth = visible.width().roundToInt().coerceAtLeast(1)
        card.pointTowardHero(anchor.x + visibleWidth / 2 < area.left + area.width / 2)
        card.compositionScale = scale
        card.measure(View.MeasureSpec.makeMeasureSpec(minOf((248 * scale).roundToInt(), area.width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(area.height, View.MeasureSpec.AT_MOST))
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        val cardPos = OverlayLayout.bubble(area, anchor, visibleWidth, card.width, card.height, 2,
            anchorY = hero.hintAnchorY(size.width, size.height), tailInset = card.tailInset, tailHeight = card.tailHeight)
        val cardOnRight = anchor.x + visibleWidth / 2 < area.left + area.width / 2
        card.aimAtHero(heroPos.x + hero.hintTargetX(size.width, size.height, cardOnRight) - cardPos.x)
        canvas.save(); canvas.translate(cardPos.x.toFloat(), cardPos.y.toFloat()); card.draw(canvas); canvas.restore()
        val foreground = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(foreground).apply { save(); translate(cardPos.x.toFloat(), cardPos.y.toFloat()); card.draw(this); restore() }
        File(output, "$name-card.png").outputStream().use { foreground.compress(Bitmap.CompressFormat.PNG, 100, it) }
        foreground.recycle()
        val backgroundCanvas = Canvas(backdrop)
        backgroundCanvas.save(); backgroundCanvas.translate(cardPos.x.toFloat(), cardPos.y.toFloat())
        card.draw(backgroundCanvas); backgroundCanvas.restore()
        File(output, "$name-background.png").outputStream().use { backdrop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val compact = OverlayCompactView(context) {}
        compact.measure(View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
        compact.layout(0, 0, 48, 48)
        val compactPoint = OverlayLayout.clamp(area, 48, 48, heroPos.x + visible.right.roundToInt() - 40, heroPos.y + visible.bottom.roundToInt() - 40)
        Canvas(compactBackdrop).apply {
            translate(compactPoint.x.toFloat(), compactPoint.y.toFloat()); compact.draw(this)
        }
        val bellLayer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bellLayer).apply { translate(compactPoint.x.toFloat(), compactPoint.y.toFloat()); compact.draw(this) }
        File(output, "$name-bell.png").outputStream().use { bellLayer.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bellLayer.recycle()
        File(output, "$name-collapsed.png").outputStream().use { compactBackdrop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compactBackdrop.recycle()
        val collapse = card.findViewWithTag<View>("collapse")
        var buttonX = collapse.left; var buttonY = collapse.top; var ancestor = collapse.parent as View
        while (ancestor !== card) { buttonX += ancestor.left; buttonY += ancestor.top; ancestor = ancestor.parent as View }
        val frame = OverlayFraming.destination(art, size.width, size.height)
        File(output, "$name-layout.json").writeText("""{"x":${heroPos.x},"y":${heroPos.y},"width":${size.width},"height":${size.height},"frame":{"x":${heroPos.x+frame.left},"y":${heroPos.y+frame.top},"width":${frame.width()},"height":${frame.height()}},"collapse":{"x":${cardPos.x + buttonX},"y":${cardPos.y + buttonY},"width":44,"height":44},"compact":{"x":${compactPoint.x},"y":${compactPoint.y},"width":48,"height":48}}""")
        backdrop.recycle()
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
