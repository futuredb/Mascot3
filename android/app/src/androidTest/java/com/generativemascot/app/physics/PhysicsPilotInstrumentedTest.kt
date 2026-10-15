package com.generativemascot.app.physics

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.data.BundledHeroImporter
import com.generativemascot.app.ui.PhysicsMascot
import com.generativemascot.app.ui.PuppetInteraction
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot

/** Real approved pilot assets, local playback only; never starts provider jobs. */
class PhysicsPilotInstrumentedTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val device: RuleChain = RuleChain.outerRule(PhysicsDeviceScreenRule()).around(compose)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val hero get() = "bundled-heroes/${PhysicsStore.PILOT_ID}"

    @Test fun approvedPilotRemainsStableAcrossFrameRatesAndStrongInputs() {
        BundledHeroImporter({ context.assets.list(it).orEmpty() },{ context.assets.open(it) },File(context.filesDir,"hero")).importAll()
        val store=PhysicsStore(context)
        val installed=runBlocking { requireNotNull(store.load(PhysicsStore.PILOT_ID)) }
        assertEquals(store.revision(PhysicsStore.PILOT_ID),PhysicsStore.hash(File(installed.directory,"manifest.json").readBytes()))
        val pack=installed.pack
        pack.validate()
        fun run(fps:Int): List<Float> {
            val solver=PhysicsSolver(pack)
            val trace=listOf(PhoneSample(),PhoneSample(.209f,-.209f,0f,0f,.7f,-.6f),PhoneSample(-.209f,.209f,12f,-12f,-1f,1f),PhoneSample(.209f,.209f,-12f,12f),PhoneSample())
            trace.forEach { r -> repeat(fps*2) {
                solver.advance(1.0/fps,r.tiltX,r.tiltY,r.accelerationX,r.accelerationY,r.gravityX,r.gravityY)
                solver.states.forEach { s ->
                    s.x.indices.forEach { i ->
                        assertTrue(s.x[i].isFinite() && s.y[i].isFinite())
                        assertTrue(hypot(s.x[i]-s.layer.vertices[i][0],s.y[i]-s.layer.vertices[i][1])<=s.layer.maxDisplacement+.00001f)
                    }
                    for(t in s.layer.triangles.indices step 3) {
                        val a=s.layer.triangles[t];val b=s.layer.triangles[t+1];val c=s.layer.triangles[t+2]
                        assertTrue("Triangle folded in ${s.layer.id}",(s.x[b]-s.x[a])*(s.y[c]-s.y[a])-(s.y[b]-s.y[a])*(s.x[c]-s.x[a])>0f)
                    }
                }
            } }
            solver.neutral=true;repeat(fps*3) { solver.advance(1.0/fps,.2f,.2f,12f,12f) }
            assertTrue(solver.settled)
            return listOf(solver.angleX,solver.angleY)+solver.states.flatMap { it.x.toList()+it.y.toList() }
        }
        val reference=run(120)
        for(fps in listOf(30,60)) reference.zip(run(fps)).forEach { (a,b)->assertTrue(abs(a-b)<1e-6f) }
    }

    @Test fun greetingDanceSleepPauseAndRecreatedSurfaceReturnToPhysics() {
        BundledHeroImporter({ context.assets.list(it).orEmpty() },{ context.assets.open(it) },File(context.filesDir,"hero")).importAll()
        val local=File(context.filesDir,"hero/${PhysicsStore.PILOT_ID}/videos")
        val videos=local.listFiles().orEmpty().filter { it.extension=="mp4" }.associate { it.nameWithoutExtension to it.toURI().toString() }
        val action=mutableStateOf("idle");val surface=mutableIntStateOf(0)
        val visible=CopyOnWriteArrayList<String>();val completed=CopyOnWriteArrayList<String>()
        val output=File(context.getExternalFilesDir(null),"physics-acceptance").apply { mkdirs() }
        val events=File(output,"transition-events.jsonl").apply { writeText("") }
        fun event(kind:String,name:String) { events.appendText("{\"time_ms\":${System.currentTimeMillis()},\"kind\":\"$kind\",\"action\":\"$name\"}\n") }
        compose.setContent {
            key(surface.intValue) {
                PhysicsMascot(PhysicsStore.PILOT_ID,videos,File(local,"neutral.png").toURI().toString(),action.value,PuppetInteraction(),Modifier.fillMaxSize(),
                    onAnimationVisible={visible+=it;event("visible",it)},onOneShotFinished={completed+=it;event("completed",it)},fallback={})
            }
        }
        fun awaitVisible(name:String,timeout:Long=30000) { compose.waitUntil(timeout) { name in visible } }
        fun request(name:String) { compose.runOnIdle { visible.clear();event("request",name);action.value=name } }
        awaitVisible("idle")
        request("greeting");awaitVisible("greeting");awaitVisible("idle");assertTrue("greeting" in completed)
        request("dancing");awaitVisible("dancing");awaitVisible("idle")
        request("sleeping");awaitVisible("sleeping")
        request("idle");awaitVisible("idle",45000)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        android.os.SystemClock.sleep(300)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        // New composition destroys the old TextureView/EGL surface and rebuilds
        // GPU resources from the same installed pack, like a hero/surface switch.
        compose.runOnIdle { visible.clear();surface.intValue++ }
        awaitVisible("idle")
        var renderers=0
        compose.runOnIdle {
            fun visit(view:View) {
                if(view is PhysicsView && view.isAvailable && view.active) renderers++
                if(view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            visit(compose.activity.findViewById(android.R.id.content))
        }
        assertEquals(1,renderers)
        File(output,"transitions-result.json").writeText("""{"greeting_return":true,"dance_return":true,"sleep_wake_return":true,"pause_resume":true,"recreated_surface":true,"active_renderers":$renderers}""")
    }
}
