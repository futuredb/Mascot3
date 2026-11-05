package com.generativemascot.app.data

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Cheap, deterministic checks run after the paid provider job, before a clip
 * becomes visible in the app. Only technical defects that make the file
 * unusable are blocking. For new loop-directed jobs, a clearly broken seam
 * also blocks publication; the paid original remains available in quarantine.
 */
internal object VideoQualityGate {
    fun inspect(
        file: File,
        action: String,
        expectedDurationSeconds: Int,
        matteColor: Int,
        enforceLoop: Boolean = false,
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
                val frameCount = if (Build.VERSION.SDK_INT >= 28) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                } else null
                val effectiveCount = frameCount?.takeIf { it > 3 }
                    ?: ((safeDuration / 1000.0) * 24.0).roundToInt()
                val fullSeam = if (enforceLoop) {
                    inspectSeam(retriever, safeDuration, effectiveCount, 0, effectiveCount - 1, matteColor)
                } else null
                val sleepSeam = if (enforceLoop && HeroLocalStore.normalizeVideoAction(action) == "sleeping") {
                    val start = ceil(DERIVED_SLEEP_START_MS.toDouble() * effectiveCount / safeDuration).toInt()
                    val end = ceil(DERIVED_SLEEP_END_MS.toDouble() * effectiveCount / safeDuration).toInt() - 1
                    inspectSeam(retriever, safeDuration, effectiveCount, start, end, matteColor)
                } else null
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
                    enforceLoop = enforceLoop,
                    loopSeam = fullSeam,
                    sleepLoopSeam = sleepSeam,
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

    private fun inspectSeam(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        frameCount: Int,
        firstIndex: Int,
        lastIndex: Int,
        matte: Int,
    ): VideoLoopSeam? {
        if (frameCount < 8 || firstIndex < 0 || lastIndex >= frameCount || lastIndex - firstIndex < 6) return null
        val frames = mutableMapOf<Int, Bitmap>()
        fun get(index: Int): Bitmap? {
            frames[index]?.let { return it }
            val source = if (Build.VERSION.SDK_INT >= 28) {
                runCatching { retriever.getFrameAtIndex(index) }.getOrNull()
            } else {
                val timeUs = (index.toDouble() * durationMs * 1_000.0 / frameCount).toLong()
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } ?: return null
            val scaled = try { Bitmap.createScaledBitmap(source, 192, 192, true) }
                finally { if (source.width != 192 || source.height != 192) source.recycle() }
            frames[index] = scaled
            return scaled
        }
        fun difference(a: Int, b: Int): Double? {
            val left = get(a) ?: return null
            val right = get(b) ?: return null
            return frameDelta(left, right, matte)
        }
        return try {
            val span = lastIndex - firstIndex
            val middleSteps = listOf(1, 2, 3).mapNotNull { part ->
                val i = firstIndex + span * part / 4
                difference(i, i + 1)
            }.sorted()
            if (middleSteps.size != 3) return null
            val edgeSteps = listOf(
                firstIndex to firstIndex + 1,
                firstIndex + 2 to firstIndex + 3,
                firstIndex + 5 to firstIndex + 6,
                lastIndex - 6 to lastIndex - 5,
                lastIndex - 3 to lastIndex - 2,
                lastIndex - 1 to lastIndex,
            ).mapNotNull { (a, b) -> difference(a, b) }
            if (edgeSteps.size != 6) return null
            val previous = get(lastIndex - 1) ?: return null
            val last = get(lastIndex) ?: return null
            val first = get(firstIndex) ?: return null
            val second = get(firstIndex + 1) ?: return null
            VideoLoopSeam(
                endpointDelta = difference(firstIndex, lastIndex) ?: return null,
                firstStepDelta = difference(firstIndex, firstIndex + 1) ?: return null,
                lastStepDelta = difference(lastIndex - 1, lastIndex) ?: return null,
                typicalStepDelta = middleSteps[1],
                peakEdgeStepDelta = edgeSteps.maxOrNull() ?: return null,
                motionMismatch = frameVelocityMismatch(previous, last, first, second, matte),
            )
        } catch (_: Exception) {
            null
        } finally {
            frames.values.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun frameVelocityMismatch(previous: Bitmap, last: Bitmap, first: Bitmap, next: Bitmap, matte: Int): Double {
        val width = minOf(previous.width, last.width, first.width, next.width)
        val height = minOf(previous.height, last.height, first.height, next.height)
        val step = max(2, minOf(width, height) / 96)
        var total = 0.0
        var count = 0
        for (y in 0 until height step step) for (x in 0 until width step step) {
            val p = previous.getPixel(x, y)
            val l = last.getPixel(x, y)
            val f = first.getPixel(x, y)
            val n = next.getPixel(x, y)
            if (colorDistance(p, matte) <= 105.0 && colorDistance(l, matte) <= 105.0 &&
                colorDistance(f, matte) <= 105.0 && colorDistance(n, matte) <= 105.0) continue
            total += abs((Color.red(l) - Color.red(p)) - (Color.red(n) - Color.red(f)))
            total += abs((Color.green(l) - Color.green(p)) - (Color.green(n) - Color.green(f)))
            total += abs((Color.blue(l) - Color.blue(p)) - (Color.blue(n) - Color.blue(f)))
            count += 3
        }
        return if (count < 30) 1.0 else total / (count * 255.0)
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
        return if (count < 30) 1.0 else total / (count * 255.0)
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
