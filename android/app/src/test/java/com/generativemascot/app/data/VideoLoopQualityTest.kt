package com.generativemascot.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLoopQualityTest {
    private val closed = VideoLoopSeam(
        endpointDelta = 0.003,
        firstStepDelta = 0.008,
        lastStepDelta = 0.008,
        typicalStepDelta = 0.009,
        peakEdgeStepDelta = 0.012,
        motionMismatch = 0.012,
    )

    private fun inspect(seam: VideoLoopSeam?, action: String = "idle", sleepSeam: VideoLoopSeam? = null, enforce: Boolean = true) =
        evaluateVideoQa(
            action = action, expectedDurationSeconds = 6, durationMs = 6_040,
            width = 960, height = 960, sampleCount = 5, minimumBorderKeyRatio = 0.99,
            minimumFrameMargin = 0.15, endpointDelta = 0.01, matteHex = "#00FF00",
            enforceLoop = enforce, loopSeam = seam, sleepLoopSeam = sleepSeam,
        )

    @Test fun closedMovingLoopIsAccepted() {
        assertTrue(inspect(closed).hardPass)
        assertTrue(inspect(closed, action = "sleeping", sleepSeam = closed).hardPass)
    }

    @Test fun positionJumpAndLateFrameDistortionAreRejected() {
        val jump = inspect(closed.copy(endpointDelta = 0.09))
        val spike = inspect(closed.copy(peakEdgeStepDelta = 0.12))
        assertFalse(jump.hardPass)
        assertFalse(spike.hardPass)
        assertTrue(jump.blockingIssues.any { it.contains("Начало и конец") })
        assertTrue(spike.blockingIssues.any { it.contains("скачок") })
    }

    @Test fun suddenDirectionReversalAndMissingFramesAreRejected() {
        assertFalse(inspect(closed.copy(motionMismatch = 0.09)).hardPass)
        assertFalse(inspect(null).hardPass)
    }

    @Test fun sleepMustAlsoPassTheActualMiddleSectionUsedByThePlayer() {
        val brokenSleep = inspect(closed, action = "sleeping", sleepSeam = closed.copy(endpointDelta = 0.1))
        assertFalse(brokenSleep.hardPass)
        assertTrue(brokenSleep.blockingIssues.any { it.contains("Петля сна") })
    }

    @Test fun olderInFlightClipsKeepTheirPreviousAcceptancePolicy() {
        assertTrue(inspect(null, enforce = false).hardPass)
    }

    @Test fun everyNewPromptRequiresMatchingVelocityAndNoLateReset() {
        HeroLocalStore.VIDEO_ACTIONS.forEach { action ->
            val prompt = buildCharacterAnimationPrompt(action, seed = 73)
            assertTrue(prompt.contains("Motion direction and speed immediately before and after the seam must also match"))
            assertTrue(prompt.contains("squeeze an abrupt reset"))
            validateResolvedVideoPrompt(prompt, "#00FF00")
        }
        val idle = buildCharacterAnimationPrompt("idle", seed = 73)
        assertTrue(idle.contains("one continuous slow periodic cycle"))
        assertTrue(idle.contains("same direction and speed as at the beginning"))
        assertTrue(idle.contains("No yawning, mouth opening"))
    }
}
