package com.generativemascot.app.overlay

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayMenuInstrumentedTest {
    @Test fun compactMenuRemainsAvailableAfterDismissingTheLastReply() {
        assertEquals(OverlayControls.REPLY, overlayControls(true, false, true, true))
        assertEquals(OverlayControls.COMPACT, overlayControls(true, false, false, true))
        assertEquals(OverlayControls.COMPACT, overlayControls(true, false, false, false))
        assertEquals(OverlayControls.COMPACT, overlayControls(true, true, true, true))
        assertEquals(OverlayControls.NONE, overlayControls(false, true, true, true))
    }

    @Test fun hidingTheMessageIndicatorLeavesTheMenuHandleInteractive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var opened = false
            val menu = OverlayCompactView(instrumentation.targetContext) { opened = true }
            menu.setHasMessage(true)
            menu.setHasMessage(false)
            assertEquals(View.VISIBLE, menu.getChildAt(0).visibility)
            assertTrue(menu.performClick())
            assertTrue(opened)
        }
    }
}
