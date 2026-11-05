package com.generativemascot.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.generativemascot.app.data.resolveMediaUrl
import com.generativemascot.app.physics.PhysicsFraming
import com.generativemascot.app.physics.SubjectBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A fixed neutral-pose anchor: animation frames must keep their authored hops. */
internal data class HeroGrounding(val width: Int, val height: Int, val bottom: Int, val left: Int = 0, val top: Int = 0, val right: Int = width) {
    fun bounds() = SubjectBounds(width, height, left, top, right, bottom)
    fun translationY(viewWidth: Float, viewHeight: Float): Float {
        val fittedScale = minOf(viewWidth / width, viewHeight / height)
        return minOf(viewWidth, viewHeight) * PhysicsFraming.GROUND_OFFSET -
            (bottom - height * .5f) * fittedScale
    }
}

private val groundingCache = LruCache<String, HeroGrounding>(48)

/** Uses alpha for cutouts and the playback matte for opaque neutral frames. */
internal fun detectHeroGrounding(bitmap: Bitmap): HeroGrounding? {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val border = buildList {
        for (x in listOf(.025f, .075f, .925f, .975f)) {
            for (y in listOf(.04f, .18f, .36f, .64f, .82f, .96f)) {
                add(pixels[(y * height).toInt() * width + (x * width).toInt()])
            }
        }
    }
    val transparent = border.count { Color.alpha(it) < 16 } >= border.size / 2
    fun median(channel: (Int) -> Int) = border.map(channel).sorted()[border.size / 2]
    val key = Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
    // Ignore isolated compression flecks and faint floor shadows below the feet.
    val minimumRow = maxOf(2, width / 200)
    var bottom = 0
    var top = height
    var left = width
    var right = 0
    var occupiedRows = 0
    for (y in 0 until height) {
        var count = 0
        var rowLeft = width
        var rowRight = 0
        for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            val alpha = Color.alpha(if (transparent) pixel else keyedPixel(pixel, key))
            if (alpha >= 160) { count++; rowLeft = minOf(rowLeft, x); rowRight = maxOf(rowRight, x + 1) }
        }
        if (count >= minimumRow) { top = minOf(top, y); bottom = y + 1; left = minOf(left, rowLeft); right = maxOf(right, rowRight); occupiedRows++ }
    }
    if (occupiedRows < height / 20 || bottom == 0) return null
    return HeroGrounding(width, height, bottom, left, top, right)
}

@Composable
internal fun rememberHeroGrounding(sourceUrl: String?, enabled: Boolean = true): HeroGrounding? {
    val context = LocalContext.current
    val source = sourceUrl?.let(::resolveMediaUrl)
    val cacheKey = source?.let { url ->
        val uri = Uri.parse(url.substringBefore('?'))
        val file = if (uri.scheme == "file") uri.path?.let(::File) else null
        "$url:${file?.length()}:${file?.lastModified()}"
    }
    // Key the state itself so a carousel change never borrows the previous feet.
    var grounding by remember(cacheKey) { mutableStateOf(cacheKey?.let(groundingCache::get)) }
    LaunchedEffect(cacheKey, enabled) {
        if (!enabled || source == null || cacheKey == null || grounding != null) return@LaunchedEffect
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context).data(source).size(512).allowHardware(false).build(),
        )
        val bitmap = result.drawable?.toBitmap() ?: return@LaunchedEffect
        // Coil owns the bitmap. Cache only the small measurement, never recycle it.
        val measured = withContext(Dispatchers.Default) { detectHeroGrounding(bitmap) }
        if (measured != null) { groundingCache.put(cacheKey, measured); grounding = measured }
    }
    return if (enabled) grounding else null
}

@Composable
internal fun GroundedHeroArtwork(
    sourceUrl: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val grounding = rememberHeroGrounding(sourceUrl, enabled)
    Box(modifier) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val framing = grounding?.bounds()?.fit(size.width, size.height)
            scaleX = framing?.scale ?: 1f
            scaleY = framing?.scale ?: 1f
            translationX = framing?.translationX ?: 0f
            translationY = framing?.translationY ?: 0f
        }) { content() }
    }
}
