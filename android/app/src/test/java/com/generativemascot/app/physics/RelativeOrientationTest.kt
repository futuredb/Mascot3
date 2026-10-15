package com.generativemascot.app.physics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RelativeOrientationTest {
    private fun axis(axis: Int, angle: Float) = FloatArray(4).apply {
        this[0] = cos(angle / 2); this[axis] = sin(angle / 2)
    }
    private fun multiply(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[0]*b[0]-a[1]*b[1]-a[2]*b[2]-a[3]*b[3],
        a[0]*b[1]+a[1]*b[0]+a[2]*b[3]-a[3]*b[2],
        a[0]*b[2]-a[1]*b[3]+a[2]*b[0]+a[3]*b[1],
        a[0]*b[3]+a[1]*b[2]-a[2]*b[1]+a[3]*b[0],
    )

    @Test fun startupIdentityDoesNotBecomeNeutralForAnUprightPhone() {
        val pose = RelativeOrientation()
        val upright = axis(1, 1.5f)
        repeat(5) { pose.update(axis(1, 0f), it * 20_000_000L) }
        repeat(40) { pose.update(upright, (it + 5) * 20_000_000L) }
        assertTrue(pose.calibrated)
        val neutral = pose.update(upright, 1_000_000_000L)
        assertEquals(0f, neutral.first, 1e-5f); assertEquals(0f, neutral.second, 1e-5f)
        val tilted = pose.update(multiply(upright, axis(2, .15f)), 1_020_000_000L)
        assertEquals(.15f, tilted.first, 1e-5f); assertEquals(0f, tilted.second, 1e-5f)
        val other = pose.update(multiply(upright, axis(1, -.12f)), 1_040_000_000L)
        assertEquals(0f, other.first, 1e-5f); assertEquals(-.12f, other.second, 1e-5f)
    }

    @Test fun movingPhoneWaitsForAStableWindowAndResetUsesNewOrientation() {
        val pose = RelativeOrientation()
        repeat(40) { pose.update(axis(2, it * .06f), it * 20_000_000L) }
        assertFalse(pose.calibrated)
        repeat(20) { pose.update(axis(2, 2.34f), (it + 40) * 20_000_000L) }
        assertTrue(pose.calibrated)
        pose.reset()
        assertFalse(pose.calibrated)
        val landscape = axis(3, 1.57f)
        repeat(35) { pose.update(landscape, (it + 60) * 20_000_000L) }
        assertTrue(pose.calibrated)
        assertEquals(0f, pose.update(landscape, 2_000_000_000L).first, 1e-5f)
    }

    @Test fun EquivalentQuaternionSignsAndInvalidSamplesCannotCauseASpike() {
        val pose = RelativeOrientation()
        val q = axis(1, .8f)
        repeat(35) { pose.update(q.map { v -> v * if (it % 2 == 0) -1f else 1f }.toFloatArray(), it * 20_000_000L) }
        assertTrue(pose.calibrated)
        val flipped = pose.update(q.map { -it * 1.01f }.toFloatArray(), 800_000_000L)
        assertEquals(0f, flipped.first, 1e-5f); assertEquals(0f, flipped.second, 1e-5f)
        assertEquals(0f to 0f, pose.update(floatArrayOf(Float.NaN, 0f, 0f, 0f), 820_000_000L))
        assertEquals(0f to 0f, pose.update(FloatArray(4), 840_000_000L))
    }
}
