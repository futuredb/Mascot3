package com.generativemascot.app.physics

import kotlin.math.exp

/** One immutable light direction shared by the UI and both rendering threads. */
data class LightTilt(val x: Float = 0f, val y: Float = 0f)
class TiltLighting {
    @Volatile var tilt = LightTilt()
        private set
    private var velocityX = 0f
    private var velocityY = 0f

    fun reset() { tilt = LightTilt(); velocityX = 0f; velocityY = 0f }

    /** Exact critically damped response: no bounce, frame-rate dependent gain,
     * or shake impulse. Shares the existing tilt sensitivity with the body. */
    fun advance(seconds: Float, sample: PhoneSample): LightTilt {
        if(!seconds.isFinite() || seconds<=0f)return tilt
        if(seconds>.25f){reset();return tilt}
        val targetX=TiltResponse.angle(sample.tiltX+sample.gravityX*.7f)
        val targetY=TiltResponse.angle(sample.tiltY+sample.gravityY*.7f)
        val omega=18f
        val decay=exp(-omega*seconds)
        val dx=tilt.x-targetX;val dy=tilt.y-targetY
        val tx=velocityX+omega*dx;val ty=velocityY+omega*dy
        tilt=LightTilt(targetX+(dx+tx*seconds)*decay,targetY+(dy+ty*seconds)*decay)
        velocityX=(velocityX-omega*tx*seconds)*decay
        velocityY=(velocityY-omega*ty*seconds)*decay
        return tilt
    }
}
