package com.generativemascot.app.data

private val zeroFrameName = Regex("frame_0+\\.png", RegexOption.IGNORE_CASE)

/**
 * Frame zero is a reference image, not part of the animated cycle. Filter by
 * source index rather than dropping the first remaining item, so a sequence
 * passed through multiple readers never loses frame one as well. A single
 * image is a static preview and must stay visible.
 */
internal fun <T> animationFramesWithoutZero(frames: List<T>, sourcePath: (T) -> String): List<T> {
    if (frames.size <= 1) return frames
    return frames.filterNot { frame ->
        val name = sourcePath(frame).substringBefore('?').substringBefore('#')
            .substringAfterLast('/').substringAfterLast('\\')
        zeroFrameName.matches(name)
    }
}
