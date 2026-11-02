package com.generativemascot.app.physics

data class SpaceLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val alpha: Float)

/** A separate floor plane, in normalized scene coordinates. It never inherits
 * the character's lean or shake, so it remains a visual reference for motion. */
object HeroSpace {
    fun floor(pose: PhonePose, groundY: Float = .85f): List<SpaceLine> {
        val horizon = groundY - .42f + pose.y * .025f
        fun project(x: Float, z: Float): Pair<Float, Float> {
            val perspective = 1f / (1f + z * .65f)
            return (.5f + (x - pose.x * .16f) * .30f * perspective) to
                (horizon + (groundY + .09f - horizon) * perspective)
        }
        val depths = listOf(0f, .5f, 1.15f, 2f, 3.2f, 5f, 8f, 13f)
        return buildList {
            depths.zipWithNext().forEachIndexed { index, (near, far) ->
                val alpha = .26f * (1f - index / 8f)
                for (column in -5..5) {
                    val a = project(column.toFloat(), near); val b = project(column.toFloat(), far)
                    add(SpaceLine(a.first, a.second, b.first, b.second, alpha))
                }
            }
            depths.forEachIndexed { index, depth ->
                val a = project(-5f, depth); val b = project(5f, depth)
                add(SpaceLine(a.first, a.second, b.first, b.second, .26f * (1f - index / 8f)))
            }
        }
    }
}
