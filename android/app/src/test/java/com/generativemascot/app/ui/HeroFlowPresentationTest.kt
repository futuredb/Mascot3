package com.generativemascot.app.ui

import com.generativemascot.app.data.MascotDto
import org.junit.Assert.*
import org.junit.Test

class HeroFlowPresentationTest {
    private val approval = AppUiState(ready = true, mascot = MascotDto(
        id = "new-hero", name = "Аким", status = "AWAITING_ACCEPTANCE", previewUrl = "file:///base.png"))

    @Test fun acceptanceSynchronouslyEntersHomeBeforeAnyWorkerOrNetworkResponse() {
        assertFalse(approval.showsHeroHome)
        assertEquals("generation", heroFlowContentKey(approval))
        val started = approval.withGreetingStarted()
        assertTrue(started.showsHeroHome)
        assertEquals("home", heroFlowContentKey(started))
        assertEquals("READY", started.mascot?.status)
        assertEquals("new-hero", started.mascot?.id)
        assertEquals("Аким", started.mascot?.name)
        assertTrue(started.creating && started.busy && started.greetingPending)
        assertNull(started.context)
        assertEquals(listOf("greeting"), started.animationBatchActions)
    }

    @Test fun interruptionAndRecoveryNeverReturnToKnockOrChangeTheHero() {
        val failed = approval.withGreetingStarted().copy(creating = false, busy = false, error = "HTTP 429")
        val restored = failed.copy(creating = true, busy = true, error = null)
        val completed = restored.copy(creating = false, busy = false, greetingPending = false)
        for (state in listOf(failed, restored, completed)) {
            assertTrue(state.showsHeroHome)
            assertEquals("home", heroFlowContentKey(state))
            assertEquals("new-hero", state.mascot?.id)
            assertEquals("Аким", state.mascot?.name)
        }
    }

    @Test fun greetingCompletionKeepsTheSameHomeContentIdentity() {
        val started = approval.withGreetingStarted()
        assertEquals(heroFlowContentKey(started), heroFlowContentKey(started.copy(greetingPending = false)))
        assertEquals("animations", heroFlowContentKey(started.copy(route = "animations")))
        assertEquals("settings", heroFlowContentKey(started.copy(route = "settings")))
    }

    @Test fun onlyExplicitAcceptanceEntersHomeForANewHero() {
        for (status in listOf("DRAFT", "BASE_GENERATING", "AWAITING_ACCEPTANCE", "FAILED_FINAL")) {
            assertFalse(approval.copy(mascot = approval.mascot?.copy(status = status)).showsHeroHome)
        }
        assertFalse(AppUiState(ready = true).showsHeroHome)
    }
}
