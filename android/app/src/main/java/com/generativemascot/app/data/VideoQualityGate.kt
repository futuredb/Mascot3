package com.generativemascot.app.data

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cheap, deterministic checks run after the paid provider job, before a clip
 * becomes visible in the app. Only technical defects that make the file
 * unusable are blocking. Visual heuristics are advisory because discarding an
 * already-paid, playable clip would require another paid generation.
 */
internal object VideoQualityGate {
    fun inspect(
        file: File,
        action: String,
        expectedDurationSeconds: Int,
        matteColor: Int,
    ): VideoClipQa {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull().orZero()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull().orZero()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull().orZero()
            val safeDuration = durationMs.coerceAtLeast(1L)
            val offsets = listOf(0.0, 0.25, 0.5, 0.75, 0.985)
                .map { ratio -> (safeDuration * ratio).toLong() * 1_000L }
            val frames = offsets.mapNotNull { offset ->
                retriever.getFrameAtTime(offset, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            try {
                val measurements = frames.map { measureFrame(it, matteColor) }
                val minimumBorderKeyRatio = measurements.minOfOrNull { it.borderKeyRatio } ?: 0.0
                val minimumMargin = measurements.minOfOrNull { it.minimumMargin } ?: 0.0
                val endpointDelta = if (frames.size >= 2) {
                    frameDelta(frames.first(), frames.last(), matteColor)
                } else {
                    1.0
                }
                return evaluateVideoQa(
                    action = action,
                    expectedDurationSeconds = expectedDurationSeconds,
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    sampleCount = frames.size,
                    minimumBorderKeyRatio = minimumBorderKeyRatio,
                    minimumFrameMargin = minimumMargin,
                    endpointDelta = endpointDelta,
                    matteHex = colorHex(matteColor),
                )
            } finally {
                frames.forEach { frame ->
                    if (!frame.isRecycled) frame.recycle()
                }
            }
        } finally {
            retriever.release()
        }
    }

    private data class FrameMeasurement(val borderKeyRatio: Double, val minimumMargin: Double)

    private fun measureFrame(bitmap: Bitmap, matteColor: Int): FrameMeasurement {
        val step = max(2, minOf(bitmap.width, bitmap.height) / 180)
        val border = max(step, minOf(bitmap.width, bitmap.height) / 32)
        var borderSamples = 0
        var keyedBorderSamples = 0
        val subjectXs = mutableListOf<Int>()
        val subjectYs = mutableListOf<Int>()
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val isKey = colorDistance(bitmap.getPixel(x, y), matteColor) <= 105.0
                val atBorder = x < border || y < border || x >= bitmap.width - border || y >= bitmap.height - border
                if (atBorder) {
                    borderSamples += 1
                    if (isKey) keyedBorderSamples += 1
                }
                if (!isKey) {
                    subjectXs += x
                    subjectYs += y
                }
            }
        }
        val margin = if (subjectXs.isEmpty()) {
            0.0
        } else {
            subjectXs.sort()
            subjectYs.sort()
            // Ignore isolated codec speckles while retaining real limbs, tails
            // and broad background leaks such as the black bars seen in QA.
            val trim = (subjectXs.size / 500).coerceAtMost((subjectXs.size - 1) / 2)
            val left = subjectXs[trim]
            val right = subjectXs[subjectXs.lastIndex - trim]
            val top = subjectYs[trim]
            val bottom = subjectYs[subjectYs.lastIndex - trim]
            minOf(
                left.toDouble() / bitmap.width,
                top.toDouble() / bitmap.height,
                (bitmap.width - 1 - right).toDouble() / bitmap.width,
                (bitmap.height - 1 - bottom).toDouble() / bitmap.height,
            )
        }
        return FrameMeasurement(
            borderKeyRatio = keyedBorderSamples.toDouble() / borderSamples.coerceAtLeast(1),
            minimumMargin = margin,
        )
    }

    private fun frameDelta(first: Bitmap, last: Bitmap, matteColor: Int): Double {
        val width = minOf(first.width, last.width)
        val height = minOf(first.height, last.height)
        val step = max(2, minOf(width, height) / 96)
        var total = 0.0
        var count = 0
        for (y in 0 until height step step) for (x in 0 until width step step) {
            val a = first.getPixel(x, y)
            val b = last.getPixel(x, y)
            if (colorDistance(a, matteColor) <= 105.0 && colorDistance(b, matteColor) <= 105.0) continue
            total += abs(Color.red(a) - Color.red(b))
            total += abs(Color.green(a) - Color.green(b))
            total += abs(Color.blue(a) - Color.blue(b))
            count += 3
        }
        return total / (count.coerceAtLeast(1) * 255.0)
    }

    private fun colorDistance(left: Int, right: Int): Double {
        val red = Color.red(left) - Color.red(right)
        val green = Color.green(left) - Color.green(right)
        val blue = Color.blue(left) - Color.blue(right)
        return sqrt((red * red + green * green + blue * blue).toDouble())
    }

    private fun colorHex(color: Int): String = "#%02X%02X%02X".format(
        Color.red(color), Color.green(color), Color.blue(color),
    )

    private fun Long?.orZero(): Long = this ?: 0L
    private fun Int?.orZero(): Int = this ?: 0
}
