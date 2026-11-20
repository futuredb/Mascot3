package com.generativemascot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatIdleFramingTest {
    @Test fun identicalSourcesDoNotMove() {
        val bounds = HeroGrounding(1000, 1000, 900, 200, 100, 800)
        val result = chatIdleFraming(bounds, bounds)
        assertEquals(1f, result.scale, .001f)
        assertEquals(0f, result.x, .001f)
        assertEquals(0f, result.y, .001f)
    }

    @Test fun smallerNeutralCharacterIsEnlargedAndAligned() {
        val base = HeroGrounding(1000, 1000, 900, 250, 100, 750)
        val video = HeroGrounding(1000, 1000, 700, 375, 300, 625)
        val result = chatIdleFraming(base, video)
        assertEquals(2f, result.scale, .001f)
        assertEquals(0f, result.x, .001f)
        assertEquals(0f, result.y, .001f)
    }

    @Test fun missingReferenceFallsBackSafely() {
        assertEquals(ChatIdleFraming(), chatIdleFraming(null, null))
    }
}
