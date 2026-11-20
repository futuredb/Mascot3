package com.generativemascot.app.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AnimationBatchPlannerTest {
    @Test fun defaultIsFourAndClampsToRemaining() {
        assertEquals(4, defaultAnimationBatchSize(17))
        assertEquals(4, defaultAnimationBatchSize(13))
        assertEquals(2, defaultAnimationBatchSize(2))
        assertEquals(0, defaultAnimationBatchSize(0))
    }

    @Test fun firstFourCoverTheBasicInteractions() {
        assertEquals(listOf("idle", "joyful", "sleeping", "dancing"), planAnimationBatch(emptySet(), emptySet(), 4))
    }

    @Test fun nextBatchNeverRegeneratesSavedClips() {
        val saved = HeroLocalStore.CORE_VIDEO_ACTIONS.toSet()
        val next = planAnimationBatch(saved, emptySet(), 4)
        assertEquals(4, next.size)
        assertTrue(next.none(saved::contains))
        assertEquals(listOf("resting", "thinking", "at_glass", "watching"), next)
        assertEquals(listOf("resting"), planAnimationBatch(saved, emptySet(), 1))
    }

    @Test fun savedProviderJobsResumeBeforeNewPaidJobs() {
        assertEquals(listOf("angry", "idle"), planAnimationBatch(emptySet(), setOf("angry"), 2))
    }

    @Test fun allMeansOnlyMissingAndNeverMoreThanTheChosenCount() {
        val saved = ANIMATION_BATCH_ORDER.dropLast(2).toSet()
        assertEquals(2, planAnimationBatch(saved, emptySet(), 17).size)
        assertEquals(1, planAnimationBatch(saved, emptySet(), 1).size)
        assertTrue(planAnimationBatch(ANIMATION_BATCH_ORDER.toSet(), emptySet(), 4).isEmpty())
        assertEquals(17, planAnimationBatch(emptySet(), emptySet(), 17).distinct().size)
    }

    @Test fun corruptOrInvalidPlansFailClosedWhileOldWorkersKeepTheirOriginalAuthorization() {
        assertTrue(runCatching { planAnimationBatch(emptySet(), emptySet(), 0) }.isFailure)
        assertTrue(runCatching { planAnimationBatch(emptySet(), emptySet(), HeroLocalStore.LIBRARY_VIDEO_ACTIONS.size + 1) }.isFailure)
        assertTrue(runCatching { validateAnimationBatch(emptyList()) }.isFailure)
        assertTrue(runCatching { validateAnimationBatch(listOf("idle", "idle")) }.isFailure)
        assertTrue(runCatching { validateAnimationBatch(listOf("unknown")) }.isFailure)
        assertEquals(17, validateAnimationBatch(null).size)
        assertFalse("welcome" in validateAnimationBatch(null))
        assertEquals(HeroLocalStore.LEGACY_LIBRARY_VIDEO_ACTIONS, validateAnimationBatch(HeroLocalStore.LEGACY_LIBRARY_VIDEO_ACTIONS))
        assertEquals(listOf("dancing"), validateAnimationBatch(listOf("dancing")))
    }

    @Test fun legacyCatalogOnlyNeedsTheTypingReactionAndDoesNotSubmitRetiredWelcome() {
        val saved = HeroLocalStore.LEGACY_LIBRARY_VIDEO_ACTIONS.toSet()
        assertEquals("typing_reaction", planAnimationBatch(saved, emptySet(), 4).first())
        assertEquals(HeroLocalStore.LIBRARY_VIDEO_ACTIONS.size, validateAnimationBatch(HeroLocalStore.LIBRARY_VIDEO_ACTIONS + "welcome").size)
        assertTrue(validateAnimationBatch(listOf("welcome")).isEmpty())
        assertEquals(listOf("idle"), validateAnimationBatch(listOf("idle", "welcome")))
    }

    @Test fun chatPackContainsAllAgreedReactionsAndResumesWithoutOrderingExtraClips() {
        val expected = setOf(
            "idle", "curious", "thinking", "chat_happy", "excited", "laughing", "shy",
            "proud", "affection", "surprised", "confused", "skeptical", "sad", "offended",
            "annoyed", "angry", "worried", "encouraging", "bored", "sleepy", "typing_reaction",
        )
        assertEquals(expected.size, chatReactionActions.size)
        assertEquals(expected, chatReactionActions.toSet())
        val ready = setOf("idle", "thinking")
        val pending = setOf("angry", "greeting")
        val batch = validateAnimationBatch(planAnimationBatch(
            ready, pending, expected.size - ready.size, eligibleActions = chatReactionActions,
        ))
        assertEquals("angry", batch.first())
        assertEquals(expected - ready, batch.toSet())
    }

    @Test fun ambiguousPaidSubmissionCannotAutomaticallyRepeatAfterWorkerRestart() {
        val directory = Files.createTempDirectory("mascot-batch-marker-test").toFile()
        try {
            claimVideoSubmission(directory, "request-1", "idle")
            assertTrue(runCatching { claimVideoSubmission(directory, "request-1", "idle") }.isFailure)
            claimVideoSubmission(directory, "request-1", "joyful")
            claimVideoSubmission(directory, "request-2", "idle")
            assertEquals(3, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }
}
