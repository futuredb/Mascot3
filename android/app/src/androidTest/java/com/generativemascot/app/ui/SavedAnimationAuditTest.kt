package com.generativemascot.app.ui

import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedAnimationAuditTest {
    @Test fun savedAnimationClipsDecodeOnTheDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("auditSavedAnimations") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val heroes = app.filesDir.resolve("hero").listFiles().orEmpty().filter { it.isDirectory }
        val clips = heroes.flatMap { hero ->
            hero.resolve("videos").listFiles().orEmpty().filter { it.isFile && it.extension == "mp4" }
        }
        assertTrue("No saved animations to audit", clips.isNotEmpty())
        val invalid = mutableListOf<String>()
        for (clip in clips) {
            val label = "${clip.parentFile?.parentFile?.name}/${clip.name}"
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(clip.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (duration <= 0) {
                    invalid += "$label: duration"
                    continue
                }
                for (time in listOf(0L, duration * 500)) {
                    val frame = if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 128, 128)
                    } else retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame == null) invalid += "$label: frame ${time / 1_000}ms"
                    frame?.recycle()
                }
            } catch (_: Exception) {
                invalid += "$label: decode"
            } finally {
                retriever.release()
            }
        }
        android.util.Log.i("MascotReleaseAudit", "Decoded clips=${clips.size}, invalid=${invalid.size}")
        assertTrue(invalid.joinToString("\n"), invalid.isEmpty())
    }
}
