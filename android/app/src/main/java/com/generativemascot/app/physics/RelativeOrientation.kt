package com.generativemascot.app.physics

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Android quaternion order is [w, x, y, z]. Wait for sensor fusion to settle
 * before capturing neutral; the first event can still be the startup identity. */
internal class RelativeOrientation {
    private var reference: FloatArray? = null
    private var candidate: FloatArray? = null
    private var startedAt = -1L
    private var stableSince = -1L
    var calibrated = false; private set

    fun reset() {
        reference = null; candidate = null
        startedAt = -1L; stableSince = -1L; calibrated = false
    }

    fun update(input: FloatArray, timestamp: Long): Pair<Float, Float> {
        if (input.size != 4 || input.any { !it.isFinite() } || timestamp < 0) return 0f to 0f
        val norm = sqrt(input.sumOf { (it * it).toDouble() }).toFloat()
        if (norm < .1f || !norm.isFinite()) return 0f to 0f
        val q = FloatArray(4) { input[it] / norm }
        if (!calibrated) {
            if (startedAt < 0 || timestamp < startedAt) {
                startedAt = timestamp; stableSince = timestamp; candidate = q
            }
            val previous = candidate!!
            // About 2 degrees from the candidate: restart the settling window.
            // abs(dot) treats q and -q as the same orientation.
            val alignment = abs(q.indices.sumOf { (q[it] * previous[it]).toDouble() })
            if (alignment < .99985) { candidate = q; stableSince = timestamp }
            reference = q
            if (timestamp - startedAt >= 500_000_000L && timestamp - stableSince >= 250_000_000L) calibrated = true
            return 0f to 0f
        }
        val r = reference!!
        var w = r[0]*q[0] + r[1]*q[1] + r[2]*q[2] + r[3]*q[3]
        var x = r[0]*q[1] - r[1]*q[0] - r[2]*q[3] + r[3]*q[2]
        var y = r[0]*q[2] + r[1]*q[3] - r[2]*q[0] - r[3]*q[1]
        var z = r[0]*q[3] - r[1]*q[2] + r[2]*q[1] - r[3]*q[0]
        if (w < 0) { w = -w; x = -x; y = -y; z = -z }
        val length = sqrt(x*x + y*y + z*z)
        if (length < .00001f) return 0f to 0f
        val scale = 2f * atan2(length, w) / length
        return (y * scale).coerceIn(-.7f, .7f) to (x * scale).coerceIn(-.7f, .7f)
    }
}
