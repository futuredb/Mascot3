package com.generativemascot.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeliosAutoStartTest {
    @Test fun startsWhenForegroundPlayerIsReadyAndHasNoStream() {
        assertTrue(shouldAutoStartHelios(keepWarm = true, ready = true, running = false))
        assertFalse(shouldAutoStartHelios(keepWarm = true, ready = true, running = true))
        assertFalse(shouldAutoStartHelios(keepWarm = false, ready = true, running = false))
        assertFalse(shouldAutoStartHelios(keepWarm = true, ready = false, running = false))
    }

    @Test fun reconnectDelayGrowsAndIsBounded() {
        assertTrue(heliosRetryDelayMillis(0) == 0L)
        assertTrue(heliosRetryDelayMillis(1) == 5_000L)
        assertTrue(heliosRetryDelayMillis(2) == 10_000L)
        assertTrue(heliosRetryDelayMillis(5) == 60_000L)
        assertTrue(heliosRetryDelayMillis(500) == 60_000L)
    }
}
