package com.generativemascot.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CharacterCommunicationPersonalityTest {
    @Test fun emptyPersonalityKeepsEverySliderCentered() {
        val profile = CharacterCommunication.fromPersonality("")
        CommunicationTrait.entries.forEach { assertEquals(50, profile[it]) }
    }

    @Test fun boldPersonalityStartsDirectAndProfanityFriendly() {
        val profile = CharacterCommunication.fromPersonality("Дерзкий и уверенный в себе", 17)
        assertEquals(100, profile[CommunicationTrait.BREVITY])
        assertEquals(100, profile[CommunicationTrait.PROFANITY])
        assertEquals(100, profile[CommunicationTrait.DIRECTNESS])
    }

    @Test fun mixedPersonalityCombinesTraitsAndIsStableForOneSeed() {
        val text = "Дерзкий и уверенный в себе. Любопытный, любит исследовать новое"
        val first = CharacterCommunication.fromPersonality(text, 91)
        val repeated = CharacterCommunication.fromPersonality(text, 91)
        assertEquals(first, repeated)
        assertNotEquals(CharacterCommunication(), first)
        assertEquals(100, first[CommunicationTrait.COMPLEXITY])
        assertEquals(100, first[CommunicationTrait.DIRECTNESS])
    }
}
