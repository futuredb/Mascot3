package com.generativemascot.app.physics

/** Offsets are fractions of the fitted source width. */
data class PhysicsFraming(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    companion object {
        const val GROUND_OFFSET = .3375f
        fun fit(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): PhysicsFraming {
            if (right <= left || bottom <= top) return PhysicsFraming()
            val edge = minOf(width,height).toFloat()
            // Equal visible height and a common ground line, with room for recoil.
            // Some generated images have much larger transparent margins than
            // the original heroes. Permit those silhouettes to reach the same
            // target size, while still limiting wide characters to the frame.
            val scale = minOf(edge*.675f/(bottom-top),edge*.80f/(right-left)).coerceIn(.75f,2.5f)
            return PhysicsFraming(scale,
                (width*.5f-(left+right)*.5f)*scale/width,
                (edge*GROUND_OFFSET-(bottom-height*.5f)*scale)/width)
        }
    }
}

/** Bounds in the source image, measured independently for artwork and video. */
data class SubjectBounds(val width: Int, val height: Int, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun fit(viewWidth: Float, viewHeight: Float): DisplayFraming {
        if (width <= 0 || height <= 0 || right <= left || bottom <= top || viewWidth <= 0f || viewHeight <= 0f) return DisplayFraming()
        val fitted = minOf(viewWidth / width, viewHeight / height)
        val edge = minOf(viewWidth, viewHeight)
        val scale = minOf(edge * .675f / ((bottom - top) * fitted), edge * .80f / ((right - left) * fitted)).coerceIn(.4f, 4f)
        return DisplayFraming(scale,
            (width * .5f - (left + right) * .5f) * fitted * scale,
            edge * PhysicsFraming.GROUND_OFFSET - (bottom - height * .5f) * fitted * scale)
    }
}

data class DisplayFraming(val scale: Float = 1f, val translationX: Float = 0f, val translationY: Float = 0f)
