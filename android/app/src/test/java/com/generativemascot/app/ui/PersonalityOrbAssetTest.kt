package com.generativemascot.app.ui

import com.generativemascot.app.data.characterPersonalities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalityOrbAssetTest {
    @Test fun everyPersonalityHasItsOwnBackgroundAndSuppliedLoop() {
        assertEquals(characterPersonalities.map { it.id }.toSet(), personalityOrbAssets.keys)
        assertEquals(12, personalityOrbAssets.values.map { it.image }.toSet().size)
        assertEquals(characterPersonalities.map { it.id }.toSet(),
            personalityOrbAssets.filterValues { it.video != null }.keys)
        assertEquals(12, personalityOrbAssets.values.mapNotNull { it.video }.toSet().size)
        assertTrue(personalityOrbAssets.values.all { it.image != 0 })
    }
}
