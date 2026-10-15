package com.generativemascot.app.physics

import android.os.Build
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.rules.ExternalResource

/** Only the temporary test activity may display above keyguard; it does not unlock it. */
class PhysicsDeviceScreenRule : ExternalResource() {
    private val windows=java.util.WeakHashMap<android.app.Activity,Boolean>()
    private val callback = ActivityLifecycleCallback { activity, stage ->
        if (stage == Stage.CREATED) {
            windows[activity]=(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 27) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }
    override fun before() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        }
    }
    override fun after() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
            windows.forEach { (activity,keptOn) ->
                if(!activity.isDestroyed) {
                    if(!keptOn)activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if(Build.VERSION.SDK_INT>=27){activity.setShowWhenLocked(false);activity.setTurnScreenOn(false)}
                }
            }
            windows.clear()
        }
    }
}
