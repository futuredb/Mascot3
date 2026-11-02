package com.generativemascot.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.generativemascot.app.physics.TiltLighting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

@Serializable
internal data class VideoLightingManifest(
    val format: String, val version: Int,
    @SerialName("source_sha256") val sourceSha256: String,
    @SerialName("source_bytes") val sourceBytes: Long,
    val width: Int, val height: Int,
    @SerialName("tile_size") val tileSize: Int,
    val columns: Int,
    @SerialName("page_frames") val pageFrames: Int,
    @SerialName("timestamps_us") val timestampsUs: List<Long>,
    val pages: List<String>,
    @SerialName("page_sha256") val pageSha256: List<String>,
) {
    fun validate() {
        require(format == "mascot-video-lighting" && version == 1)
        require(sourceSha256.matches(Regex("[a-f0-9]{64}")) && sourceBytes > 0)
        require(width in 64..4096 && height in 64..4096)
        require(tileSize == 128 && columns == 8 && pageFrames == 64)
        require(timestampsUs.isNotEmpty() && timestampsUs.size <= 256 && timestampsUs.first() == 0L)
        require(timestampsUs.zipWithNext().all { (a,b) -> b > a })
        require(pages.size == (timestampsUs.size + pageFrames - 1) / pageFrames)
        require(pages.withIndex().all { (i,name) -> name == "normals-%03d.png".format(i) })
        require(pageSha256.size == pages.size && pageSha256.all { it.matches(Regex("[a-f0-9]{64}")) })
    }
    fun frameAt(sourceTimeUs: Long): Int? {
        if(sourceTimeUs < 0 || sourceTimeUs > timestampsUs.last() + 100_000) return null
        // Nearest source PTS tolerates decoder rounding; never use wall-clock time.
        val found=timestampsUs.binarySearch(sourceTimeUs)
        if(found>=0)return found
        val next=(-found-1).coerceAtMost(timestampsUs.lastIndex)
        val previous=(next-1).coerceAtLeast(0)
        return if(sourceTimeUs-timestampsUs[previous] <= timestampsUs[next]-sourceTimeUs)previous else next
    }
    fun tileRect(frame: Int): FloatArray {
        require(frame in timestampsUs.indices)
        val local=frame%pageFrames
        val count=minOf(pageFrames,timestampsUs.size-frame/pageFrames*pageFrames)
        val w=columns*tileSize;val h=((count+columns-1)/columns)*tileSize
        return floatArrayOf((local%columns*tileSize+.5f)/w,(local/columns*tileSize+.5f)/h,(tileSize-1f)/w,(tileSize-1f)/h)
    }
}

internal data class LoadedVideoLighting(val manifest: VideoLightingManifest, val pages: List<Bitmap>)
internal data class VideoLightingBinding(val pack: LoadedVideoLighting, val input: TiltLighting)

/** Bounded correspondence between Media3's stream clock and original clip PTS.
 * Recording happens before enqueue; looking up happens on the GL thread. */
internal class VideoFrameClock {
    private val sourceTimes=ConcurrentHashMap<Long,Long>()
    private val order=ConcurrentLinkedQueue<Long>()
    fun record(effectTimeUs: Long, sourceTimeUs: Long) {
        if(sourceTimes.putIfAbsent(effectTimeUs,sourceTimeUs)==null)order.add(effectTimeUs)
        else sourceTimes[effectTimeUs]=sourceTimeUs
        while(order.size>128)order.poll()?.let(sourceTimes::remove)
    }
    fun sourceTime(effectTimeUs: Long): Long? = sourceTimes[effectTimeUs]
}

private val lightingJson=Json { ignoreUnknownKeys=true }
private val videoClocks=WeakHashMap<ExoPlayer,VideoFrameClock>()
internal fun videoFrameClock(player: ExoPlayer): VideoFrameClock? = videoClocks[player]

/** Observe timestamps without changing decoder scheduling, clipping or looping. */
@OptIn(UnstableApi::class)
internal fun createMascotVideoPlayer(context: Context): ExoPlayer {
    val clock=VideoFrameClock()
    val factory=object:DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>) {
            out.add(object:MediaCodecVideoRenderer(context,codecAdapterFactory,mediaCodecSelector,allowedVideoJoiningTimeMs,
                enableDecoderFallback,eventHandler,eventListener,50) {
                override fun processOutputBuffer(positionUs: Long,elapsedRealtimeUs: Long,codec: MediaCodecAdapter?,
                    buffer: ByteBuffer?,bufferIndex: Int,bufferFlags: Int,sampleCount: Int,bufferPresentationTimeUs: Long,
                    isDecodeOnlyBuffer: Boolean,isLastBuffer: Boolean,format: androidx.media3.common.Format): Boolean {
                    // ClippingMediaPeriod preserves source sample timestamps.
                    // Removing the renderer offset already recovers original PTS,
                    // including when playback starts inside the sleeping clip.
                    clock.record(bufferPresentationTimeUs,bufferPresentationTimeUs-outputStreamOffsetUs)
                    return super.processOutputBuffer(positionUs,elapsedRealtimeUs,codec,buffer,bufferIndex,bufferFlags,sampleCount,
                        bufferPresentationTimeUs,isDecodeOnlyBuffer,isLastBuffer,format)
                }
            })
        }
    }
    return ExoPlayer.Builder(context,factory).build().also { videoClocks[it]=clock }
}

/** Exact content identity prevents applying another revision's moving surface. */
internal suspend fun loadVideoLighting(context: Context, rawUrl: String): LoadedVideoLighting? = withContext(Dispatchers.IO) {
    val decoded = mutableListOf<Bitmap>()
    runCatching {
        val uri=URI(rawUrl.substringBefore('?'))
        val file=when(uri.scheme) { "file" -> File(uri); null -> File(uri.path); else -> return@withContext null }
        if(!file.isFile)return@withContext null
        val hash=file.inputStream().use { stream ->
            val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true){val count=stream.read(buffer);if(count<0)break;digest.update(buffer,0,count)}
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val folder="video-lighting/$hash"
        val prepared=File(context.filesDir,"lighting/video/$hash")
        fun read(name: String): ByteArray = if(File(prepared,"manifest.json").isFile) File(prepared,name).readBytes()
            else context.assets.open("$folder/$name").use { it.readBytes() }
        val manifest=read("manifest.json").inputStream().bufferedReader().use {
            lightingJson.decodeFromString<VideoLightingManifest>(it.readText())
        }
        manifest.validate()
        require(manifest.sourceSha256==hash && manifest.sourceBytes==file.length())
        val images=manifest.pages.mapIndexed { index,name ->
            val data=read(name)
            require(MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) } == manifest.pageSha256[index])
            val bitmap=requireNotNull(BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply { inScaled=false }))
            decoded.add(bitmap)
            val count=minOf(manifest.pageFrames,manifest.timestampsUs.size-index*manifest.pageFrames)
            require(bitmap.width==manifest.columns*manifest.tileSize && bitmap.height==(count+manifest.columns-1)/manifest.columns*manifest.tileSize)
            bitmap
        }
        LoadedVideoLighting(manifest,images)
    }.getOrElse { decoded.forEach { it.recycle() }; null } // Missing/corrupt sidecars never interrupt a saved video.
}
