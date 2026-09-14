package com.generativemascot.app.ui

import com.generativemascot.app.data.HeroLocalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeActionDirectorTest {
    @Test
    fun `home pool uses every authored action except baseline and deliberate sleep`() {
        val pool = mainScreenActionPool(HeroLocalStore.VIDEO_ACTIONS.toSet())

        assertEquals(HeroLocalStore.LIBRARY_VIDEO_ACTIONS.size - 2, pool.size)
        assertFalse("idle" in pool)
        assertFalse("sleeping" in pool)
        assertFalse("sleep_loop" in pool)
        assertTrue("resting" in pool)
        assertTrue("thinking" in pool)
        assertTrue("signature_move" in pool)
        assertTrue("dancing" in pool)
    }

    @Test
    fun `home pool only exposes actions that exist on the device`() {
        val pool = mainScreenActionPool(setOf("idle", "happy", "greeting", "sleeping"))

        assertEquals(listOf("joyful", "greeting"), pool)
    }
}
