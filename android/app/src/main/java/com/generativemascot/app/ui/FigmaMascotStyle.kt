package com.generativemascot.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.generativemascot.app.R
import com.generativemascot.app.data.resolveMediaUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val FigmaCanvas = Color(0xFFEAEBEC)
internal val FigmaInk = Color(0xFF222222)
internal val RubikOne = FontFamily(Font(R.font.rubik_one))
internal val RubikBubbles = FontFamily(Font(R.font.rubik_bubbles))
internal val SbSansDisplayMedium = FontFamily(
    Font(R.font.sb_sans_display_medium, FontWeight.Medium),
)

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

@Composable
internal fun MascotSystemBars(top: Color, bottom: Color = top) {
    val view = LocalView.current
    val activity = LocalContext.current.hostActivity()
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = top.luminance() > .45f
                isAppearanceLightNavigationBars = bottom.luminance() > .45f
            }
        }
    }
}

internal data class HeroPalette(
    val top: Color,
    val middle: Color,
    val bottom: Color,
    val end: Color,
)

private val paletteCache = android.util.LruCache<String, HeroPalette>(64)

@Composable
@SuppressLint("ProduceStateDoesNotAssignValue")
internal fun heroPalette(url: String?): HeroPalette {
    val context = LocalContext.current
    val fallback = HeroPalette(
        top = Color(0xFF183A71),
        middle = Color(0xFF275EA4),
        bottom = Color(0xFF8BDDE8),
        end = Color(0xFFE8D7E4),
    )
    val target by produceState(url?.let(paletteCache::get) ?: fallback, url) {
        if (url.isNullOrBlank()) return@produceState
        paletteCache.get(url)?.let { value = it; return@produceState }
        val bitmap = runCatching {
            context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(resolveMediaUrl(url))
                    .allowHardware(false)
                    .build(),
            ).drawable?.toBitmap(96, 96)
        }.getOrNull() ?: return@produceState
        value = withContext(Dispatchers.Default) { extractHeroPalette(bitmap, fallback) }
        paletteCache.put(url, value)
    }
    val top by animateColorAsState(target.top, tween(280), label = "palette top")
    val middle by animateColorAsState(target.middle, tween(280), label = "palette middle")
    val bottom by animateColorAsState(target.bottom, tween(280), label = "palette bottom")
    val end by animateColorAsState(target.end, tween(280), label = "palette end")
    return HeroPalette(top, middle, bottom, end)
}

private fun extractHeroPalette(bitmap: Bitmap, fallback: HeroPalette): HeroPalette {
    val bins = HashMap<Int, Int>()
    val corners = listOf(
        bitmap.getPixel(0, 0),
        bitmap.getPixel(bitmap.width - 1, 0),
        bitmap.getPixel(0, bitmap.height - 1),
        bitmap.getPixel(bitmap.width - 1, bitmap.height - 1),
    )
    for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
        val pixel = bitmap.getPixel(x, y)
        val alpha = android.graphics.Color.alpha(pixel)
        val red = android.graphics.Color.red(pixel)
        val green = android.graphics.Color.green(pixel)
        val blue = android.graphics.Color.blue(pixel)
        if (alpha < 150 || (red > 232 && green > 232 && blue > 232)) continue
        val isBackground = corners.any { corner ->
            kotlin.math.abs(red - android.graphics.Color.red(corner)) +
                kotlin.math.abs(green - android.graphics.Color.green(corner)) +
                kotlin.math.abs(blue - android.graphics.Color.blue(corner)) < 78
        }
        if (isBackground || maxOf(red, green, blue) - minOf(red, green, blue) < 20) continue
        val key = ((red shr 4) shl 8) or ((green shr 4) shl 4) or (blue shr 4)
        bins[key] = (bins[key] ?: 0) + 1
    }
    val key = bins.maxByOrNull { it.value }?.key ?: return fallback
    val primary = Color(
        red = ((key shr 8) and 15) * 17,
        green = ((key shr 4) and 15) * 17,
        blue = (key and 15) * 17,
    )
    return HeroPalette(
        top = primary.withTone(lightness = 0.25f, saturationScale = 0.86f),
        middle = primary.withTone(lightness = 0.45f, saturationScale = 0.88f),
        bottom = lerp(primary, Color.White, 0.70f),
        end = lerp(primary, Color(0xFFF0D9E3), 0.86f),
    )
}

private fun Color.withTone(lightness: Float, saturationScale: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = (hsl[1] * saturationScale).coerceIn(0.32f, 0.82f)
    hsl[2] = lightness
    return Color(ColorUtils.HSLToColor(hsl))
}

internal fun heroGradient(palette: HeroPalette): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to palette.top,
        0.38f to palette.middle,
        0.76f to palette.bottom,
        1f to palette.end,
    ),
)

@Composable
internal fun HeroThumbnail(hero: HeroLibraryItem, modifier: Modifier = Modifier) {
    val preview = hero.baseStill ?: hero.baseFrames.firstOrNull()
    val palette = heroPalette(preview)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(heroGradient(palette))
            .border(2.dp, Color.White.copy(alpha = 0.42f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = resolveMediaUrl(preview),
            contentDescription = hero.name ?: "Герой",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
