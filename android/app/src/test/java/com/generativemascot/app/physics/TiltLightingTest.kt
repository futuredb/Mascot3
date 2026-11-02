package com.generativemascot.app.physics

import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class TiltLightingTest {
    @Test fun deliberateTiltSettlesMonotonicallyWithoutFlashingOrOvershoot() {
        val light=TiltLighting();val sample=PhoneSample(tiltX=.12f)
        val target=TiltResponse.angle(sample.tiltX);var previous=0f
        repeat(60) { frame ->
            val x=light.advance(1f/60,sample).x
            assertTrue(x>=previous-1e-6f);assertTrue(x<=target+1e-6f)
            if(frame==0)assertTrue(x<target*.05f)
            if(frame==14)assertTrue(x>target*.9f)
            previous=x
        }
        assertEquals(target,previous,1e-6f)
        repeat(60) {
            val x=light.advance(1f/60,PhoneSample()).x
            assertTrue(x<=previous+1e-6f);assertTrue(x>=-1e-6f);previous=x
        }
        assertEquals(0f,previous,1e-6f)
    }

    @Test fun responseMatchesAt30_60_120And144Hz() {
        val values=listOf(30,60,120,144).map { fps ->
            val light=TiltLighting()
            repeat(fps/2){light.advance(1f/fps,PhoneSample(tiltX=.09f,tiltY=-.15f))}
            light.tilt
        }
        values.forEach { assertEquals(values[0].x,it.x,1e-6f);assertEquals(values[0].y,it.y,1e-6f) }
    }

    @Test fun smallHandTremorIsSuppressedAndTheSteadyDirectionDoesNotDrift() {
        val light=TiltLighting();val steady=TiltResponse.angle(.10f)
        var raw=0.0;var smooth=0.0
        repeat(360) { i ->
            val angle=.10f+.006f*sin(2*Math.PI*8*i/120).toFloat()
            val x=light.advance(1f/120,PhoneSample(tiltX=angle)).x
            if(i>=240){raw+=abs(TiltResponse.angle(angle)-steady);smooth+=abs(x-steady)}
        }
        assertTrue("Small sensor tremor must not shimmer across the surface",smooth<raw*.2)
    }

    @Test fun shakeAccelerationCannotFlashTheLightAndLifecycleResetClearsMomentum() {
        val clean=TiltLighting();val shaken=TiltLighting()
        repeat(120) { i ->
            clean.advance(1f/60,PhoneSample(tiltX=.1f))
            shaken.advance(1f/60,PhoneSample(tiltX=.1f,accelerationX=if(i%2==0)50f else -50f,accelerationY=40f,accelerationZ=80f))
            assertEquals(clean.tilt,shaken.tilt)
        }
        shaken.reset();assertEquals(LightTilt(),shaken.tilt)
        shaken.advance(.3f,PhoneSample(tiltX=.2f));assertEquals(LightTilt(),shaken.tilt)
        shaken.advance(Float.NaN,PhoneSample());assertEquals(LightTilt(),shaken.tilt)
        shaken.advance(1f/60,PhoneSample(tiltX=Float.NaN,tiltY=Float.POSITIVE_INFINITY))
        assertEquals(LightTilt(),shaken.tilt)
    }

    @Test fun reversalsStayContinuousAndExportTheAndroidResponseForPreview() {
        val light=TiltLighting();var previous=LightTilt();val frames=mutableListOf<List<Float>>()
        repeat(360) { i ->
            val x=when(i){in 60..119 -> .12f;in 120..179 -> .12f+.006f*sin(2*Math.PI*8*i/60).toFloat();in 180..239 -> -.12f;else -> 0f}
            val pose=light.advance(1f/60,PhoneSample(tiltX=x))
            assertTrue(abs(pose.x-previous.x)<.023f);assertTrue(abs(pose.x)<.21f)
            frames.add(listOf(i/60f,TiltResponse.angle(x),0f,pose.x,pose.y));previous=pose
        }
        System.getenv("MASCOT3_PREVIEW_OUTPUT")?.let { folder ->
            File(folder,"lighting-traces.json").writeText(frames.joinToString(",","[","]") { it.joinToString(",","[","]") })
        }
    }
}
