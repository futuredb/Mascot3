package com.generativemascot.app.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsFramingTest {
    @Test fun smallSilhouetteReachesSameHeightAsLargerHero() {
        val large = PhysicsFraming.fit(1000, 1000, 350, 200, 650, 800)
        val small = PhysicsFraming.fit(1000, 1000, 425, 350, 575, 650)
        assertEquals(600 * large.scale, 300 * small.scale, 0.01f)
    }

    @Test fun wideSilhouetteFitsWithoutCropping() {
        val frame = PhysicsFraming.fit(1000, 1000, 100, 350, 900, 650)
        assertTrue(800 * frame.scale <= 800.01f)
    }

    @Test fun croppedSquareVideoMatchesPortraitDepthArtwork() {
        // Real proportions from a saved character and its generated video reference.
        val artwork = SubjectBounds(1024, 1536, 273, 121, 778, 1440)
        val video = SubjectBounds(720, 720, 286, 167, 434, 554)
        for ((viewWidth, viewHeight) in listOf(342f to 396f, 290f to 250f, 400f to 400f)) {
            fun measurements(subject: SubjectBounds): Triple<Float, Float, Float> {
                val fit = minOf(viewWidth / subject.width, viewHeight / subject.height)
                val frame = subject.fit(viewWidth, viewHeight)
                val height = (subject.bottom - subject.top) * fit * frame.scale
                val center = viewWidth / 2f + ((subject.left + subject.right) / 2f - subject.width / 2f) * fit * frame.scale + frame.translationX
                val feet = viewHeight / 2f + (subject.bottom - subject.height / 2f) * fit * frame.scale + frame.translationY
                return Triple(height, center, feet)
            }
            val a = measurements(artwork)
            val b = measurements(video)
            assertEquals(a.first, b.first, .01f)
            assertEquals(a.second, b.second, .01f)
            assertEquals(a.third, b.third, .01f)
            assertEquals(viewHeight / 2f + minOf(viewWidth, viewHeight) * PhysicsFraming.GROUND_OFFSET, a.third, .01f)
        }
    }
}
