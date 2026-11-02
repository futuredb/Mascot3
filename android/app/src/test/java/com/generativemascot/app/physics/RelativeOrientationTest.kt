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
    private val upright = axis(1,(Math.PI/2).toFloat())
    private fun settle(pose: RelativeOrientation, q: FloatArray, rotation: Int = 0) {
        repeat(40) { pose.update(q,it*20_000_000L,rotation) }
        assertTrue(pose.calibrated)
    }

    @Test fun startupIdentityDoesNotBecomeNeutralForAnUprightPhone() {
        val pose = RelativeOrientation()
        repeat(5) { pose.update(axis(1,0f),it*20_000_000L) }
        repeat(40) { pose.update(upright,(it+5)*20_000_000L) }
        assertTrue(pose.calibrated)
        val neutral = pose.update(upright,1_000_000_000L)
        assertEquals(0f,neutral.first,1e-5f); assertEquals(0f,neutral.second,1e-5f)
        val roll = pose.update(multiply(upright,axis(3,.15f)),1_020_000_000L)
        assertEquals(.15f,roll.first,1e-5f); assertEquals(0f,roll.second,1e-5f)
        val pitch = pose.update(multiply(upright,axis(1,-.12f)),1_040_000_000L)
        assertEquals(0f,pitch.first,1e-5f); assertEquals(-.12f,pitch.second,1e-5f)
    }

    @Test fun openingAtAnAngleDoesNotLeaveABiasWhenPhoneIsLevel() {
        val pose = RelativeOrientation()
        settle(pose,multiply(upright,axis(3,-.12f)))
        val neutral = pose.update(upright,1_000_000_000L)
        assertEquals(0f,neutral.first,1e-5f)
        val held = multiply(upright,axis(3,.08f))
        repeat(1200) { assertEquals(.08f,pose.update(held,1_020_000_000L+it*20_000_000L).first,1e-5f) }
    }

    @Test fun headingDriftCannotDisplaceTheHeroEvenWithAComfortableViewingPitch() {
        val pose=RelativeOrientation();val resting=axis(1,1.1f)
        settle(pose,resting)
        repeat(1000) { frame ->
            // A world-Z turn changes heading but not gravity in the phone.
            val drifted=multiply(axis(3,frame*.01f),resting)
            val tilt=pose.update(drifted,1_000_000_000L+frame*20_000_000L)
            assertEquals(0f,tilt.first,1e-5f);assertEquals(0f,tilt.second,1e-5f)
        }
    }

    @Test fun movingPhoneWaitsForAStableWindowAndAllDisplayRotationsUseScreenAxes() {
        val pose = RelativeOrientation()
        repeat(40) { pose.update(axis(1,it*.06f),it*20_000_000L) }
        assertFalse(pose.calibrated)
        repeat(20) { pose.update(axis(1,2.34f),(it+40)*20_000_000L) }
        assertTrue(pose.calibrated)
        for (rotation in 0..3) {
            pose.reset()
            val z=rotation*(Math.PI/2).toFloat()
            val resting=multiply(upright,axis(3,z))
            settle(pose,resting,rotation)
            val level=pose.update(resting,1_000_000_000L,rotation)
            assertEquals(0f,level.first,1e-5f);assertEquals(0f,level.second,1e-5f)
            val roll=pose.update(multiply(upright,axis(3,z+.1f)),1_020_000_000L,rotation)
            assertEquals(.1f,roll.first,1e-5f)
        }
    }

    @Test fun equivalentQuaternionSignsAndInvalidSamplesCannotCauseASpike() {
        val pose=RelativeOrientation();val q=axis(1,.8f)
        repeat(40) { pose.update(q.map { v->v*if(it%2==0) -1f else 1f }.toFloatArray(),it*20_000_000L) }
        assertTrue(pose.calibrated)
        val flipped=pose.update(q.map { -it*1.01f }.toFloatArray(),800_000_000L)
        assertEquals(0f,flipped.first,1e-5f);assertEquals(0f,flipped.second,1e-5f)
        assertEquals(0f to 0f,pose.update(floatArrayOf(Float.NaN,0f,0f,0f),820_000_000L))
        assertEquals(0f to 0f,pose.update(FloatArray(4),840_000_000L))
    }
}
