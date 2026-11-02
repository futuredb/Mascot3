package com.generativemascot.app.physics

import kotlin.math.abs
import kotlin.math.sqrt

data class PhoneSample(
    val tiltX: Float = 0f, val tiltY: Float = 0f,
    val accelerationX: Float = 0f, val accelerationY: Float = 0f,
    val gravityX: Float = 0f, val gravityY: Float = 0f,
    val accelerationZ: Float = 0f, val accelerationTimestamp: Long = 0,
)

data class PhonePose(val x: Float = 0f, val y: Float = 0f, val wobble: Float = 0f, val bounce: Float = 0f) {
    fun artwork(preparedPhysics: Boolean = false) = ArtworkMotion(
        translationX = (if (preparedPhysics) 0f else x * 12f) + wobble * .5f,
        translationY = (if (preparedPhysics) 0f else y * 8f) - bounce * 12f,
        rotationX = if (preparedPhysics) 0f else -y * 12f,
        rotationY = if (preparedPhysics) 0f else x * 16f,
        rotationZ = (if (preparedPhysics) 0f else x * 2f) + wobble * .4f,
        scaleX = 1f + bounce * .015f,
        scaleY = 1f - bounce * .015f,
    )
}

/** Translation is in dp, rotation in degrees. Shared with the Codex preview. */
data class ArtworkMotion(
    val translationX: Float, val translationY: Float,
    val rotationX: Float, val rotationY: Float, val rotationZ: Float,
    val scaleX: Float, val scaleY: Float,
)

/** Local movement for existing artwork. No asset preparation or network calls. */
class PhoneInteractionMotion {
    var pose = PhonePose(); private set
    private var vx = 0f
    private var vy = 0f
    private var wobbleVelocity = 0f
    private var bounceVelocity = 0f
    private var lastSample = 0L
    private var firstPeak = 0L
    private var peakVector = floatArrayOf(0f, 0f, 0f)
    private var lastShake = 0L
    private var armed = true

    fun reset() {
        pose = PhonePose(); vx = 0f; vy = 0f; wobbleVelocity = 0f; bounceVelocity = 0f
        lastSample = 0L; firstPeak = 0L; lastShake = 0L; armed = true
    }

    /** Two opposite acceleration peaks distinguish shaking from one bump or a tilt. */
    fun observe(sample: PhoneSample): Boolean {
        val now = sample.accelerationTimestamp
        if (now <= lastSample) return false
        lastSample = now
        val vector = floatArrayOf(sample.accelerationX, sample.accelerationY, sample.accelerationZ)
        if (vector.any { !it.isFinite() }) return false
        val magnitude = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (magnitude < 2.5f) armed = true
        if (lastShake > 0 && now - lastShake < 1_200_000_000L) return false
        if (firstPeak > 0 && now - firstPeak > 650_000_000L) firstPeak = 0L
        if (magnitude < 4.5f) return false
        val peakMagnitude = sqrt(peakVector.sumOf { (it * it).toDouble() })
        val opposite = vector.indices.sumOf { (vector[it] * peakVector[it]).toDouble() } < -.45 * magnitude * peakMagnitude
        // A fast reversal may cross zero between display frames. Do not require
        // an observed quiet frame between two opposing peaks.
        if (firstPeak > 0 && now - firstPeak in 70_000_000L..650_000_000L && opposite) {
            firstPeak = 0L; lastShake = now; armed = false
            val direction = vector.maxBy { abs(it) }
            wobbleVelocity = if (direction >= 0f) 145f else -145f
            bounceVelocity = 20f
            return true
        }
        if (!armed) return false
        armed = false
        firstPeak = now; peakVector = vector
        return false
    }

    fun advance(seconds: Float, sample: PhoneSample): PhonePose {
        if (!seconds.isFinite() || seconds <= 0f) return pose
        if (seconds > .25f) { reset(); return pose }
        fun target(value: Float): Float = TiltResponse.angle(value) / .20943952f
        val tx = target(sample.tiltX + sample.gravityX * .7f)
        val ty = target(sample.tiltY + sample.gravityY * .7f)
        var remaining = seconds.coerceAtMost(.05f)
        var x = pose.x; var y = pose.y; var wobble = pose.wobble; var bounce = pose.bounce
        while (remaining > 0f) {
            val dt = minOf(remaining, 1f / 120f); remaining -= dt
            vx += (360f * (tx - x) - 32f * vx) * dt
            vy += (360f * (ty - y) - 32f * vy) * dt
            x = (x + vx * dt).coerceIn(-1f, 1f); y = (y + vy * dt).coerceIn(-1f, 1f)
            wobbleVelocity += (-150f * wobble - 9f * wobbleVelocity) * dt
            wobble = (wobble + wobbleVelocity * dt).coerceIn(-9f, 9f)
            bounceVelocity += (-170f * bounce - 11f * bounceVelocity) * dt
            bounce = (bounce + bounceVelocity * dt).coerceIn(-.35f, 1f)
        }
        pose = PhonePose(x, y, wobble, bounce)
        return pose
    }
}
