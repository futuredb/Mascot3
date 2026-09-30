package com.generativemascot.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreativePromptContractsTest {
    @Test
    fun sameSeedKeepsOneCanonicalIdentityAcrossResumedJobs() {
        val first = mobileCharacterContract(481516)
        val resumed = mobileCharacterContract(481516)

        assertEquals(first, resumed)
        assertEquals(64, first.fingerprint.length)
        assertNotEquals(first.fingerprint, mobileCharacterContract(481517).fingerprint)
    }

    @Test
    fun canonicalPromptContainsWriterDesignerAndBodyContracts() {
        val character = mobileCharacterContract(42)
        val prompt = buildCanonicalCharacterPrompt(42)

        assertTrue(prompt.contains("WRITER CONTRACT"))
        assertTrue(prompt.contains("CHARACTER-DESIGN CONTRACT"))
        assertTrue(prompt.contains("CANONICAL ANCHOR"))
        assertTrue(prompt.contains(character.body.visibleParts))
        assertTrue(prompt.contains(character.visualSignature.primarySilhouetteCue))
        assertTrue(prompt.contains("solid #00FF00 chroma-key green background"))
        assertTrue(prompt.contains("removed"))
        assertTrue(prompt.contains("locally"))
        assertTrue(prompt.contains("every documented body part"))
    }

    @Test
    fun eightySequentialCharactersKeepDistinctCompositeIdentities() {
        val characters = (0 until 80).map(::mobileCharacterContract)

        assertEquals(80, characters.map { it.fingerprint }.toSet().size)
        assertTrue(characters.map { it.body.subject }.toSet().size >= 10)
        assertTrue(characters.map { it.behavior.motivation }.toSet().size >= 5)
        assertTrue(characters.map { it.materialAndTechnique }.toSet().size >= 5)
    }

    @Test
    fun catalogProducesAnimalsHumanoidsRobotsObjectsAndSpirits() {
        val characters = (0 until 1_000).map(::mobileCharacterContract)
        val archetypes = characters.map { it.body.archetype }.toSet()

        assertTrue(archetypes.containsAll(setOf("creature", "humanoid", "robot", "object", "construct", "spirit")))
        assertTrue(characters.any { it.body.subject.contains("samurai") })
        assertTrue(characters.count { it.body.archetype == "creature" } < characters.size / 2)
    }

    @Test
    fun humanoidPromptDoesNotForceAnimalAnatomy() {
        val seed = (0 until 10_000).first { mobileCharacterContract(it).body.subject.contains("samurai") }
        val character = mobileCharacterContract(seed)
        val prompt = buildCanonicalCharacterPrompt(seed)

        assertEquals("humanoid", character.body.archetype)
        assertTrue(prompt.contains("Archetype: humanoid"))
        assertTrue(prompt.contains("without a weapon"))
        assertTrue(!prompt.contains("complete non-human character"))
    }

    @Test
    fun everyVideoActionHasFourOrderedBeatsAndExactClosure() {
        HeroLocalStore.VIDEO_ACTIONS.forEach { action ->
            val direction = animationContract(action, seed = 73)
            val prompt = buildCharacterAnimationPrompt(action, seed = 73)

            assertEquals(4, direction.beats.size)
            assertTrue(direction.protectedInvariants.size >= 5)
            assertTrue(direction.performanceFreedom.size >= 3)
            direction.beats.forEachIndexed { index, beat ->
                assertTrue(prompt.contains("${index + 1}. $beat"))
            }
            assertTrue(prompt.contains(direction.closure))
            assertTrue(prompt.contains("one complete indivisible body"))
            if (action == "sleep_loop") {
                assertTrue(prompt.contains("identical already-sleeping anchor"))
            } else {
                assertTrue(prompt.contains("supplied first and last images are the identical canonical home anchor"))
            }
        }
    }

    @Test
    fun sleepDefinesPersistentLoopAndDelayedWakeUp() {
        val direction = animationContract("sleeping", seed = 17)
        val prompt = buildCharacterAnimationPrompt("sleeping", seed = 17)

        assertEquals(1.25, direction.loopStartSeconds, 0.0)
        assertEquals(4.75, direction.loopEndSeconds, 0.0)
        assertTrue(prompt.contains("remain closed for the entire loop"))
        assertTrue(prompt.contains("wake and recover during the final 1.25s"))
        assertTrue(prompt.contains("no spontaneous waking"))
    }

    @Test
    fun persistentSleepUsesASeparateClosedBreathingClip() {
        val direction = animationContract("sleep_loop", seed = 17)
        val prompt = buildCharacterAnimationPrompt("sleep_loop", seed = 17)

        assertEquals(0.0, direction.loopStartSeconds, 0.0)
        assertEquals(6.0, direction.loopEndSeconds, 0.0)
        assertTrue(prompt.contains("There is no entrance, wake-up or exit"))
        assertTrue(prompt.contains("eyes remain fully closed for every frame"))
        assertTrue(prompt.contains("identical already-sleeping anchor"))
    }

    @Test
    fun futureVideoPromptsUseAConventionalGreenMatte() {
        val prompt = buildCharacterAnimationPrompt("idle", seed = 12)

        assertTrue(prompt.contains("#00FF00"))
        assertTrue(prompt.contains("perfectly uniform matte"))
        assertEquals(promptSha256(prompt), promptSha256(prompt))
        assertEquals(64, promptSha256(prompt).length)
    }

    @Test
    fun everyResolvedVideoPromptPassesTheProductionGuard() {
        HeroLocalStore.VIDEO_ACTIONS.forEach { action ->
            val prompt = buildCharacterAnimationPrompt(action, seed = 91, matteHex = "#00FF00")

            validateResolvedVideoPrompt(prompt, "#00FF00")
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun productionGuardRejectsPromptThatCanRecreateASet() {
        val prompt = buildCharacterAnimationPrompt("idle", seed = 91) + " Add a floor."

        validateResolvedVideoPrompt(prompt, "#00FF00")
    }

    @Test
    fun diversitySelectionIsDeterministicAndAvoidsExistingFingerprints() {
        val existing = (0 until 24).map(::mobileCharacterContract)
        val first = selectDiverseCreativeSeed(700, existing)
        val second = selectDiverseCreativeSeed(700, existing)

        assertEquals(first, second)
        assertTrue(mobileCharacterContract(first).fingerprint !in existing.map { it.fingerprint })
    }

    @Test
    fun videoManifestDeclaresPersistentSleepTransitions() {
        val transitions = defaultVideoTransitions()

        assertTrue(transitions.any { it.from == "idle" && it.to == "sleeping" })
        assertTrue(transitions.any { it.from == "sleeping" && it.to == "sleeping" })
        assertTrue(transitions.any { it.from == "sleeping" && it.to == "joyful" })
    }

    @Test
    fun providerPackHasSeventeenCatalogActionsAndOneHundredTwelveSeconds() {
        assertEquals(17, HeroLocalStore.LIBRARY_VIDEO_ACTIONS.size)
        assertEquals(112, HeroLocalStore.LIBRARY_VIDEO_ACTIONS.sumOf(::videoDurationSeconds))
        assertEquals(
            HeroLocalStore.LIBRARY_VIDEO_ACTIONS.size,
            HeroLocalStore.LIBRARY_VIDEO_ACTIONS.map { animationContract(it, seed = 19).actionGoal }.toSet().size,
        )
    }

    @Test fun appEntryReusesTheExistingGreetingPrompt() {
        val prompt = buildCharacterAnimationPrompt("greeting", seed = 19)
        assertEquals(6, videoDurationSeconds("greeting"))
        assertFalse("welcome" in HeroLocalStore.LIBRARY_VIDEO_ACTIONS)
        assertTrue("greeting" in HeroLocalStore.LIBRARY_VIDEO_ACTIONS)
        validateResolvedVideoPrompt(prompt, "#00FF00")
    }

    @Test fun greetingPromptRemainsValidAcrossDifferentCharacterBodyProfiles() {
        repeat(96) { seed ->
            validateResolvedVideoPrompt(buildCharacterAnimationPrompt("greeting", seed = seed), "#00FF00")
        }
    }

    @Test
    fun videoQaAcceptsAClosedWellFramedClip() {
        val qa = evaluateVideoQa(
            action = "sleep_loop",
            expectedDurationSeconds = 6,
            durationMs = 6_040,
            width = 720,
            height = 1280,
            sampleCount = 5,
            minimumBorderKeyRatio = 0.97,
            minimumFrameMargin = 0.12,
            endpointDelta = 0.012,
            matteHex = "#00FF00",
        )

        assertTrue(qa.hardPass)
        assertTrue(qa.blockingIssues.isEmpty())
    }

    @Test
    fun videoQaKeepsPaidClipAndWarnsAboutVisualDefects() {
        val qa = evaluateVideoQa(
            action = "sleep_loop",
            expectedDurationSeconds = 6,
            durationMs = 6_040,
            width = 720,
            height = 1280,
            sampleCount = 5,
            minimumBorderKeyRatio = 0.51,
            minimumFrameMargin = 0.0,
            endpointDelta = 0.24,
            matteHex = "#00FF00",
        )

        assertTrue(qa.hardPass)
        assertTrue(qa.blockingIssues.isEmpty())
        assertTrue(qa.warnings.size >= 3)
    }

    @Test
    fun videoQaStillRejectsTechnicallyInvalidClip() {
        val qa = evaluateVideoQa(
            action = "joyful",
            expectedDurationSeconds = 6,
            durationMs = 180,
            width = 320,
            height = 320,
            sampleCount = 1,
            minimumBorderKeyRatio = 1.0,
            minimumFrameMargin = 0.2,
            endpointDelta = 0.0,
            matteHex = "#00FF00",
        )

        assertTrue(!qa.hardPass)
        assertTrue(qa.blockingIssues.size >= 3)
    }

    @Test
    fun idleAllowsAModerateEndpointDifferenceHandledByThePlayerCrossfade() {
        val qa = evaluateVideoQa(
            action = "idle",
            expectedDurationSeconds = 8,
            durationMs = 8_042,
            width = 960,
            height = 960,
            sampleCount = 5,
            minimumBorderKeyRatio = 1.0,
            minimumFrameMargin = 0.171,
            endpointDelta = 0.1062,
            matteHex = "#00FF00",
        )

        assertTrue(qa.hardPass)
        assertTrue(qa.warnings.isNotEmpty())
    }

    @Test
    fun sleepLoopAllowsAModerateEndpointDifferenceHandledByItsCrossfade() {
        val qa = evaluateVideoQa(
            action = "sleep_loop",
            expectedDurationSeconds = 6,
            durationMs = 6_020,
            width = 960,
            height = 960,
            sampleCount = 5,
            minimumBorderKeyRatio = 0.99,
            minimumFrameMargin = 0.16,
            endpointDelta = 0.11,
            matteHex = "#00FF00",
        )

        assertTrue(qa.hardPass)
        assertTrue(qa.warnings.isNotEmpty())
    }

    @Test
    fun canonicalQaRequiresRealTransparencyAndSafeMargins() {
        val accepted = evaluateCanonicalImageQa(
            width = 1024,
            height = 1536,
            transparentRatio = 0.62,
            subjectWidthRatio = 0.48,
            subjectHeightRatio = 0.61,
            minimumMarginRatio = 0.14,
        )
        val rejected = evaluateCanonicalImageQa(
            width = 1024,
            height = 1536,
            transparentRatio = 0.0,
            subjectWidthRatio = 1.0,
            subjectHeightRatio = 1.0,
            minimumMarginRatio = 0.0,
        )

        assertTrue(accepted.hardPass)
        assertTrue(!rejected.hardPass)
        assertTrue(rejected.blockingIssues.size >= 2)
    }

    @Test
    fun canonicalQaAcceptsATallHumanoidThatDoesNotTouchTheCanvas() {
        val qa = evaluateCanonicalImageQa(
            width = 1024,
            height = 1536,
            transparentRatio = 0.6495,
            subjectWidthRatio = 0.6826,
            subjectHeightRatio = 0.9017,
            minimumMarginRatio = 0.0403,
        )

        assertTrue(qa.hardPass)
    }

    @Test
    fun canonicalQaStillRejectsActualCanvasEdgeContact() {
        val qa = evaluateCanonicalImageQa(
            width = 1024,
            height = 1536,
            transparentRatio = 0.48,
            subjectWidthRatio = 0.72,
            subjectHeightRatio = 0.93,
            minimumMarginRatio = 0.0,
        )

        assertTrue(!qa.hardPass)
        assertTrue(qa.blockingIssues.any { it.contains("краю") })
    }
}
