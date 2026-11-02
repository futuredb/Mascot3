package com.generativemascot.app.overlay

import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Build
import com.generativemascot.app.ui.keyBitmap
import java.io.File

/** Cached envelope of decoded poses; never crop to just the first frame. Runs on IO. */
internal fun videoEnvelope(clip: OverlayVideo, width: Int, height: Int): Rect {
    val full = Rect(0, 0, width, height)
    if (Build.VERSION.SDK_INT < 27) return full
    val cache = File(clip.file.parentFile, clip.file.name + ".overlay-bounds-v1")
    val stamp = "${clip.file.length()}:${clip.file.lastModified()}:${clip.keyColor}:$width:$height"
    runCatching {
        val lines = cache.readLines()
        if (lines.first() == stamp) {
            val n = lines[1].split(',').map(String::toInt)
            val r = Rect(n[0], n[1], n[2], n[3])
            if (!r.isEmpty && full.contains(r)) return r
        }
    }
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(clip.file.path)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return full
        val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.coerceIn(24f, 60f) ?: 30f
        if (duration !in 1..30_000) return full
        val result = Rect()
        val samples = kotlin.math.ceil(duration * fps / 1000).toInt()
        for (i in 0..samples) {
            val bitmap = retriever.getScaledFrameAtTime(minOf(duration * 1000 - 1, (i * 1_000_000.0 / fps).toLong()),
                MediaMetadataRetriever.OPTION_CLOSEST, 160, 160) ?: return full
            val keyed = try { keyBitmap(bitmap, clip.keyColor) } finally { bitmap.recycle() }
            try {
                val r = alphaBounds(keyed)
                if (!r.isEmpty) result.union(Rect(
                    kotlin.math.floor(r.left.toDouble() * width / keyed.width).toInt(),
                    kotlin.math.floor(r.top.toDouble() * height / keyed.height).toInt(),
                    kotlin.math.ceil(r.right.toDouble() * width / keyed.width).toInt(),
                    kotlin.math.ceil(r.bottom.toDouble() * height / keyed.height).toInt()))
            } finally { keyed.recycle() }
        }
        if (result.isEmpty) return full
        // Guard downsampling, codec timestamps, hair and antialiased edges.
        result.inset(-maxOf(4, width / 25), -maxOf(4, height / 25))
        result.intersect(full)
        runCatching { cache.writeText("$stamp\n${result.left},${result.top},${result.right},${result.bottom}") }
        result
    } catch (_: Exception) { full } finally { retriever.release() }
}
