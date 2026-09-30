package com.generativemascot.app.ui

import org.junit.Assert.*
import org.junit.Test

class ForegroundWelcomeGateTest {
    @Test fun aGreetingOnlyHeroDoesNotPretendItCanAlreadySleepOrRejoice() {
        assertEquals("greeting", resolveAvailableHomeAction("sleeping", setOf("greeting")))
        assertEquals("greeting", resolveAvailableHomeAction("joyful", setOf("greeting")))
        assertEquals("idle", resolveAvailableHomeAction("sleeping", setOf("idle")))
    }
    @Test fun savedAndLegacyActionsKeepTheirExistingBehavior() {
        assertEquals("sleeping", resolveAvailableHomeAction("sleeping", setOf("idle", "sleeping", "greeting")))
        assertEquals("joyful", resolveAvailableHomeAction("joyful", emptySet()))
        assertEquals("dancing", resolveAvailableHomeAction("dancing", setOf("idle", "dancing")))
    }
    @Test fun onlyGreetingLoopsAsTheBaselineUntilOtherClipsAreReady() {
        assertTrue(greetingIsOnlyVideo(setOf("greeting")))
        assertFalse(greetingIsOnlyVideo(setOf("idle", "greeting")))
        assertFalse(greetingIsOnlyVideo(emptySet()))
        assertFalse(greetingIsOnlyVideo(setOf("welcome")))
    }
    @Test fun greetingOnlyDefaultsToGreetingForEveryUnavailableContext() {
        assertEquals("greeting", defaultHomeVideoAction(setOf("greeting")))
        for (requested in listOf("idle", "thinking", "sleeping", "joyful", "sad")) {
            assertEquals("greeting", resolveAvailableHomeAction(requested, setOf("greeting")))
        }
    }
    @Test fun readyIdleBecomesBaselineAndMultipleClipsDoNotLoopGreetingInPlace() {
        assertEquals("idle", defaultHomeVideoAction(setOf("greeting", "joyful", "idle")))
        assertEquals("greeting", defaultHomeVideoAction(setOf("joyful", "greeting")))
        assertFalse(greetingIsOnlyVideo(setOf("greeting", "joyful")))
        assertEquals("idle", defaultHomeVideoAction(emptySet()))
        assertEquals("joyful", defaultHomeVideoAction(setOf("happy")))
    }
    @Test fun newlyAcceptedHeroGetsGreetingEvenAfterPreviousHeroConsumedAppEntry() {
        val gate = ForegroundWelcomeGate()
        val old = gate.enter()!!
        gate.consume(old)
        val accepted = gate.requestGreeting()!!
        assertTrue(accepted > old)
        assertTrue(gate.consume(accepted))
        assertFalse(gate.consume(accepted))
    }

    @Test fun acceptanceInBackgroundWaitsUntilNextRealAppEntry() {
        val gate = ForegroundWelcomeGate()
        assertNull(gate.requestGreeting())
        val next = gate.enter()!!
        assertTrue(gate.consume(next))
    }
    @Test fun firstEntryHasOneConsumableGreeting() {
        val gate = ForegroundWelcomeGate()
        val request = gate.enter()!!
        assertTrue(gate.consume(request))
        assertFalse(gate.consume(request))
        assertNull(gate.pending)
    }

    @Test fun recompositionMenuReturnAndConfigurationRestartDoNotReissueGreeting() {
        val gate = ForegroundWelcomeGate()
        val request = gate.enter()!!
        assertEquals(request, gate.enter())
        gate.consume(request)
        repeat(4) { assertNull(gate.enter()) }
    }

    @Test fun genuineBackgroundAndReturnCreateANewRequest() {
        val gate = ForegroundWelcomeGate()
        val first = gate.enter()!!
        gate.consume(first)
        gate.leave()
        val second = gate.enter()!!
        assertTrue(second > first)
        assertFalse(gate.consume(first))
        assertTrue(gate.consume(second))
    }

    @Test fun backgroundCancelsAnUnplayedRequest() {
        val gate = ForegroundWelcomeGate()
        val request = gate.enter()!!
        gate.leave()
        assertNull(gate.pending)
        assertFalse(gate.consume(request))
    }

    @Test fun reuseExistingGreetingAndIgnoreRetiredDuplicate() {
        assertEquals("greeting", entryWelcomeAction(setOf("welcome", "greeting", "idle")))
        assertNull(entryWelcomeAction(setOf("welcome", "idle")))
        assertEquals("greeting", entryWelcomeAction(setOf("greeting", "idle")))
        assertNull(entryWelcomeAction(setOf("idle", "joyful", "sleeping", "dancing")))
        assertNull(entryWelcomeAction(emptySet()))
    }

    @Test fun welcomeStartsPromptlyAndUserGesturesCanInterruptIt() {
        assertTrue(shouldInterruptHomeClip("joyful", "greeting", "idle"))
        assertTrue(shouldInterruptHomeClip("greeting", "sleeping", "idle"))
        assertTrue(shouldInterruptHomeClip("greeting", "joyful", "idle"))
        assertFalse(shouldInterruptHomeClip("greeting", "greeting", "idle"))
        assertFalse(shouldInterruptHomeClip("joyful", "dancing", "idle"))
    }
}
