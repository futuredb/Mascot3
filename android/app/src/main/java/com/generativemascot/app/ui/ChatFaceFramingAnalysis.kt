package com.generativemascot.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import java.net.URI

private data class ChatHeadCrop(val x: Float, val y: Float, val scale: Float)

internal fun estimateChatFaceFraming(still: File?, neutralUrl: String?, idleUrl: String?, fallback: ChatFaceFraming): ChatFaceFraming {
    fun localFile(url: String?): File? = url?.let { runCatching { File(URI(it.substringBefore('?'))) }.getOrNull() }
    val stillCrop = still?.let { BitmapFactory.decodeFile(it.path)?.let(::measureHead) }
    val frame = localFile(neutralUrl)?.let { BitmapFactory.decodeFile(it.path) }
        ?: localFile(idleUrl)?.let { file ->
            runCatching {
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.path)
                    if (Build.VERSION.SDK_INT >= 27) reader.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 192)
                    else reader.getFrameAtTime(0)
                } finally { reader.release() }
            }.getOrNull()
        }
    val videoCrop = frame?.let(::measureHead)
    return fallback.copy(videoCenter = videoCrop?.y ?: fallback.videoCenter,
        videoScale = videoCrop?.scale ?: fallback.videoScale, videoX = videoCrop?.x ?: fallback.videoX,
        stillCenter = stillCrop?.y ?: fallback.stillCenter, stillScale = stillCrop?.scale ?: fallback.stillScale,
        stillX = stillCrop?.x ?: fallback.stillX)
}

private fun measureHead(source: Bitmap): ChatHeadCrop? {
    val longest = maxOf(source.width, source.height).toFloat()
    val w = (128 * source.width / longest).toInt().coerceAtLeast(1)
    val h = (128 * source.height / longest).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createScaledBitmap(source, w, h, true)
    if (bitmap !== source) source.recycle()
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    bitmap.recycle()

    fun green(pixel: Int): Boolean {
        val red = pixel shr 16 and 255
        val g = pixel shr 8 and 255
        val blue = pixel and 255
        return g > maxOf(red, blue) + 50
    }
    val keyed = listOf(0, w - 1, w * (h - 1), w * h - 1).count { green(pixels[it]) } >= 3
    val transparent = pixels.any { (it ushr 24) < 80 }
    if (!keyed && !transparent) return null
    val rows = pixels.indices.filter { i -> (pixels[i] ushr 24) >= 100 && (!keyed || !green(pixels[i])) }
        .groupBy { it / w }.filterValues { it.size >= 3 }
    val top = rows.keys.minOrNull() ?: return null
    val bottom = rows.keys.maxOrNull() ?: return null
    if (bottom - top < 10) return null
    val headEnd = top + ((bottom - top) * .43f).toInt()
    val head = rows.filterKeys { it <= headEnd }.values.flatten()
    val left = head.minOf { it % w }
    val right = head.maxOf { it % w }
    val centerX = ((left + right) / 2f - w / 2f) / 128f + .5f
    val centerY = ((top + headEnd) / 2f - h / 2f) / 128f + .5f
    val scale = minOf(128f * .70f / (right - left + 1), 128f * .61f / (headEnd - top + 1))
        .coerceIn(1.35f, 4.2f)
    return ChatHeadCrop(centerX, centerY, scale)
}
