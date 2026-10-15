package com.generativemascot.app.physics

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.MainActivity
import com.generativemascot.app.MascotApp
import com.generativemascot.app.state.PetState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** No Compose test clock or activity-idle barrier: this measures the real app. */
class PhysicsFullAppSoakTest {
    @get:Rule val screen=PhysicsDeviceScreenRule()
    @Test fun tenMinutesOfRealIdleKeepTheRendererAlive() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("physicsSoak")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as MascotApp
        val output=File(context.getExternalFilesDir(null),"physics-acceptance").apply{mkdirs()}
        val trace=File(output,"soak-setup.jsonl").apply{writeText("")}
        val stop=File(output,"soak-stop").apply{delete()}
        fun stage(name:String){trace.appendText("{\"time_ms\":${System.currentTimeMillis()},\"stage\":\"$name\"}\n")}
        stage("method_started")
        runBlocking { app.session.saveAcceptedMascot(PhysicsStore.PILOT_ID) }
        val behavior=app.behavior
        val previous=runBlocking { behavior.refresh(fetchExternal=false) }
        runBlocking { behavior.store.update { it.copy(currentStateId=PetState.IDLE,manualSleep=false,
            stateStartedAt=System.currentTimeMillis(),stateExpiresAt=System.currentTimeMillis()+900000,
            priority=2,triggerReason="instrumented_idle_soak",lastRenderedAction="idle",revision=it.revision+1) } }
        stage("idle_held")
        try {
            val launchTime=System.currentTimeMillis()
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            stage("activity_requested")
            val folder=File(context.getExternalFilesDir(null),"physics-metrics")
            var metrics:File?=null
            val deadline=SystemClock.elapsedRealtime()+90000
            while(SystemClock.elapsedRealtime()<deadline && metrics==null) {
                assertFalse("Soak cancelled",stop.exists())
                metrics=folder.listFiles().orEmpty().filter { it.lastModified()>=launchTime && it.readLines().size>1 }.maxByOrNull { it.lastModified() }
                if(metrics==null)SystemClock.sleep(200)
            }
            val file=requireNotNull(metrics){"No real physics frames after app launch"}
            stage("renderer_measured")
            val start=SystemClock.elapsedRealtime()
            File(output,"soak-start.json").writeText("""{"start_epoch_ms":${System.currentTimeMillis()},"pack":"${PhysicsStore(context).revision(PhysicsStore.PILOT_ID)}","metrics_file":"${file.name}","real_application":true,"controlled_inputs":false,"idle_held_for_measurement":true,"real_frame_clock":true}""")
            while(SystemClock.elapsedRealtime()-start<610000) {
                SystemClock.sleep(1000)
                assertFalse("Soak cancelled",stop.exists())
                val last=file.readLines().last().substringBefore(',').toLongOrNull()
                assertNotNull(last)
                assertTrue("The physics renderer stopped",System.currentTimeMillis()-last!!<25000)
            }
            val rows=file.readLines()
            assertTrue("Missing ten-minute metrics",rows.size>=60)
            assertFalse(rows.any{it.contains("NaN")||it.contains("Infinity")})
            File(output,"soak-result.json").writeText("""{"duration_ms":${SystemClock.elapsedRealtime()-start},"metrics_windows":${rows.size-1},"real_application":true,"controlled_inputs":false,"idle_held_for_measurement":true,"real_frame_clock":true}""")
        } finally {
            runBlocking { behavior.store.update { it.copy(currentStateId=previous.currentStateId,
                stateStartedAt=previous.stateStartedAt,stateExpiresAt=previous.stateExpiresAt,
                priority=previous.priority,triggerReason=previous.triggerReason,manualSleep=previous.manualSleep,
                lastRenderedAction=previous.lastRenderedAction,revision=it.revision+1) } }
            File(output,"soak-state-restored.json").writeText("""{"restored":true}""")
            stage("state_restored")
        }
    }
}
