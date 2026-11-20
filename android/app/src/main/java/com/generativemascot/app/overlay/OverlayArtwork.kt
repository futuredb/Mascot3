package com.generativemascot.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.ui.detectBackgroundColor
import com.generativemascot.app.ui.keyBitmap
import com.generativemascot.app.ui.GreenMatteTuning
import com.generativemascot.app.ui.detectAdaptiveGreenMatte
import java.io.File

data class OverlayVideo(val file: File, val keyColor: Int, val tuning: GreenMatteTuning = GreenMatteTuning())

data class OverlayArtwork(var frames: List<Bitmap>, var bounds: Rect, var intervalMs: Long,
    var video: OverlayVideo? = null, var reactions: List<OverlayVideo> = emptyList(), var canvasBounds: Rect? = null) {
    fun recycle() = frames.forEach { if (!it.isRecycled) it.recycle() }
}

internal fun overlayVideoFile(store: HeroLocalStore, heroId: String): File? =
    listOf("idle", "resting", "watching", "thinking", "greeting")
        .firstNotNullOfOrNull { store.actionVideoFile(heroId, it) }

internal fun overlayReactionFiles(store: HeroLocalStore, heroId: String, idle: File): List<File> =
    listOf("joyful", "greeting", "curious", "tender", "dancing")
        .mapNotNull { store.actionVideoFile(heroId, it) }.filter { it != idle }.distinct()

/** Reads saved media on IO. Only a poster is decoded; motion uses the hardware video player. */
fun loadOverlayArtwork(context: Context, heroId: String, fast: Boolean = false): OverlayArtwork {
    require(heroId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
    val store = HeroLocalStore(context)
    val base = requireNotNull(store.baseFile(heroId)) { "Изображение героя ещё не сохранено" }
    overlayVideoFile(store, heroId)?.let { video ->
        val savedKey = store.videoMatteColor(heroId)
        val neutralUrl = store.neutralVideoFrameFile(heroId)?.toURI()?.toString()
        loadOverlayVideo(video, savedKey, neutralUrl, fast)?.let { art ->
            if (fast) return art
            return art.copy(reactions = loadOverlayReactions(context, heroId, requireNotNull(art.video)))
        }
    }
    val cached = store.cachedWidgetAnimation(heroId, "idle")
    val selected = if (!fast && cached.files.size >= 2) {
        // ≤48 × 320² × 4 bytes; preserve the duration when sampling a longer cache.
        val count = minOf(48, cached.files.size)
        List(count) { cached.files[it * cached.files.size / count] }
    } else listOf(base)
    val frames = mutableListOf<Bitmap>()
    try {
        selected.forEach { frames += decodeOverlayBitmap(it) }
        val bounds = Rect()
        frames.forEach { bitmap ->
            require(bitmap.width == frames.first().width && bitmap.height == frames.first().height)
            val visible = alphaBounds(bitmap)
            require(!visible.isEmpty) { "У героя нет видимого изображения" }
            bounds.union(visible)
        }
        return OverlayArtwork(frames, bounds, if (frames.size > 1)
            (cached.frameIntervalMs.toLong() * cached.files.size / frames.size).coerceAtLeast(33) else 100)
    } catch (error: Exception) {
        frames.forEach(Bitmap::recycle)
        // A partially prepared animation must not prevent the saved hero appearing.
        val bitmap = decodeOverlayBitmap(base)
        return OverlayArtwork(listOf(bitmap), alphaBounds(bitmap).also {
            require(!it.isEmpty) { "У героя нет видимого изображения" }
        }, 100)
    }
}

internal fun loadOverlayReactions(context: Context, heroId: String, idle: OverlayVideo): List<OverlayVideo> {
    val store = HeroLocalStore(context)
    val savedKey = store.videoMatteColor(heroId)
    val neutralUrl = store.neutralVideoFrameFile(heroId)?.toURI()?.toString()
    return overlayReactionFiles(store, heroId, idle.file).map { file ->
        val url = file.toURI().toString()
        OverlayVideo(file, savedKey ?: detectBackgroundColor(url), detectAdaptiveGreenMatte(url, neutralUrl))
    }
}

internal fun refineOverlayIdle(context: Context, heroId: String, idle: OverlayVideo): OverlayVideo {
    val neutral = HeroLocalStore(context).neutralVideoFrameFile(heroId)?.toURI()?.toString()
    return idle.copy(tuning = detectAdaptiveGreenMatte(idle.file.toURI().toString(), neutral))
}

/** Reuse the first decoded frame to estimate the key; no second video decode. */
private fun frameBackgroundColor(bitmap: Bitmap): Int {
    val samples = mutableListOf<Int>()
    for (x in listOf(.025f, .075f, .925f, .975f)) for (y in listOf(.04f, .18f, .36f, .64f, .82f, .96f))
        samples += bitmap.getPixel((bitmap.width * x).toInt().coerceIn(0, bitmap.width - 1),
            (bitmap.height * y).toInt().coerceIn(0, bitmap.height - 1))
    fun median(channel: (Int) -> Int) = samples.map(channel).sorted()[samples.size / 2]
    return android.graphics.Color.rgb(median(android.graphics.Color::red), median(android.graphics.Color::green), median(android.graphics.Color::blue))
}

private fun loadOverlayVideo(file: File, savedKey: Int?, neutralUrl: String?, fast: Boolean): OverlayArtwork? = runCatching {
    val retriever = MediaMetadataRetriever()
    val source = try {
        retriever.setDataSource(file.path)
        requireNotNull(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
    } finally { retriever.release() }
    val scaled = scaleOverlayBitmap(source)
    val key = savedKey ?: frameBackgroundColor(scaled)
    val poster = try { keyBitmap(scaled, key) } finally { scaled.recycle() }
    try {
        val bounds = alphaBounds(poster)
        require(!bounds.isEmpty)
        // Estimate neutral-pose size/head placement only. The renderer fits the
        // complete source canvas so other frames and reactions cannot be cropped here.
        bounds.inset(-(bounds.width() * .12f).toInt(), -(bounds.height() * .06f).toInt())
        require(bounds.intersect(0, 0, poster.width, poster.height))
        val tuning = if (fast) GreenMatteTuning() else detectAdaptiveGreenMatte(file.toURI().toString(), neutralUrl)
        OverlayArtwork(listOf(poster), bounds, 100, OverlayVideo(file, key, tuning))
    } catch (error: Exception) { poster.recycle(); throw error }
}.getOrNull()

private fun decodeOverlayBitmap(file: File): Bitmap {
    val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, info)
    require(info.outWidth in 1..8192 && info.outHeight in 1..8192)
    var sample = 1
    while (maxOf(info.outWidth, info.outHeight) / sample > 640) sample *= 2
    val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
        inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
    }))
    return scaleOverlayBitmap(decoded)
}

private fun scaleOverlayBitmap(decoded: Bitmap): Bitmap {
    val factor = minOf(1f, 320f / maxOf(decoded.width, decoded.height))
    if (factor == 1f) return decoded
    return Bitmap.createScaledBitmap(decoded, (decoded.width * factor).toInt().coerceAtLeast(1),
        (decoded.height * factor).toInt().coerceAtLeast(1), true).also { if (it !== decoded) decoded.recycle() }
}

internal fun alphaBounds(bitmap: Bitmap): Rect {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
    pixels.forEachIndexed { i, color ->
        if ((color ushr 24) > 12) {
            val x = i % bitmap.width; val y = i / bitmap.width
            left = minOf(left, x); right = maxOf(right, x)
            top = minOf(top, y); bottom = maxOf(bottom, y)
        }
    }
    return if (right < left) Rect() else Rect(left, top, right + 1, bottom + 1)
}
