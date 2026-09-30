package com.generativemascot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationSoundGateTest {
    @Test fun eachExplicitRequestPlaysOnceAndResumeDoesNotReplay() {
        var plays = 0
        var stops = 0
        val gate = GenerationSoundGate({ plays++ }, { stops++ })
        gate.resume()
        gate.onLoaded(true)
        gate.request()
        assertEquals(1, plays)
        gate.pause()
        gate.resume()
        assertEquals(1, plays)
        gate.request()
        assertEquals(2, plays)
        assertEquals(1, stops)
    }

    @Test fun rapidRequestsBeforeLoadAreCoalescedIntoOneSound() {
        var plays = 0
        val gate = GenerationSoundGate({ plays++ }, {})
        gate.resume()
        repeat(5) { gate.request() }
        assertEquals(0, plays)
        gate.onLoaded(true)
        gate.onLoaded(true)
        assertEquals(1, plays)
    }

    @Test fun leavingBeforeLoadCancelsPendingSoundAndResumeDoesNotPlayIt() {
        var plays = 0
        val gate = GenerationSoundGate({ plays++ }, {})
        gate.resume()
        gate.request()
        gate.pause()
        gate.onLoaded(true)
        gate.resume()
        assertEquals(0, plays)
    }

    @Test fun backgroundRequestsAndLateCallbacksAfterReleaseNeverPlay() {
        var plays = 0
        var stops = 0
        val gate = GenerationSoundGate({ plays++ }, { stops++ })
        gate.onLoaded(true)
        gate.request()
        assertEquals(0, plays)
        gate.resume()
        gate.close()
        gate.close()
        gate.onLoaded(true)
        gate.resume()
        gate.request()
        assertEquals(0, plays)
        assertEquals(1, stops)
    }

    @Test fun failedLoadDiscardsPendingRequestWithoutCrashingOrReplaying() {
        var plays = 0
        val gate = GenerationSoundGate({ plays++ }, {})
        gate.resume()
        gate.request()
        gate.onLoaded(false)
        gate.onLoaded(true)
        assertEquals(0, plays)
        gate.request()
        assertEquals(1, plays)
    }
}
