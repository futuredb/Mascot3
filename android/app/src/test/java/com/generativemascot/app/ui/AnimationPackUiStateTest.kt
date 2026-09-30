package com.generativemascot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationPackUiStateTest {
    @Test fun selectedFourDoNotQueueTheOtherThirteen() {
        val progress = animationPackProgress(emptyMap())
        val batch = listOf("idle", "joyful", "sleeping", "dancing")
        val statuses = ANIMATION_STATE_KEYS.map { progress.cardStatus(it, true, false, false, true, batch) }
        assertEquals(1, statuses.count { it == AnimationCardStatus.WORKING })
        assertEquals(3, statuses.count { it == AnimationCardStatus.QUEUED })
        assertEquals(13, statuses.count { it == AnimationCardStatus.NOT_STARTED })
        assertEquals("joyful", animationPackProgress(mapOf("idle" to "idle.mp4")).activeAction(true, true, batch))
    }

    @Test fun completedPartialBatchHasNoActiveOrQueuedJobs() {
        val batch = listOf("idle", "joyful", "sleeping", "dancing")
        val progress = animationPackProgress(batch.associateWith { "$it.mp4" })
        assertNull(progress.activeAction(true, true, batch))
        assertEquals(4, progress.readyCount)
        assertEquals(13, progress.remainingCount)
        assertEquals(AnimationCardStatus.NOT_STARTED, progress.cardStatus("angry", false, false, false, true, batch))
    }
    @Test
    fun freshBatchHasExactlyOneWorkingActionAndSixteenQueued() {
        val progress = animationPackProgress(emptyMap())
        val statuses = ANIMATION_STATE_KEYS.map { progress.cardStatus(it, true, false, false, true) }

        assertEquals(0, progress.readyCount)
        assertEquals(17, progress.remainingCount)
        assertEquals("idle", progress.activeAction(true, true))
        assertEquals(1, statuses.count { it == AnimationCardStatus.WORKING })
        assertEquals(16, statuses.count { it == AnimationCardStatus.QUEUED })
    }

    @Test
    fun savedVideosBecomeReadyWhileTheNextMissingActionWorks() {
        val videos = ANIMATION_STATE_KEYS.take(4).associateWith { "$it.mp4" }
        val progress = animationPackProgress(videos)

        assertEquals(4, progress.readyCount)
        assertEquals(13, progress.remainingCount)
        assertEquals("at_glass", progress.activeAction(true, true))
        assertEquals(AnimationCardStatus.READY, progress.cardStatus("sleeping", true, false, false, true))
        assertEquals(AnimationCardStatus.QUEUED, progress.cardStatus("dancing", true, false, false, true))
    }

    @Test
    fun resumeFindsTheFirstMissingActionRatherThanUsingTheReadyCountAsAnIndex() {
        val progress = animationPackProgress(mapOf("joyful" to "joyful.mp4", "dancing" to "dancing.mp4"))

        assertEquals("idle", progress.activeAction(true, true))
        assertEquals(15, progress.remainingCount)
    }

    @Test
    fun stoppedBatchDoesNotPretendAnAnimationIsStillGenerating() {
        val progress = animationPackProgress(emptyMap())

        assertNull(progress.activeAction(false, true))
        assertEquals(AnimationCardStatus.PAUSED, progress.cardStatus("idle", false, false, true, true))
    }

    @Test
    fun serverBatchDoesNotInventWhichParallelJobIsCurrentlyWorking() {
        val progress = animationPackProgress(emptyMap())

        assertNull(progress.activeAction(true, false))
        assertEquals(AnimationCardStatus.PROCESSING, progress.cardStatus("idle", true, false, false, false))
    }

    @Test
    fun fullCatalogCompletesWithoutCountingSleepDerivativeOrUnknownFiles() {
        val videos = ANIMATION_STATE_KEYS.associateWith { "$it.mp4" } +
            mapOf("sleep_loop" to "sleep_loop.mp4", "unknown" to "extra.mp4")
        val progress = animationPackProgress(videos)

        assertEquals(17, progress.readyCount)
        assertEquals(0, progress.remainingCount)
        assertNull(progress.activeAction(true, true))
    }

    @Test
    fun staticFallbacksAndLegacyPerformanceDoNotInflateSavedVideoProgress() {
        val frames = ANIMATION_STATE_KEYS.associateWith { listOf("base.png") }

        assertTrue(animationPlayableActions(emptyMap(), frames, false).isEmpty())
        assertEquals(LEGACY_PERFORMANCE_ACTIONS, animationPlayableActions(emptyMap(), frames, true))
        assertEquals(0, animationPackProgress(emptyMap()).readyCount)
        assertFalse("angry" in animationPlayableActions(emptyMap(), frames, true))
    }

    @Test
    fun realLegacyAnimationFramesRemainPlayableWithoutPretendingTheirVideoJobIsSaved() {
        val frames = mapOf("joyful" to listOf("frame_1.png", "frame_2.png"))

        assertEquals(setOf("joyful"), animationPlayableActions(emptyMap(), frames, false))
        assertEquals(0, animationPackProgress(emptyMap()).readyCount)
    }
}
