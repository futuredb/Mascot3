package com.generativemascot.app.flowdemo

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.state.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Separate offline package, private test directory: never opens or mutates production heroes/jobs. */
@RunWith(AndroidJUnit4::class)
class StateSystemInstrumentedTest {
    private fun context() = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        private val directory = File(super.getCacheDir(), "state-test-${System.nanoTime()}").apply { mkdirs() }
        override fun getFilesDir(): File = directory
    }
    private val now = Instant.parse("2026-09-18T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    private val ready = PetState.entries.map { it.action }.toSet()

    @Test fun manualSleepAndStableEventMarkersSurviveRealAndroidAtomicFileReopen() = runBlocking {
        val ctx = context()
        val resolver = StateResolver(Random(9))
        val store = StateStore(ctx)
        val held = store.update { resolver.resolve(it, now, event = StateEvent.HOLD, available = ready, zone = zone) }
        assertTrue(held.manualSleep)
        val reopened = StateStore(ctx)
        assertEquals(held, reopened.current)
        val woken = reopened.update { resolver.resolve(it, now + 1000, event = StateEvent.TAP, available = ready, zone = zone) }
        assertFalse(woken.manualSleep)
        assertNotEquals(PetState.SLEEP, woken.currentStateId)
    }

    @Test fun appAndWidgetShareSemanticStateAndMissingVideoFallbackAfterReopen() = runBlocking {
        val ctx = context()
        val selected = StateStore(ctx).update { it.copy(currentStateId = PetState.SCARED,
            triggerReason = "weather_strong", lastRenderedAction = "idle", stateStartedAt = now) }
        val reopened = StateStore(ctx).current
        assertEquals(selected, reopened)
        assertEquals("idle", renderedAction(selected, setOf("idle", "greeting")))
        assertEquals(renderedAction(selected, setOf("idle")), renderedAction(reopened, setOf("idle")))
        assertEquals(PetState.SCARED, reopened.currentStateId)
    }

    @Test fun disabledHealthNeedsNoPermissionsAndNeverChangesTimeOnlyNightRule() = runBlocking {
        val cfg = StateConfig(healthTriggersEnabled = false)
        assertTrue(HealthSignals.requiredPermissions(cfg).isEmpty())
        val health = HealthSignals.read(context(), cfg, now)
        assertNull(health.stepsToday)
        val night = Instant.parse("2026-09-18T23:15:00Z").toEpochMilli()
        val state = StateResolver(Random(4)).resolve(StateRecord(), night, cfg,
            StateSignals(health = health), available = ready, zone = zone)
        assertEquals(PetState.SLEEP, state.currentStateId)
        assertEquals(Instant.parse("2026-09-19T07:00:00Z").toEpochMilli(), state.stateExpiresAt)
    }
}
