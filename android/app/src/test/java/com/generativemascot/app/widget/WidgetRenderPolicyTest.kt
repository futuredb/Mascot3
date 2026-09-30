package com.generativemascot.app.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetRenderPolicyTest {
    @Test fun activeClipFitsItsDecodedMemoryBudget() {
        for (count in 1..96) {
            val side = widgetBitmapSide(count)
            assertTrue(side in 1..320)
            assertTrue(count * side * side * 4 <= WIDGET_BITMAP_BUDGET_BYTES)
        }
    }
    @Test fun invalidOrOversizedBatchesAreNotSentToTheLauncher() {
        assertTrue(runCatching { widgetBitmapSide(0) }.isFailure)
        assertTrue(runCatching { widgetBitmapSide(97) }.isFailure)
    }
    @Test fun repeatedRefreshDoesNotRecreateTheSameAnimation() {
        assertFalse(widgetNeedsUpdate("same", "same", false))
        assertTrue(widgetNeedsUpdate("old", "new", false))
        assertTrue(widgetNeedsUpdate(null, "new", false))
        assertTrue(widgetNeedsUpdate("same", "same", true))
    }
    @Test fun slowPreparationCannotOverwriteANewHeroOrNewMood() {
        assertTrue(preparedWidgetStateIsCurrent("hero-a", "sleeping", "hero-a", "sleeping"))
        assertFalse(preparedWidgetStateIsCurrent("hero-a", "sleeping", "hero-b", "sleeping"))
        assertFalse(preparedWidgetStateIsCurrent("hero-a", "joyful", "hero-a", "sleeping"))
        assertFalse(preparedWidgetStateIsCurrent("hero-a", "joyful", null, "joyful"))
    }
}
