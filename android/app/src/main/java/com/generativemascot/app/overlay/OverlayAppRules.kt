package com.generativemascot.app.overlay

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock

internal enum class OverlayAppMode(val title: String) {
    EVERYWHERE("Везде"),
    HIDE_SELECTED("Скрывать в выбранных"),
    ONLY_SELECTED("Только в выбранных"),
}

internal data class OverlayAppRules(
    val mode: OverlayAppMode = OverlayAppMode.EVERYWHERE,
    val packages: Set<String> = emptySet(),
) {
    val needsTracking get() = mode != OverlayAppMode.EVERYWHERE && packages.isNotEmpty()

    fun showsOver(packageName: String?): Boolean = when {
        mode == OverlayAppMode.EVERYWHERE -> true
        packages.isEmpty() -> mode == OverlayAppMode.HIDE_SELECTED
        packageName == null -> false // Do not expose the overlay until the active app is known.
        mode == OverlayAppMode.HIDE_SELECTED -> packageName !in packages
        else -> packageName in packages
    }

    fun save(context: Context) {
        context.getSharedPreferences(OverlaySize.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(MODE_KEY, mode.name).putStringSet(APPS_KEY, packages.toSet()).apply()
    }

    companion object {
        const val MODE_KEY = "app_visibility_mode"
        const val APPS_KEY = "app_visibility_packages"
        fun read(context: Context): OverlayAppRules {
            val prefs = context.getSharedPreferences(OverlaySize.PREFERENCES, Context.MODE_PRIVATE)
            val mode = OverlayAppMode.entries.firstOrNull { it.name == prefs.getString(MODE_KEY, null) }
                ?: OverlayAppMode.EVERYWHERE
            return OverlayAppRules(mode, prefs.getStringSet(APPS_KEY, emptySet())!!.toSet())
        }

        fun hasUsageAccess(context: Context): Boolean = runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29)
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            else @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}

/** Reduce lifecycle events, not lastTimeUsed statistics (which can point at a closed app). */
internal class OverlayForegroundTracker {
    var packageName: String? = null
        private set
    private var activity: String? = null
    private var lastTime = Long.MIN_VALUE
    private val atLastTime = mutableSetOf<Triple<Int, String?, String?>>()

    fun accept(type: Int, pkg: String?, cls: String?, time: Long) {
        if (type != UsageEvents.Event.ACTIVITY_RESUMED && type != UsageEvents.Event.ACTIVITY_PAUSED &&
            type != UsageEvents.Event.ACTIVITY_STOPPED && type != UsageEvents.Event.SCREEN_NON_INTERACTIVE &&
            type != UsageEvents.Event.KEYGUARD_SHOWN && type != UsageEvents.Event.DEVICE_SHUTDOWN) return
        if (time < lastTime) return
        if (time > lastTime) { lastTime = time; atLastTime.clear() }
        if (!atLastTime.add(Triple(type, pkg, cls))) return
        when (type) {
            // Values 1 and 2 also cover MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND on API 26–28.
            UsageEvents.Event.ACTIVITY_RESUMED -> { packageName = pkg?.takeIf { it.isNotBlank() }; activity = cls }
            UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                if (pkg == packageName && (cls == activity || cls == null)) { packageName = null; activity = null }
            UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN,
            UsageEvents.Event.DEVICE_SHUTDOWN -> { packageName = null; activity = null }
        }
    }
}

/** Called on IO only while a filtered overlay is enabled and the screen is unlocked. */
internal class OverlayForegroundApps(private val context: Context) {
    private var tracker = OverlayForegroundTracker()
    private var cursor = 0L

    fun currentPackage(now: Long = System.currentTimeMillis()): String? {
        if (!OverlayAppRules.hasUsageAccess(context)) { reset(); return null }
        if (now < cursor) reset() // Wall-clock changes must not keep an old foreground app.
        val start = if (cursor == 0L) now - minOf(SystemClock.elapsedRealtime(), 86_400_000L)
            else (cursor - 5_000).coerceAtLeast(0)
        return try {
            val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(start, now)
                ?: run { reset(); return null }
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                tracker.accept(event.eventType, event.packageName, event.className, event.timeStamp)
            }
            cursor = now
            tracker.packageName
        } catch (_: RuntimeException) { reset(); null }
    }

    private fun reset() { cursor = 0; tracker = OverlayForegroundTracker() }
}

internal data class OverlayAppChoice(val packageName: String, val label: String)

internal fun overlayAppChoices(context: Context): List<OverlayAppChoice> {
    val manager = context.packageManager
    @Suppress("DEPRECATION")
    val launchers = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    @Suppress("DEPRECATION")
    val homes = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
    val homePackages = homes.map { it.activityInfo.packageName }.toSet()
    return (launchers + homes).distinctBy { it.activityInfo.packageName }.map {
        val pkg = it.activityInfo.packageName
        val name = it.loadLabel(manager).toString()
        OverlayAppChoice(pkg, if (pkg in homePackages) "Главный экран · $name" else name)
    }.sortedBy { it.label.lowercase() }
}
