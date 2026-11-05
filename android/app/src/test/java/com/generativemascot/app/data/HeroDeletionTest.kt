package com.generativemascot.app.data

import android.app.Application
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HeroDeletionTest {
    @Test fun deletingOneHeroKeepsOthersAndPreventsBundleReimport() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = HeroLocalStore(context)
        val root = File(context.filesDir, "hero")
        val id = "deleted-hero"
        val other = "kept-hero"
        listOf(id, other).forEach { hero ->
            File(root, "$hero/base.png").apply { parentFile!!.mkdirs(); writeText(hero) }
            File(root, "$hero/videos/idle.mp4").apply { parentFile!!.mkdirs(); writeText("video-$hero") }
        }
        context.getSharedPreferences("mascot_chat_history", 0).edit().putString(id, "history").putString(other, "keep").commit()
        store.deleteMascot(id)
        assertFalse(File(root, id).exists())
        assertEquals("video-$other", File(root, "$other/videos/idle.mp4").readText())
        assertTrue(store.isDeleted(id))
        assertEquals(listOf(other), store.localMascotIds())
        assertFalse(context.getSharedPreferences("mascot_chat_history", 0).contains(id))
        assertEquals("keep", context.getSharedPreferences("mascot_chat_history", 0).getString(other, null))
        BundledHeroImporter(
            listAssets = { path -> when (path) {
                "bundled-heroes" -> arrayOf(id)
                "bundled-heroes/$id" -> arrayOf("base.png")
                else -> emptyArray()
            } },
            openAsset = { ByteArrayInputStream(byteArrayOf(1)) },
            destinationRoot = root,
            isDeleted = store::isDeleted,
        ).importAll()
        assertFalse(File(root, id).exists())
    }

    @Test fun deletionRejectsPathsOutsideHeroDirectory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val outside = File(context.filesDir, "untouched").apply { writeText("safe") }
        try { HeroLocalStore(context).deleteMascot("../untouched"); fail("invalid id accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals("safe", outside.readText())
    }

    @Test fun sessionReplacesOnlyTheDeletedSelection() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val session = SessionStore(context)
        session.saveAcceptedMascot("first")
        session.replaceDeletedMascot("other", "second")
        assertEquals("first", session.acceptedMascotId())
        session.replaceDeletedMascot("first", "second")
        assertEquals("second", session.acceptedMascotId())
        session.replaceDeletedMascot("second", null)
        assertNull(session.acceptedMascotId())
        assertNull(session.mascotId())
    }
}
