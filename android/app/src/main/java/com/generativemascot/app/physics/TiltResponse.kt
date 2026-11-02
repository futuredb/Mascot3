package com.generativemascot.app.physics

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

/** One phone-to-view mapping for the relief, fallback artwork and floor.
 * A relaxed hold stays quiet; deliberate tilt approaches the limit gradually. */
internal object TiltResponse {
    private const val DEAD_ZONE = .022f
    private const val GAIN = .9f
    fun angle(input: Float, limit: Float = .20943952f): Float {
        if (!input.isFinite()) return 0f
        val magnitude = (abs(input) - DEAD_ZONE).coerceAtLeast(0f)
        return sign(input) * limit * tanh(magnitude * GAIN / limit)
    }
}
