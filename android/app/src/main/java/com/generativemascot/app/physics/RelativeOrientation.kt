package com.generativemascot.app.physics

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** Use the gravity direction from Android's fused quaternion, not its heading.
 * Game-rotation heading can drift; it must never move an upright hero sideways.
 * Horizontal neutral is level with the world. Pitch neutral is the user's
 * comfortable viewing angle after the initial sensor-settling window. */
internal class RelativeOrientation {
    private var candidate: FloatArray? = null
    private var referencePitch = 0f
    private var startedAt = -1L
    private var stableSince = -1L
    private var previousRotation = -1
    var calibrated = false; private set

    fun reset() {
        candidate = null; referencePitch = 0f
        startedAt = -1L; stableSince = -1L; previousRotation = -1; calibrated = false
    }

    /** displayRotation is Android Surface.ROTATION_0 / 90 / 180 / 270 (0..3). */
    fun update(input: FloatArray, timestamp: Long, displayRotation: Int = 0): Pair<Float, Float> {
        if (input.size != 4 || input.any { !it.isFinite() } || timestamp < 0) return 0f to 0f
        val norm = sqrt(input.sumOf { (it * it).toDouble() }).toFloat()
        if (norm < .1f || !norm.isFinite()) return 0f to 0f
        if (displayRotation != previousRotation || timestamp < startedAt) {
            reset(); previousRotation = displayRotation
        }
        val w = input[0]/norm; val x = input[1]/norm; val y = input[2]/norm; val z = input[3]/norm
        // Third row of the rotation matrix: world up in device coordinates.
        val gx = 2f*(x*z-w*y); val gy = 2f*(y*z+w*x); val gz = 1f-2f*(x*x+y*y)
        val screen = when (displayRotation) {
            1 -> floatArrayOf(-gy,gx,gz)
            2 -> floatArrayOf(-gx,-gy,gz)
            3 -> floatArrayOf(gy,-gx,gz)
            else -> floatArrayOf(gx,gy,gz)
        }
        val pitch = atan2(-screen[2],screen[1])
        if (!calibrated) {
            if (startedAt < 0) { startedAt = timestamp; stableSince = timestamp; candidate = screen }
            val distance = screen.indices.sumOf { i -> val d=screen[i]-candidate!![i]; (d*d).toDouble() }
            // Restart after about two degrees of physical tilt; ignore heading.
            if (distance > .0012) { candidate = screen; stableSince = timestamp }
            referencePitch = pitch
            if (timestamp - startedAt >= 500_000_000L && timestamp - stableSince >= 250_000_000L) calibrated = true
            if (!calibrated) return 0f to 0f
        }
        val delta = pitch-referencePitch
        val relativePitch = atan2(kotlin.math.sin(delta),kotlin.math.cos(delta))
        return asin(screen[0].coerceIn(-1f,1f)).coerceIn(-.7f,.7f) to relativePitch.coerceIn(-.7f,.7f)
    }
}
