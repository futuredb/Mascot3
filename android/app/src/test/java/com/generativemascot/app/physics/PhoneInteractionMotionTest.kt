package com.generativemascot.app.physics

import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PhoneInteractionMotionTest {
    private fun sample(ms: Long, acceleration: Float = 0f) =
        PhoneSample(accelerationX = acceleration, accelerationTimestamp = ms * 1_000_000L)

    @Test fun oneBumpAndStationarySensorNoiseDoNotTriggerAnAction() {
        val movement = PhoneInteractionMotion()
        assertFalse(movement.observe(sample(1000, 10f)))
        repeat(500) { assertFalse(movement.observe(sample(1020 + it * 10L, .3f))) }
        assertEquals(0f, movement.pose.wobble, 0f)
    }

    @Test fun oppositePeaksTriggerOnceAndRepeatedSamplesCannotRetrigger() {
        val movement = PhoneInteractionMotion()
        assertFalse(movement.observe(sample(1000, 9f)))
        assertFalse(movement.observe(sample(1080)))
        assertTrue(movement.observe(sample(1180, -9f)))
        repeat(20) { assertFalse(movement.observe(sample(1180, -9f))) }
        assertFalse(movement.observe(sample(1300)))
        assertFalse(movement.observe(sample(1400, 10f)))
        assertFalse(movement.observe(sample(1500)))
        assertFalse(movement.observe(sample(1600, -10f)))
    }

    @Test fun peaksTooFarApartAndSameDirectionAreNotAShake() {
        val movement = PhoneInteractionMotion()
        movement.observe(sample(1000, 10f)); movement.observe(sample(1100))
        assertFalse(movement.observe(sample(1200, 10f)))
        movement.observe(sample(1300))
        assertFalse(movement.observe(sample(2100, -10f)))
    }

    @Test fun moderateReversalWorksWithoutAQuietSampleAndCanRepeatAfterCooldown() {
        val movement = PhoneInteractionMotion()
        assertFalse(movement.observe(sample(1000, 5f)))
        assertTrue(movement.observe(sample(1150, -5f)))
        assertFalse(movement.observe(sample(1250, 5f)))
        assertFalse(movement.observe(sample(1350, -5f)))
        movement.observe(sample(2300))
        assertFalse(movement.observe(sample(2400, 5f)))
        assertTrue(movement.observe(sample(2550, -5f)))
    }

    @Test fun verticalAndDepthShakesAlsoReactButNoiseAndPerpendicularBumpsDoNot() {
        for (axis in 0..2) {
            val movement = PhoneInteractionMotion()
            fun axisSample(ms: Long, force: Float) = PhoneSample(
                accelerationX = if (axis == 0) force else 0f,
                accelerationY = if (axis == 1) force else 0f,
                accelerationZ = if (axis == 2) force else 0f,
                accelerationTimestamp = ms * 1_000_000L,
            )
            repeat(20) { assertFalse(movement.observe(axisSample(1000 + it * 100L, if (it % 2 == 0) 2f else -2f))) }
            assertFalse(movement.observe(axisSample(4000, 5f)))
            assertTrue(movement.observe(axisSample(4150, -5f)))
        }
        val movement = PhoneInteractionMotion()
        movement.observe(sample(1000, 6f))
        assertFalse(movement.observe(PhoneSample(accelerationY = 6f, accelerationTimestamp = 1_150_000_000L)))
    }

    @Test fun shakeHasVisibleRecoilAndHopThenSettlesAtEveryFrameRate() {
        for (fps in listOf(30, 60, 120)) {
            val movement = PhoneInteractionMotion()
            movement.observe(sample(1000, 5f)); movement.observe(sample(1150, -5f))
            var peakWobble = 0f; var peakHop = 0f
            repeat(fps * 3) {
                val pose = movement.advance(1f / fps, PhoneSample())
                peakWobble = maxOf(peakWobble, abs(pose.wobble))
                peakHop = maxOf(peakHop, -pose.artwork().translationY)
                assertTrue(abs(pose.wobble) <= 9f)
                assertTrue(pose.bounce in -.35f..1f)
                // Shake feedback survives switching from a prepared pack to a video.
                assertEquals(pose.artwork().rotationZ, pose.artwork(true).rotationZ, 0f)
            }
            assertTrue("Recoil must be clearly visible at $fps fps", peakWobble > 6f)
            assertTrue("Shake remains visible without a large jump at $fps fps", peakHop in 8f..13f)
            assertTrue(abs(movement.pose.wobble) < .001f && abs(movement.pose.bounce) < .001f)
            movement.reset()
            assertEquals(PhonePose(), movement.pose)
        }
    }

    @Test fun gentleTiltIsVisibleBoundedAndReturnsToRest() {
        val movement = PhoneInteractionMotion()
        repeat(120) { movement.advance(1f / 60f, PhoneSample(tiltX = .06f)) }
        assertTrue("Small tilts use the relaxed part of the range", movement.pose.x in .14f.. .20f)
        repeat(120) { movement.advance(1f / 60f, PhoneSample(tiltX = 100f, tiltY = -100f)) }
        assertTrue(abs(movement.pose.x) <= 1f && abs(movement.pose.y) <= 1f)
        repeat(240) { movement.advance(1f / 60f, PhoneSample()) }
        assertTrue(abs(movement.pose.x) < .001f && abs(movement.pose.y) < .001f)
        movement.advance(1f, PhoneSample(tiltX = .1f))
        assertEquals(PhonePose(), movement.pose)
    }

    @Test fun preparedArtworkDoesNotApplyTiltTwice() {
        val pose=PhonePose(x=.7f,y=-.5f)
        val prepared=pose.artwork(true)
        assertEquals(0f,prepared.translationX,0f);assertEquals(0f,prepared.translationY,0f)
        assertEquals(0f,prepared.rotationX,0f);assertEquals(0f,prepared.rotationY,0f);assertEquals(0f,prepared.rotationZ,0f)
        assertTrue(pose.artwork(false).translationX>0f)
        assertTrue(PhonePose(wobble=5f,bounce=.8f).artwork(true).rotationZ>0f)
    }

    @Test fun previewTracesUseTheProductionMotionSolver() {
        val folder = System.getenv("MASCOT3_PREVIEW_OUTPUT")?.let(::File) ?: return
        folder.mkdirs()
        val traces = listOf("left", "right", "shake", "neutral").associateWith { scenario ->
            val movement = PhoneInteractionMotion()
            (0..240).map { frame ->
                val milliseconds = 1000 + frame * 1000L / 60
                val sample = when {
                    scenario == "shake" && frame == 6 -> sample(milliseconds, 10f)
                    scenario == "shake" && frame == 18 -> sample(milliseconds, -10f)
                    scenario == "left" && frame < 120 -> PhoneSample(tiltX = -.06f)
                    scenario == "right" && frame < 120 -> PhoneSample(tiltX = .06f, tiltY = .02f)
                    else -> sample(milliseconds)
                }
                val shaken = movement.observe(sample)
                val pose = movement.advance(1f / 60f, sample)
                val visual = pose.artwork()
                val transform = listOf(visual.translationX, visual.translationY, visual.rotationX, visual.rotationY,
                    visual.rotationZ, visual.scaleX, visual.scaleY).joinToString(",", "[", "]")
                "[${pose.x},${pose.y},${pose.wobble},$shaken,${pose.bounce},$transform]"
            }.joinToString(",", "[", "]")
        }
        File(folder, "motion-traces.json").writeText(traces.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" })
    }
}
