package com.generativemascot.app.ui

import com.generativemascot.app.data.characterPersonalities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalityOrbAssetTest {
    @Test fun everyPersonalityHasItsOwnBackgroundAndOnlySuppliedLoopsAreMapped() {
        assertEquals(characterPersonalities.map { it.id }.toSet(), personalityOrbAssets.keys)
        assertEquals(12, personalityOrbAssets.values.map { it.image }.toSet().size)
        assertEquals(setOf("neutral", "direct", "friendly", "concise", "executive"),
            personalityOrbAssets.filterValues { it.video != null }.keys)
        assertEquals(5, personalityOrbAssets.values.mapNotNull { it.video }.toSet().size)
        assertTrue(personalityOrbAssets.values.all { it.image != 0 })
    }
}
