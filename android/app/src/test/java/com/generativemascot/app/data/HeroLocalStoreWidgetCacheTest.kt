package com.generativemascot.app.data

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HeroLocalStoreWidgetCacheTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val id = "widget-test-${UUID.randomUUID()}"
    private val store get() = HeroLocalStore(context)

    @Test fun soleGreetingIsUsedForWidgetFallbackWithoutRegenerationOrCacheChanges() = runBlocking {
        val video = File(context.filesDir, "hero/$id/videos/greeting.mp4").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(2048) { 1 })
        }
        val key = "${video.length()}:${video.lastModified()}:320:12:v4"
        val directory = File(context.filesDir, "hero/$id/widget-frames/greeting/cache-${promptSha256(key).take(16)}")
        directory.mkdirs()
        File(directory, "source.txt").writeText("${video.length()}:${video.lastModified()}:24:320:12:v4")
        File(directory, "interval-ms.txt").writeText("83")
        val frames = List(24) { index -> File(directory, "frame_%02d.png".format(index)).apply {
            writeBytes(ByteArray(256) { 2 })
        } }
        for (state in listOf("idle", "working", "sleeping", "rainy")) {
            assertEquals(frames.drop(1), store.cachedWidgetAnimation(id, state).files)
            assertEquals(frames.drop(1), store.widgetAnimation(id, state).files)
            assertFalse(store.needsWidgetPreparation(id, state))
        }
        // Once more animations exist, missing context must not force the greeting.
        fakeVideo()
        assertTrue(store.needsWidgetPreparation(id, "idle"))
        assertFalse(store.cachedWidgetAnimation(id, "idle").fromVideoCache)
    }

    private fun fakeVideo(): File = File(context.filesDir, "hero/$id/videos/idle.mp4").apply {
        parentFile!!.mkdirs()
        // Deliberately not a playable video: a warm cache must not open a decoder.
        writeBytes(ByteArray(2048) { 1 })
    }

    private fun writeCache(video: File, count: Int = 24, legacy: Boolean = false): List<File> {
        val side = if (legacy) 160 else 320
        val version = if (legacy) 3 else 4
        val key = "${video.length()}:${video.lastModified()}:$side:12:v$version"
        val root = File(context.filesDir, "hero/$id/widget-frames/idle/cache-${promptSha256(key).take(16)}")
        root.mkdirs()
        File(root, "source.txt").writeText("${video.length()}:${video.lastModified()}:$count:$side:12:v$version")
        File(root, "interval-ms.txt").writeText("83")
        return List(count) { index -> File(root, "frame_%02d.png".format(index)).apply { writeBytes(ByteArray(256) { 2 }) } }
    }

    @Test fun warmCacheIsReusedWithoutOpeningTheVideoOrChangingFiles() = runBlocking {
        val video = fakeVideo()
        val frames = writeCache(video)
        val timestamps = frames.map(File::lastModified)
        repeat(3) {
            val result = store.widgetAnimation(id, "idle")
            assertTrue(result.fromVideoCache)
            assertEquals(frames.drop(1), result.files)
            assertEquals(83, result.frameIntervalMs)
        }
        assertEquals(timestamps, frames.map(File::lastModified))
        assertFalse(store.needsWidgetPreparation(id, "idle"))
    }

    @Test fun concurrentWarmRequestsReuseTheSamePublishedCache() = runBlocking {
        val frames = writeCache(fakeVideo())
        val results = List(4) { async { store.widgetAnimation(id, "idle") } }.awaitAll()
        assertTrue(results.all { it.fromVideoCache && it.files == frames.drop(1) })
    }

    @Test fun cachedWidgetReaderExcludesZeroWithoutInvalidatingTheCache() {
        val frames = writeCache(fakeVideo())
        assertEquals(frames.drop(1), store.cachedWidgetAnimation(id, "idle").files)
        assertTrue(frames.first().isFile)
        assertFalse(store.needsWidgetPreparation(id, "idle"))
    }

    @Test fun publishedActionFramesSkipZeroInLibraryHomeAndWidgetButKeepRawSources() = runBlocking {
        val directory = File(context.filesDir, "hero/$id/sequences/idle").apply { mkdirs() }
        val frames = List(3) { index ->
            File(directory, "frame_%03d.png".format(index)).apply { writeBytes(ByteArray(256) { 2 }) }
        }
        assertEquals(frames, store.sequenceFiles(id, "idle"))
        assertEquals(frames.drop(1), store.playbackSequenceFiles(id, "idle"))
        assertEquals(frames.drop(1), store.librarySequenceFiles(id, "idle"))
        assertEquals(frames.drop(1), store.cachedWidgetAnimation(id, "idle").files)
        assertEquals(frames.drop(1), store.widgetAnimation(id, "idle").files)
        assertTrue(frames.all(File::isFile))
    }

    @Test fun singleStaticBaseFrameRemainsAvailable() {
        val directory = File(context.filesDir, "hero/$id/sequences/base").apply { mkdirs() }
        val frame = File(directory, "frame_000.png").apply { writeBytes(ByteArray(256) { 2 }) }
        assertEquals(listOf(frame), store.playbackSequenceFiles(id, "idle"))
        assertEquals(listOf(frame), store.cachedWidgetAnimation(id, "idle").files)
    }

    @Test fun incompleteOrTruncatedCacheIsNotMarkedReady() {
        val frames = writeCache(fakeVideo())
        frames[3].writeBytes(ByteArray(0))
        assertFalse(store.cachedWidgetAnimation(id, "idle").fromVideoCache)
        assertTrue(store.needsWidgetPreparation(id, "idle"))
    }

    @Test fun changedSourceInvalidatesCacheWithoutDeletingLauncherReadablePaths() {
        val video = fakeVideo()
        val frames = writeCache(video)
        assertTrue(store.cachedWidgetAnimation(id, "idle").fromVideoCache)
        video.appendBytes(ByteArray(20) { 3 })
        assertFalse(store.cachedWidgetAnimation(id, "idle").fromVideoCache)
        assertTrue(frames.all { it.isFile && it.length() == 256L })
        assertTrue(store.needsWidgetPreparation(id, "idle"))
    }

    @Test fun legacyCacheRemainsVisibleDuringLocalUpgradeAndNewCacheTakesPriority() = runBlocking {
        val video = fakeVideo()
        val oldFrames = writeCache(video, legacy = true)
        assertEquals(oldFrames.drop(1), store.cachedWidgetAnimation(id, "idle").files)
        assertTrue(store.needsWidgetPreparation(id, "idle"))
        val newFrames = writeCache(video)
        assertEquals(newFrames.drop(1), store.widgetAnimation(id, "idle").files)
        assertFalse(store.needsWidgetPreparation(id, "idle"))
        assertTrue(oldFrames.all { it.isFile })
    }
}
