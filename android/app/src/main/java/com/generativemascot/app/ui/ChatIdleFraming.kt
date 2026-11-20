package com.generativemascot.app.ui

/** Matches the visible character in a fitted neutral video frame to its base image. */
internal data class ChatIdleFraming(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f)

internal fun chatIdleFraming(base: HeroGrounding?, video: HeroGrounding?): ChatIdleFraming {
    if (base == null || video == null) return ChatIdleFraming()
    val viewWidth = 1f
    val viewHeight = 460f / 1024f
    fun fitted(bounds: HeroGrounding): FloatArray {
        val factor = minOf(viewWidth / bounds.width, viewHeight / bounds.height)
        val left = (viewWidth - bounds.width * factor) / 2f
        val top = (viewHeight - bounds.height * factor) / 2f
        return floatArrayOf(left + (bounds.left + bounds.right) * factor / 2f,
            top + bounds.top * factor, (bounds.bottom - bounds.top) * factor)
    }
    val a = fitted(base); val b = fitted(video)
    if (a[2] <= 0f || b[2] <= 0f) return ChatIdleFraming()
    val scale = (a[2] / b[2]).coerceIn(.5f, 3f)
    val x = a[0] - (viewWidth / 2f + (b[0] - viewWidth / 2f) * scale)
    val y = a[1] - (viewHeight / 2f + (b[1] - viewHeight / 2f) * scale)
    return ChatIdleFraming(scale, x / viewWidth, y / viewHeight)
}
