package com.generativemascot.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Only newly generated, QA-approved videos may opt into an exact boundary swap. */
internal fun isVerifiedGeneratedLoop(uri: Uri, sleepSection: Boolean = false): Boolean = runCatching {
    if (uri.scheme != "file") return false
    val file = File(uri.path ?: return false)
    val qa = File(file.parentFile, "${file.nameWithoutExtension}.qa.json")
    val data = JSONObject(qa.readText())
    if (!data.optBoolean("hard_pass", false)) return false
    val seam = data.optJSONObject(if (sleepSection) "sleep_loop_seam" else "loop_seam") ?: return false
    val delta = seam.optDouble("endpoint_delta", Double.NaN)
    delta.isFinite() && delta in 0.0..0.006
}.getOrDefault(false)

/** Conservative check: only use a hard surface swap for visually matching endpoints. */
internal fun loopFramesMatch(first: IntArray, last: IntArray, matte: Int): Boolean {
    if (first.isEmpty() || first.size != last.size) return false
    fun isMatte(pixel: Int): Boolean {
        val r = Color.red(pixel) - Color.red(matte)
        val g = Color.green(pixel) - Color.green(matte)
        val b = Color.blue(pixel) - Color.blue(matte)
        return r * r + g * g + b * b <= 105 * 105
    }
    var difference = 0L
    var channels = 0
    for (i in first.indices) {
        val a = first[i]
        val b = last[i]
        if (isMatte(a) && isMatte(b)) continue
        difference += abs(Color.red(a) - Color.red(b))
        difference += abs(Color.green(a) - Color.green(b))
        difference += abs(Color.blue(a) - Color.blue(b))
        channels += 3
    }
    // Empty or unreadable subject is not evidence of a closed loop.
    return channels >= 30 && difference.toDouble() / channels <= 1.5
}

internal fun hasClosedLoopSeam(uri: Uri, matte: Int): Boolean {
    if (uri.scheme != "file") return false
    val path = uri.path ?: return false
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.takeIf { it > 0 } ?: return false
        fun frame(timeUs: Long): Bitmap? = if (Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 96, 96)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { original ->
                Bitmap.createScaledBitmap(original, 96, 96, true).also { scaled ->
                    if (scaled !== original) original.recycle()
                }
            }
        }
        val first = frame(0) ?: return false
        try {
            val last = frame(durationMs * 1_000 - 1) ?: return false
            try {
                if (first.width != last.width || first.height != last.height) return false
                val a = IntArray(first.width * first.height)
                val b = IntArray(last.width * last.height)
                first.getPixels(a, 0, first.width, 0, 0, first.width, first.height)
                last.getPixels(b, 0, last.width, 0, 0, last.width, last.height)
                loopFramesMatch(a, b, matte)
            } finally { last.recycle() }
        } finally { first.recycle() }
    } catch (_: Exception) {
        false
    } finally {
        retriever.release()
    }
}
