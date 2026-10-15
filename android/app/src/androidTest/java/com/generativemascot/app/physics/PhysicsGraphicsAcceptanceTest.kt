package com.generativemascot.app.physics

import android.graphics.Bitmap
import android.os.SystemClock
import com.generativemascot.app.MainActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.data.BundledHeroImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot

/** Real GLES frames and real approved assets; inputs and context loss are controlled. */
class PhysicsGraphicsAcceptanceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val device: RuleChain = RuleChain.outerRule(PhysicsDeviceScreenRule()).around(compose)
    private fun content(block: @Composable () -> Unit) { compose.runOnUiThread { compose.activity.setContent(content=block) } }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val output get() = File(context.getExternalFilesDir(null), "physics-acceptance").apply { mkdirs() }

    private fun installed(): InstalledPhysics = runBlocking {
        BundledHeroImporter({ context.assets.list(it).orEmpty() }, { context.assets.open(it) }, File(context.filesDir, "hero")).importAll()
        requireNotNull(PhysicsStore(context).load(PhysicsStore.PILOT_ID))
    }

    private class Control : PhysicsRenderObserver {
        @Volatile var input = PhoneSample()
        val invalidate = AtomicBoolean()
        val contexts = AtomicInteger()
        val frames = AtomicInteger()
        @Volatile var lean = 0f
        @Volatile var excursion = 0f
        @Volatile var currentExcursion = 0f
        @Volatile var pinnedError = 0f
        @Volatile var rigidError = 0f
        @Volatile var folded = false
        val trace = ConcurrentLinkedQueue<String>()
        override fun sample() = input
        override fun discardContext() = invalidate.getAndSet(false)
        override fun contextCreated() { contexts.incrementAndGet() }
        override fun frame(solver: PhysicsSolver) {
            frames.incrementAndGet(); lean = solver.leanAngle
            var excursionNow = 0f
            val byId = solver.states.associateBy { it.layer.id }
            solver.states.forEach { state ->
                val layer = state.layer
                state.x.indices.forEach { i ->
                    val dx = state.x[i] - layer.vertices[i][0]
                    val dy = state.y[i] - layer.vertices[i][1]
                    if (layer.material != "rigid") { excursionNow = maxOf(excursionNow, hypot(dx, dy) * solver.pack.width); excursion = maxOf(excursion, excursionNow) }
                    else rigidError = maxOf(rigidError, abs(dx) + abs(dy))
                }
                layer.pins.forEach { (child, parent) ->
                    val p = byId.getValue(layer.parentId!!)
                    pinnedError = maxOf(pinnedError,
                        abs(state.x[child] - layer.vertices[child][0] - p.x[parent] + p.layer.vertices[parent][0]),
                        abs(state.y[child] - layer.vertices[child][1] - p.y[parent] + p.layer.vertices[parent][1]))
                }
                for (t in layer.triangles.indices step 3) {
                    val a = layer.triangles[t]; val b = layer.triangles[t + 1]; val c = layer.triangles[t + 2]
                    if ((state.x[b] - state.x[a]) * (state.y[c] - state.y[a]) - (state.y[b] - state.y[a]) * (state.x[c] - state.x[a]) <= 0f) folded = true
                }
            }
            currentExcursion = excursionNow
            if (trace.size < 2000) trace += "${SystemClock.elapsedRealtimeNanos()},${solver.angleX},${solver.angleY},${solver.bodyX},${solver.bodyY},$excursionNow,${input.accelerationX},${input.accelerationY}"
        }
    }

    private fun capture(view: PhysicsView, name: String): IntArray {
        lateinit var pixels: IntArray
        compose.runOnIdle {
            val bitmap = requireNotNull(view.bitmap)
            pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        assertTrue("Empty GPU frame: $name", pixels.count { (it ushr 24) > 128 } > 10000)
        assertEquals("Green matte spill in $name",0,pixels.count {
            val r=(it ushr 16) and 255;val g=(it ushr 8) and 255;val b=it and 255
            (it ushr 24)>32 && g>70 && g>r+35 && g>b+25
        })
        return pixels
    }

    @Test fun discardedContextRebuildsTexturesAndRepeatedFailureFallsBack() {
        val pack = installed(); val control = Control(); val failures = CopyOnWriteArrayList<String>()
        lateinit var view: PhysicsView
        content {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                PhysicsView(ctx, pack, control).also { view = it; it.neutral = true; it.onFailure = { failures += it }; it.resume() }
            }, onRelease = { it.pause() })
        }
        compose.waitUntil(15000) { control.frames.get() >= 5 }
        val before = capture(view, "context-before")
        repeat(2) { attempt ->
            val previous = control.frames.get()
            control.invalidate.set(true)
            compose.waitUntil(10000) { control.contexts.get() == attempt + 2 && control.frames.get() > previous + 2 }
            val after = capture(view, "context-recovered-${attempt + 1}")
            assertArrayEquals("Neutral GPU image changed after recreation", before, after)
            assertTrue(failures.isEmpty())
        }
        control.invalidate.set(true)
        compose.waitUntil(10000) { failures.isNotEmpty() }
        assertEquals(3, control.contexts.get())
        assertEquals(1, failures.size)
        File(output, "context-result.json").writeText("""{"contexts_created":3,"successful_recoveries":2,"neutral_pixels_identical":true,"third_loss_fallback":true,"mode":"controlled real-context disposal; same recovery path as EGL_CONTEXT_LOST"}""")
        compose.runOnIdle { view.pause() }
    }

    @Test fun recordedSmallTiltAndImpulseKeepRigidBodyAndAttachmentsIntact() {
        val pack = installed(); val control = Control(); val failures = CopyOnWriteArrayList<String>()
        lateinit var view: PhysicsView
        content {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                PhysicsView(ctx, pack, control).also { view = it; it.onFailure = { failures += it }; it.resume() }
            }, onRelease = { it.pause() })
        }
        compose.waitUntil(15000) { control.frames.get() >= 10 }
        capture(view, "motion-neutral")
        val roll = kotlin.math.sin(Math.toRadians(5.0)).toFloat()
        for (i in 1..60) { control.input = PhoneSample(gravityX = roll * i / 60); SystemClock.sleep(16) }
        SystemClock.sleep(500)
        capture(view, "motion-roll-positive-5deg")
        assertTrue(control.lean > Math.toRadians(5.0))
        for (i in 1..120) { control.input = PhoneSample(gravityX = roll * (1f - i / 60f)); SystemClock.sleep(16) }
        SystemClock.sleep(500)
        capture(view, "motion-roll-negative-5deg")
        assertTrue(control.lean < -Math.toRadians(5.0))
        control.input = PhoneSample(tiltY = .09f)
        SystemClock.sleep(700); capture(view, "motion-pitch")
        control.input = PhoneSample(accelerationX = 9f, accelerationY = -7f)
        SystemClock.sleep(150); capture(view, "motion-impulse")
        val impulseExcursion = control.currentExcursion
        control.input = PhoneSample()
        SystemClock.sleep(1800); capture(view, "motion-after-impulse")
        val decayedExcursion = control.currentExcursion
        assertTrue("Impulse does not decay", decayedExcursion < impulseExcursion * .5f + .25f)
        compose.runOnIdle { view.neutral = true }
        SystemClock.sleep(2200); capture(view, "motion-settled")
        assertTrue(failures.isEmpty())
        assertTrue("Soft parts did not move", control.excursion >= 2f)
        assertTrue("Attachment detached", control.pinnedError < 1e-6f)
        assertEquals(0f, control.rigidError, 1e-6f)
        assertFalse("Triangle folded", control.folded)
        assertTrue("Soft parts did not return to neutral", control.currentExcursion < .75f)
        File(output, "motion-result.json").writeText("""{"input":"recorded +/-5 degree roll, pitch and impulse","max_soft_excursion_px":${control.excursion},"max_attachment_error":${control.pinnedError},"max_rigid_deformation":${control.rigidError},"folded_triangles":${control.folded},"impulse_excursion_px":$impulseExcursion,"excursion_after_1_8s_px":$decayedExcursion,"final_excursion_px":${control.currentExcursion},"frames":${control.frames.get()}}""")
        compose.runOnIdle { view.pause() }
        File(output,"motion-trace.csv").writeText("time_ns,angle_x,angle_y,body_x,body_y,secondary_excursion_px,acceleration_x,acceleration_y\n"+control.trace.joinToString("\n"))
    }
}
