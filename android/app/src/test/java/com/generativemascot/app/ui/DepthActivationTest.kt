package com.generativemascot.app.ui

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.generativemascot.app.physics.PhysicsStore
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DepthActivationTest {
    @Test fun savedBundledMapIsActivatedOnFirstHomeCompositionWithoutVideo() {
        val context = RuntimeEnvironment.getApplication()
        val id = "9f226b89-df35-4d3a-9b36-96c28ea1bb62"
        val folder = File(context.filesDir, "hero/$id").apply { mkdirs() }
        fun copyAsset(source: String, target: File) {
            val children = context.assets.list(source).orEmpty()
            if (children.isEmpty()) {
                target.parentFile?.mkdirs()
                context.assets.open(source).use { input -> target.outputStream().use(input::copyTo) }
            } else children.filterNot { it == "active" }.forEach { copyAsset("$source/$it", File(target, it)) }
        }
        copyAsset("bundled-heroes/$id/base.png", File(folder, "base.png"))
        copyAsset("bundled-heroes/$id/physics", File(folder, "physics"))
        assertNull(PhysicsStore(context).revision(id))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                PhysicsMascot(id, emptyMap(), null, "idle", PuppetInteraction(),
                    onAnimationVisible = null, onOneShotFinished = null, fallback = {})
            }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (PhysicsStore(context).revision(id) == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)); Thread.sleep(20)
            }
            assertNotNull("An existing bundled depth pack must be activated even without video", PhysicsStore(context).revision(id))
        } finally { activity.pause().stop().destroy(); folder.deleteRecursively() }
    }
}
