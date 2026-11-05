package com.generativemascot.app.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.generativemascot.app.MascotApp
import com.generativemascot.app.widget.updateMascotWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restore only a previously enabled companion, after system-authorized restart events. */
class OverlayRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        com.generativemascot.app.widget.WidgetDepthService.startIfPresent(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && context.applicationContext is MascotApp) {
            // Replace old launcher PendingIntents too; no new video preparation or generation.
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try { runCatching { updateMascotWidgets(context, prepareAnimation = false, force = true) } }
                finally { pending.finish() }
            }
        }
        if (OverlaySession.hero(context) == null) return
        if (!Settings.canDrawOverlays(context)) {
            OverlaySession.disable(context)
            return
        }
        // Android may still reject a start under manufacturer battery restrictions.
        // Do not schedule alarms or repeated launches; the user can resume from the app.
        runCatching {
            ContextCompat.startForegroundService(context,
                Intent(context, MascotOverlayService::class.java).setAction(MascotOverlayService.ACTION_RESTORE))
        }
    }
}
