package com.generativemascot.app.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import java.io.File

/** Optional correction for clips where the model shaded a green backdrop. */
data class GreenMatteTuning(val low: Float = .035f, val high: Float = .18f, val adaptive: Boolean = false)

internal fun chooseGreenMatteTuning(border: List<Int>, subject: List<Int>): GreenMatteTuning {
    if (border.size < 36 || subject.size < 50) return GreenMatteTuning()
    fun greenLike(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return g > 40 && b < g * .72f && (g - maxOf(r, b)) / 255f > .035f
    }
    // Green/yellow-green material can be indistinguishable from a green screen.
    // Do not apply the stronger key if the canonical character contains it.
    if (subject.count(::greenLike).toFloat() / subject.size > .005f) return GreenMatteTuning()
    if (border.count(::greenLike).toFloat() / border.size < .90f) return GreenMatteTuning()
    val dominance = border.map { (Color.green(it) - maxOf(Color.red(it), Color.blue(it))) / 255f }.sorted()
    val tenth = dominance[(dominance.size * .10f).toInt().coerceAtMost(dominance.lastIndex)]
    if (tenth < .11f) return GreenMatteTuning()
    // A clean, bright green plate still produces low-saturation green edge
    // mixtures after video compression. When the canonical subject contains
    // no green material, use a tighter chroma ramp for those edge pixels too.
    if (tenth >= .23f) return GreenMatteTuning(adaptive = true)
    return GreenMatteTuning(low = .015f, high = (tenth - .025f).coerceIn(.075f, .105f), adaptive = true)
}

private data class MatteSource(val path: String, val length: Long, val modifiedAt: Long)
private data class MatteInputs(val video: MatteSource, val base: MatteSource)
private val localGreenMatteTunings = LruCache<MatteInputs, GreenMatteTuning>(48)

private fun File.matteSource() = MatteSource(path, length(), lastModified())

/** Local-only inspection; leaves the original files and their cached QA untouched. */
internal fun detectAdaptiveGreenMatte(videoUrl: String, neutralUrl: String?): GreenMatteTuning = runCatching {
    val video = Uri.parse(videoUrl.substringBefore('?'))
    val neutral = neutralUrl?.let { Uri.parse(it.substringBefore('?')) }
    if (video.scheme != "file" || neutral?.scheme != "file") return@runCatching GreenMatteTuning()
    val anchor = File(requireNotNull(neutral.path))
    val base = File(anchor.parentFile?.parentFile ?: return@runCatching GreenMatteTuning(), "base.png")
    val videoFile = File(requireNotNull(video.path))
    if (!base.isFile || !videoFile.isFile) return@runCatching GreenMatteTuning()
    val inputs = MatteInputs(videoFile.matteSource(), base.matteSource())
    localGreenMatteTunings.get(inputs)?.let { return@runCatching it }
    val subjectBitmap = BitmapFactory.decodeFile(base.path) ?: return@runCatching GreenMatteTuning()
    val subject = mutableListOf<Int>()
    try {
        val step = maxOf(2, minOf(subjectBitmap.width, subjectBitmap.height) / 200)
        for (y in 0 until subjectBitmap.height step step) for (x in 0 until subjectBitmap.width step step) {
            val pixel = subjectBitmap.getPixel(x, y)
            if (Color.alpha(pixel) >= 240) subject += pixel
        }
    } finally { subjectBitmap.recycle() }
    val border = mutableListOf<Int>()
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(videoFile.path)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: return@runCatching GreenMatteTuning()
        for (fraction in listOf(.25, .5, .75)) {
            val frame = retriever.getFrameAtTime((duration * fraction * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
            try {
                for (x in listOf(.025f, .075f, .925f, .975f)) for (y in listOf(.04f, .18f, .36f, .64f, .82f, .96f)) {
                    border += frame.getPixel((frame.width * x).toInt().coerceIn(0, frame.width - 1), (frame.height * y).toInt().coerceIn(0, frame.height - 1))
                }
            } finally { frame.recycle() }
        }
    } finally { retriever.release() }
    chooseGreenMatteTuning(border, subject).also {
        if (border.size >= 36) localGreenMatteTunings.put(inputs, it)
    }
}.getOrDefault(GreenMatteTuning())
