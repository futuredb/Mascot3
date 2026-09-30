package com.generativemascot.app.state

import android.app.Application
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StateStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun manualSleepCooldownsAndHealthDedupeSurviveReopening() = runBlocking {
        val saved = StateStore(context).update { it.copy(currentStateId = PetState.SLEEP,
            manualSleep = true, stateExpiresAt = Long.MAX_VALUE, heroId = "hero1",
            lastTriggerAt = mapOf(PetState.GREET to 123L), lastHealthEventAt = mapOf("goal:today" to 234L)) }
        assertEquals(saved, StateStore(context).current)
    }
    @Test fun concurrentUpdatesDoNotLoseTransitions() = runBlocking {
        val store = StateStore(context)
        val old = store.current.revision
        List(20) { async { store.update { it.copy(revision = it.revision + 1) } } }.awaitAll()
        assertEquals(old + 20, StateStore(context).current.revision)
    }
    @Test fun corruptBehaviorStateNeverTouchesHeroOrGenerationData() {
        val hero = File(context.filesDir, "hero/hero1/name.txt").apply { parentFile!!.mkdirs(); writeText("Аким") }
        val jobs = File(context.filesDir, "generation-requests/job.base.started").apply { parentFile!!.mkdirs(); writeText("paid") }
        File(context.filesDir, "behavior/state-v3.json").apply { parentFile!!.mkdirs(); writeText("invalid JSON") }
        assertEquals(PetState.IDLE, StateStore(context).current.currentStateId)
        assertEquals("Аким", hero.readText())
        assertEquals("paid", jobs.readText())
    }
    @Test fun configurationIsValidatedAndInvalidSavePreservesPreviousRules() {
        val store = StateConfigStore(context)
        val config = StateConfig(rareFrequency = 0.2, activityGoalValue = 6500)
        store.save(stateJson.encodeToString(config))
        try { store.save("{\"networkDebounceSeconds\":1}"); fail("Must reject") } catch (_: IllegalArgumentException) { }
        assertEquals(config, StateConfigStore(context).get())
    }
    @Test fun analyticsDoesNotLeakHealthValuesOrIdentifiersOrCredentials() {
        val record = StateRecord(currentStateId = PetState.JOY, triggerReason = "health_goal", healthSnapshot = HealthSnapshot(stepsToday = 12345), heroId = "privateHero")
        StateAnalytics(context).log("state_started", record, PetState.IDLE, 1000, "app", "joyful")
        val text = File(context.filesDir, "behavior/events.jsonl").readText()
        assertTrue(text.contains("health_goal"))
        for (private in listOf("12345", "stepsToday", "privateHero", "SSID", "apiKey")) assertFalse(private, text.contains(private))
    }
    @Test fun healthIsOptionalAndPermissionSelectionFollowsFeatureFlags() {
        assertEquals(3, HealthSignals.requiredPermissions(StateConfig()).size)
        assertEquals(1, HealthSignals.requiredPermissions(StateConfig(sleepTriggersEnabled = false, exerciseTriggersEnabled = false)).size)
        assertTrue(HealthSignals.requiredPermissions(StateConfig(sleepTriggersEnabled = false, exerciseTriggersEnabled = false,
            stepTriggersEnabled = false, activityGoalTriggerEnabled = false)).isEmpty())
    }
    @Test fun appAndWidgetUseTheSameRenderingFallbackWithoutChangingGlobalState() {
        val r = StateRecord(currentStateId = PetState.SLEEP, lastRenderedAction = "greeting")
        val available = setOf("greeting")
        assertEquals(renderedAction(r, available), renderedAction(r, available))
        assertEquals("greeting", renderedAction(r, available))
        assertEquals(PetState.SLEEP, r.currentStateId)
    }
}
