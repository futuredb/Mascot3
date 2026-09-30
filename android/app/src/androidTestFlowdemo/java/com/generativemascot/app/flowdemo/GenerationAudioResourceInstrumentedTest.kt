package com.generativemascot.app.flowdemo

import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Resource decoding only: no activity, audible playback, model or server call. */
@RunWith(AndroidJUnit4::class)
class GenerationAudioResourceInstrumentedTest {
    @Test fun suppliedThreeSecondSoundIsBundledAndDecodableByThePhone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val retriever = MediaMetadataRetriever()
        try {
            context.resources.openRawResourceFd(R.raw.generation_start).use { asset ->
                retriever.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            }
            assertEquals("3000", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
        } finally { retriever.release() }
        val pool = SoundPool.Builder().setMaxStreams(1)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        val loaded = CountDownLatch(1)
        var status = -1
        try {
            pool.setOnLoadCompleteListener { _, _, result -> status = result; loaded.countDown() }
            assertTrue(pool.load(context, R.raw.generation_start, 1) > 0)
            assertTrue("SoundPool did not finish loading", loaded.await(10, TimeUnit.SECONDS))
            assertEquals(0, status)
        } finally { pool.release() }
    }
}
