package com.generativemascot.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroLocalStoreWidgetTimingTest {
    @Test fun fullAndShortLoopsKeepTheirOriginalDuration() {
        for (duration in listOf(1_000L, 2_800L, 6_000L, 8_000L, 20_000L, 112_000L)) {
            val count = HeroLocalStore.widgetFrameCountForDuration(duration)
            val interval = HeroLocalStore.widgetFrameIntervalForDuration(duration, count)
            org.junit.Assert.assertTrue(kotlin.math.abs(count * interval - duration) <= interval / 2 + 1)
        }
    }
    @Test
    fun sixSecondClipUsesSeventyTwoFramesAtTwelveFps() {
        assertEquals(72, HeroLocalStore.widgetFrameCountForDuration(6_000))
    }

    @Test
    fun frameCountIsBoundedForShortAndLongClips() {
        assertEquals(12, HeroLocalStore.widgetFrameCountForDuration(1_000))
        assertEquals(80, HeroLocalStore.widgetFrameCountForDuration(20_000))
        assertEquals(250, HeroLocalStore.widgetFrameIntervalForDuration(20_000, 80))
    }
}
