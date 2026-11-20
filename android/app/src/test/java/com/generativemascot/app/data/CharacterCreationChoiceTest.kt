package com.generativemascot.app.data

import org.junit.Assert.*
import org.junit.Test

class CharacterCreationChoiceTest {
    @Test fun creationTraitsDriveEachChatReactionPrompt() {
        val profiles = mapOf(
            "Спокойный и уравновешенный" to CharacterPerformanceStyle.CALM,
            "Дерзкий и уверенный в себе" to CharacterPerformanceStyle.BOLD,
            "Любопытный, любит исследовать новое" to CharacterPerformanceStyle.CURIOUS,
            "Застенчивый и деликатный" to CharacterPerformanceStyle.SHY,
            "Мечтательный, с живым воображением" to CharacterPerformanceStyle.DREAMY,
            "Жизнерадостный и энергичный" to CharacterPerformanceStyle.JOYFUL,
        )
        for ((traits, style) in profiles) {
            val choice = CharacterCreationChoice("person", "neutral", appearancePrompt = "Потёртая куртка",
                personalityPrompt = traits)
            val contract = mobileCharacterContract(71, choice)
            assertEquals(traits, contract.behavior.worldview)
            assertEquals(style, characterPerformanceStyle(contract.behavior.worldview))
            for (action in chatReactionActions) {
                val prompt = buildCharacterAnimationPrompt(action, seed = 71, character = contract,
                    appearanceNotes = choice.appearancePrompt)
                assertTrue(prompt.contains(style.motion))
                assertTrue(prompt.contains("Потёртая куртка"))
                assertTrue(prompt.contains("only where they are visible in the supplied image"))
                assertEquals(style == CharacterPerformanceStyle.BOLD && action == "angry", prompt.contains("middle-finger"))
                validateResolvedVideoPrompt(prompt, "#00FF00")
            }
        }
    }

    @Test fun freeTextAppearanceFitsTheProviderPromptAndDoesNotIntroduceSceneGeometry() {
        val notes = "glass visor and weathered armor " + "layered fabric ".repeat(60)
        val choice = CharacterCreationChoice("person", "neutral", appearancePrompt = notes,
            personalityPrompt = "Дерзкий " + "уверенный ".repeat(60))
        val prompt = buildCharacterAnimationPrompt("angry", character = mobileCharacterContract(71, choice),
            appearanceNotes = choice.appearancePrompt)
        assertTrue(prompt.contains("visor and weathered armor"))
        assertFalse(prompt.contains("glass visor"))
        validateResolvedVideoPrompt(prompt, "#00FF00")
    }

    @Test fun referenceCharactersUseTheFullAnimationContract() {
        val choice = CharacterCreationChoice("person", "friendly", referencePath = "/private/reference.jpg")
        val contract = mobileCharacterContract(71, choice)
        for (action in HeroLocalStore.LIBRARY_VIDEO_ACTIONS) {
            val prompt = buildCharacterAnimationPrompt(action, seed = 71, character = contract)
            assertTrue(prompt.contains(contract.body.subject))
            assertTrue(prompt.contains(contract.body.visibleParts))
            assertTrue(prompt.contains("ORDERED PERFORMANCE BEATS"))
            validateResolvedVideoPrompt(prompt, "#00FF00")
        }
    }
    @Test fun identifiesProviderRejectionForRealPersonWithoutTreatingEvery400AsThatError() {
        assertTrue(isRealPersonImageRejection("{\"code\":\"InputImageSensitiveContentDetected.PrivacyInformation\"}"))
        assertTrue(isRealPersonImageRejection("The input image may contain real person"))
        assertFalse(isRealPersonImageRejection("invalid aspect ratio"))
    }
    @Test fun referenceOverridesPersonalityPaletteAndRandomAppearance() {
        val choice = CharacterCreationChoice("person", "friendly", referencePath = "/private/reference.jpg")
        val contract = mobileCharacterContract(71, choice)
        val prompt = buildCanonicalCharacterPrompt(71, choice)
        assertTrue(prompt.contains("USER REFERENCE PRIORITY"))
        assertTrue(prompt.contains("overrides the writer's randomly suggested"))
        assertFalse(prompt.contains("Palette: ${contract.palette}"))
        assertFalse(prompt.contains("Body profile: ${contract.body.visibleParts}"))
        assertTrue(prompt.contains(characterPersonalityById("friendly")!!.tone))
    }
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
