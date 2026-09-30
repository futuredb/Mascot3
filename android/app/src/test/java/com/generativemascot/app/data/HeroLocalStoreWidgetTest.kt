package com.generativemascot.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroLocalStoreWidgetTest {
    @Test
    fun sleepingWidgetPrefersTheAlreadyDerivedLoop() {
        assertEquals(
            listOf("sleep_loop", "sleeping", "idle"),
            HeroLocalStore.widgetVideoCandidates("sleeping"),
        )
    }

    @Test
    fun ambientStatesHaveAUsableVideoFallback() {
        assertEquals(listOf("thinking", "idle"), HeroLocalStore.widgetVideoCandidates("working"))
        assertEquals(listOf("sad", "idle"), HeroLocalStore.widgetVideoCandidates("rainy"))
        assertEquals(listOf("idle", "joyful"), HeroLocalStore.widgetVideoCandidates("content"))
    }

    @Test
    fun workingStateNeverFallsBackToContinuousDancing() {
        val candidates = HeroLocalStore.widgetVideoCandidates("working")

        assertEquals(false, "dancing" in candidates)
        assertEquals("idle", candidates.last())
    }

    @Test
    fun libraryActionKeepsItsOwnAnimation() {
        assertEquals(listOf("signature_move", "idle"), HeroLocalStore.widgetVideoCandidates("signature_move"))
    }
}
