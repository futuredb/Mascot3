package com.generativemascot.app.data

import org.junit.Assert.*
import org.junit.Test

class CharacterCreationChoiceTest {
    @Test fun accessErrorsDistinguishKeyFromRegionAndPrivacyRestrictions() {
        assertTrue(describeOpenRouterAccessError(401, "invalid key").contains("ключ"))
        assertTrue(describeOpenRouterAccessError(403, "unsupported country").contains("регион"))
        assertTrue(describeOpenRouterAccessError(403, "data policy denied").contains("приватност"))
        assertTrue(describeOpenRouterAccessError(403, "unknown").contains("HTTP 403"))
        assertFalse(describeOpenRouterAccessError(403, "unknown").contains("не принял ключ"))
    }
    @Test fun selectionChangesTheWriterAndArtDirection() {
        val choice = CharacterCreationChoice("person", "direct")
        val contract = mobileCharacterContract(71, choice)
        val prompt = buildCanonicalCharacterPrompt(71, choice)
        assertEquals("person", contract.body.archetype)
        assertTrue(prompt.contains("stylized human character"))
        assertTrue(prompt.contains("deep crimson, red, orange and near-black"))
        assertTrue(prompt.contains("Честно указывай"))
        assertNotEquals(mobileCharacterContract(71).body.subject, contract.body.subject)
    }

    @Test fun everyStyleHasAValidPaletteAndCommunicationTone() {
        assertEquals(12, characterPersonalities.size)
        assertEquals(12, characterPersonalities.map { it.id }.toSet().size)
        characterPersonalities.forEach { style ->
            val profile = CharacterCreationChoice("animal", style.id)
            val contract = mobileCharacterContract(42, profile)
            assertEquals("animal", contract.body.archetype)
            assertEquals(style.palette, contract.palette)
            assertTrue(contract.behavior.worldview.contains(style.tone))
        }
    }

    @Test fun everyTypeAndPersonalitySurviveIntoImageAndAnimationPrompts() {
        for (type in listOf("person", "animal", "creature")) for (style in characterPersonalities) {
            val choice = CharacterCreationChoice(type, style.id)
            val contract = mobileCharacterContract(83, choice)
            val image = buildCanonicalCharacterPrompt(83, choice)
            val video = buildCharacterAnimationPrompt("greeting", 83, character = contract)
            assertEquals(type, contract.body.archetype)
            assertTrue(image.contains(contract.body.subject))
            assertTrue(image.contains(contract.body.visibleParts))
            assertTrue(image.contains(style.palette))
            assertTrue(image.contains(style.tone))
            assertTrue(video.contains(contract.body.subject))
            assertTrue(video.contains(contract.body.visibleParts))
            assertTrue(video.contains(style.tone))
            if (type == "person") {
                assertTrue(image.contains("stylized 3D human"))
                assertTrue(image.contains("not pixel art"))
            }
        }
    }

    @Test fun animalChoiceUsesDistinctRecognisableSpeciesAndPersistsItsAnatomy() {
        val animals = (0 until 64).map { seed -> mobileCharacterContract(seed, CharacterCreationChoice("animal", "neutral")) }
        assertTrue(animals.map { it.body.subject }.toSet().size >= 6)
        assertTrue(animals.all { it.body.archetype == "animal" && it.body.visibleParts.isNotBlank() })
    }

    @Test fun prototypeQuadrantsCarryTheirOwnToneAndArtDirection() {
        assertEquals(4, interactionPersonalities.size)
        interactionPersonalities.forEach { style ->
            val choice = CharacterCreationChoice("person", style.id, .25f, .75f)
            val contract = mobileCharacterContract(42, choice)
            assertEquals(style.palette, contract.palette)
            assertEquals(style.tone, contract.behavior.worldview)
            assertEquals(style, characterPersonalityById(style.id))
        }
        assertTrue(runCatching { CharacterCreationChoice("person", "request_solutions", 1.1f, .5f) }.isFailure)
        assertTrue(runCatching { CharacterCreationChoice("person", "request_solutions", .5f, null) }.isFailure)
    }

    @Test fun unknownChoicesCannotBeSavedAsValidProfiles() {
        assertTrue(runCatching { CharacterCreationChoice("unknown", "neutral") }.isFailure)
        assertTrue(runCatching { CharacterCreationChoice("person", "unknown") }.isFailure)
    }
}
