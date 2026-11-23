package com.generativemascot.app.data

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

internal object VideoLoopRepair {
    fun canRepair(qa: VideoClipQa): Boolean {
        val seam = qa.loopSeam ?: return false
        val issue = loopSeamIssue(seam)
        // A jump this large can be a subject swap; trimming must not hide it.
        return qa.action !in setOf("sleeping", "sleep_loop") && seam.peakEdgeStepDelta <= .14 &&
            issue != null && qa.blockingIssues == listOf(issue)
    }

    data class Result(val bytes: ByteArray, val qa: VideoClipQa)

    suspend fun repair(context: Context, source: File, qa: VideoClipQa, expectedSeconds: Int): Result? =
        withContext(Dispatchers.IO) {
            if (!canRepair(qa)) return@withContext null
            try {
                val matte = Color.parseColor(qa.matteHex)
                for (window in VideoQualityGate.repairWindows(source, qa.durationMs, expectedSeconds, matte)) {
                    val output = File.createTempFile("loop-repair-", ".mp4", context.cacheDir)
                    try {
                        if (!export(context, source, output, window)) continue
                        val checked = VideoQualityGate.inspect(output, qa.action, expectedSeconds, matte, enforceLoop = true)
                        if (checked.hardPass) return@withContext Result(output.readBytes(), checked)
                    } finally {
                        output.delete()
                    }
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    @OptIn(UnstableApi::class)
    private suspend fun export(context: Context, source: File, output: File, window: LongRange): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val transformer = Transformer.Builder(context).build()
            try {
                withTimeoutOrNull(45_000L) {
                    suspendCancellableCoroutine { continuation ->
                        transformer.addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(true)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        })
                        val clipping = MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(window.first)
                            .setEndPositionMs(window.last)
                            .build()
                        transformer.start(MediaItem.Builder().setUri(Uri.fromFile(source))
                            .setClippingConfiguration(clipping).build(), output.absolutePath)
                    }
                } ?: false
            } finally {
                transformer.cancel()
            }
        }
}
