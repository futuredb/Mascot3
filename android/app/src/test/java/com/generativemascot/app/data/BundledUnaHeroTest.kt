package com.generativemascot.app.data

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BundledUnaHeroTest {
    @Test fun realBundleJoinsTheSameLibraryWithAllSeventeenClipsAndNoProviderJobs() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val id = "716cdb43-703b-4d7e-8a79-27f03d92b99c"
        val previousId = "9f226b89-df35-4d3a-9b36-96c28ea1bb62"
        val session = SessionStore(context)
        session.saveAcceptedMascot(previousId)
        val importer = BundledHeroImporter(
            listAssets = { context.assets.list(it).orEmpty() },
            openAsset = { context.assets.open(it) },
            destinationRoot = File(context.filesDir, "hero"),
        )
        importer.importAll()
        val store = HeroLocalStore(context)
        val heroIds = store.localMascotIds()
        assertTrue(heroIds.containsAll(listOf(previousId, "a83e9d30-1270-40e6-902c-b44774e84c17", id)))
        assertEquals(heroIds.distinct(), heroIds)
        assertEquals(previousId, session.acceptedMascotId())
        assertEquals("Уна", store.mascotName(id))
        val bitmap = BitmapFactory.decodeFile(store.baseFile(id)!!.absolutePath)
        assertNotNull(bitmap)
        assertEquals(1024, bitmap.width)
        assertEquals(1536, bitmap.height)
        assertTrue(bitmap.hasAlpha())
        bitmap.recycle()
        assertEquals(Color.GREEN, store.videoMatteColor(id))
        assertNotNull(store.neutralVideoFrameFile(id))
        assertNotNull(store.creativeContract(id))
        assertEquals(HeroLocalStore.LEGACY_LIBRARY_VIDEO_ACTIONS.toSet(), store.actionVideoUrls(id).keys)
        assertTrue(store.hasOpenRouterVideoProgress(id)) // Approved video files are durable progress, not jobs.
        assertTrue(HeroLocalStore.LIBRARY_VIDEO_ACTIONS.all { store.openRouterVideoJobId(id, it) == null })
        assertNull(store.latestAnimationBatchId(id))
        assertTrue(store.animationPackReady(id))
        assertTrue(planAnimationBatch(store.actionVideoUrls(id).keys, emptySet(), 17).isEmpty())
        val json = Json { ignoreUnknownKeys = true }
        val manifest = json.decodeFromString<VideoPackManifest>(File(context.filesDir, "hero/$id/videos/manifest.json").readText())
        assertEquals(id, manifest.mascotId)
        assertEquals(store.actionVideoUrls(id).keys, manifest.clips.map { it.id }.toSet())
        HeroLocalStore.LEGACY_LIBRARY_VIDEO_ACTIONS.forEach { action ->
            val qa = json.decodeFromString<VideoClipQa>(File(context.filesDir, "hero/$id/videos/$action.qa.json").readText())
            assertEquals(action, qa.action)
            assertTrue(qa.hardPass)
            assertTrue(qa.blockingIssues.isEmpty())
            assertArrayEquals(
                context.assets.open("bundled-heroes/$id/videos/$action.mp4").use { it.readBytes() },
                store.actionVideoFile(id, action)!!.readBytes(),
            )
        }
        store.saveMascotName(id, "Уна друга")
        importer.importAll()
        assertEquals("Уна друга", store.mascotName(id))
        assertEquals(heroIds.toSet(), store.localMascotIds().toSet())
        assertEquals(previousId, session.acceptedMascotId())
        assertFalse(File(context.filesDir, "hero/$id/widget-frames").exists())
    }
}
