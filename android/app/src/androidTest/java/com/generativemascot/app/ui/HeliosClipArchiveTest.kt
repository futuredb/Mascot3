package com.generativemascot.app.ui

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.data.HeroLocalStore
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeliosClipArchiveTest {
    @Test fun completeWebmBecomesPlayableLibraryItemAndIncompleteFilesStayHidden() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val hero = "test_" + UUID.randomUUID().toString().replace("-", "")
        val directory = File(context.filesDir, "hero/$hero")
        try {
            directory.mkdirs()
            File(directory, "base.png").writeBytes(byteArrayOf(1))
            val archive = HeliosClipArchive(context, hero)
            val bytes = instrumentation.context.assets.open("helios-sample.webm").use { it.readBytes() }
            val id = archive.begin("Тестовая реакция", "video/webm;codecs=vp8")
            val mid = bytes.size / 2
            assertTrue(archive.append(id, Base64.encodeToString(bytes.copyOfRange(0, mid), Base64.NO_WRAP)))
            assertTrue(archive.append(id, Base64.encodeToString(bytes.copyOfRange(mid, bytes.size), Base64.NO_WRAP)))
            val store = HeroLocalStore(context)
            assertTrue(store.chatAnimations(hero).isEmpty())
            assertTrue(archive.finish(id))
            val clip = store.chatAnimations(hero).single()
            assertEquals("Тестовая реакция", clip.title)
            assertTrue(clip.videoUrl.endsWith(".webm"))
            assertArrayEquals(bytes, File(java.net.URI(clip.videoUrl)).readBytes())
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(File(java.net.URI(clip.videoUrl)).absolutePath)
                val frame = retriever.getFrameAtTime(0)
                assertNotNull(frame)
                frame?.recycle()
            } finally { retriever.release() }

            val broken = archive.begin("Неполный файл", "video/webm")
            assertTrue(archive.append(broken, Base64.encodeToString(ByteArray(2048), Base64.NO_WRAP)))
            assertFalse(archive.finish(broken))
            assertEquals(1, store.chatAnimations(hero).size)
            // Older Turbo MP4s remain visible alongside new WebM files.
            File(store.chatAnimationDirectory(hero), "0123456789abcdef0123456789abcdef.mp4").writeBytes(ByteArray(2048))
            assertEquals(2, store.chatAnimations(hero).size)
        } finally {
            // This directory was created uniquely by this test, never an existing hero.
            directory.deleteRecursively()
        }
    }
}
