package com.generativemascot.app.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.generativemascot.app.MascotApp
import com.generativemascot.app.ui.MascotTheme
import kotlinx.coroutines.launch

/** A direct widget PendingIntent target. The main app screen is never opened. */
class OverlayLaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val explicit = intent.getStringExtra(MascotOverlayService.EXTRA_HERO)
            val app = application as? MascotApp
            val id = explicit ?: app?.session?.acceptedMascotId() ?: OverlaySession.hero(this@OverlayLaunchActivity)
            if (!OverlaySession.validHero(id)) {
                Toast.makeText(this@OverlayLaunchActivity, "Сначала выберите героя в приложении.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            if (OverlaySession.hero(this@OverlayLaunchActivity) == null) {
                val prepared = try { com.generativemascot.app.physics.PhysicsStore(this@OverlayLaunchActivity).load(requireNotNull(id)) != null }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (prepared && runCatching { com.generativemascot.app.widget.WidgetDepthService.reactFromWidget(this@OverlayLaunchActivity) }.isSuccess) {
                    finish()
                    return@launch
                }
            }
            fun showHero() {
                runCatching { MascotOverlayService.showFromWidget(this@OverlayLaunchActivity, requireNotNull(id)) }
                    .onFailure { Toast.makeText(this@OverlayLaunchActivity, "Не удалось показать героя. Попробуйте ещё раз.", Toast.LENGTH_LONG).show() }
                finish()
            }
            val permissions = OverlayPermissionState.read(this@OverlayLaunchActivity)
            if (permissions.canShow(this@OverlayLaunchActivity) &&
                (!OverlayPermissionIntro.needed(this@OverlayLaunchActivity) || permissions.complete)) {
                showHero()
            } else setContent {
                MascotTheme {
                    OverlayPermissionsDialog(this@OverlayLaunchActivity,
                        onDismiss = { OverlayPermissionIntro.seen(this@OverlayLaunchActivity); finish() },
                        onContinue = { OverlayPermissionIntro.seen(this@OverlayLaunchActivity); showHero() })
                }
            }
        }
    }

    companion object {
        internal fun intent(context: Context, heroId: String?) = Intent(context, OverlayLaunchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MascotOverlayService.EXTRA_HERO, heroId)
    }
}
