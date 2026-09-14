package com.generativemascot.app.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.generativemascot.app.data.resolveMediaUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class ActionFrame(val bitmap: Bitmap, val keyColor: Int)
private val actionFrameCache = android.util.LruCache<String, ActionFrame>(24)

/** A cached real pose from the clip. Browsing the grid does not allocate video decoders. */
@Composable
internal fun ActionThumbnail(
    videoUrl: String?,
    fallback: String?,
    description: String,
    modifier: Modifier = Modifier,
) {
    val frame = remember(videoUrl) { mutableStateOf(videoUrl?.let(actionFrameCache::get)) }
    LaunchedEffect(videoUrl) {
        if (videoUrl == null || frame.value != null) return@LaunchedEffect
        delay(300) // Let the navigation finish before reading video frames.
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(videoUrl)
                // Phone-owned clips only; opening a gallery must never download a remote movie.
                if (uri.scheme != "file" && uri.scheme != null) return@runCatching null
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(uri.path)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 4000L
                    val timeUs = duration * 500L
                    val bitmap = if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 256, 320)
                    } else retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    bitmap?.let { ActionFrame(it, detectBackgroundColor(videoUrl)) }
                } finally { retriever.release() }
            }.getOrNull()
        }
        if (decoded != null) { actionFrameCache.put(videoUrl, decoded); frame.value = decoded }
    }
    val shown = frame.value
    if (shown == null) {
        AsyncImage(
            model = resolveMediaUrl(fallback),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        AndroidView(
            modifier = modifier,
            factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { view ->
                view.contentDescription = description
                view.setImageBitmap(shown.bitmap)
                applyBackgroundKey(view, shown.keyColor)
            },
        )
    }
}
