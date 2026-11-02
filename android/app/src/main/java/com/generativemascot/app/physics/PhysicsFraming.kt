package com.generativemascot.app.physics

/** One transform shared by the neutral mesh and its authored video. Offsets
 * are fractions of the fitted source width, not the full layout bounds. */
data class PhysicsFraming(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    companion object {
        fun fit(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): PhysicsFraming {
            if (right <= left || bottom <= top) return PhysicsFraming()
            val edge = minOf(width,height).toFloat()
            // Equal visible height and a common ground line, with room for recoil.
            val scale = minOf(edge*.675f/(bottom-top),edge*.80f/(right-left)).coerceIn(.75f,1.5f)
            return PhysicsFraming(scale,
                (width*.5f-(left+right)*.5f)*scale/width,
                (edge*.3375f-(bottom-height*.5f)*scale)/width)
        }
    }
}
