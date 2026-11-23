package com.generativemascot.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.MainActivity
import com.generativemascot.app.MascotApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Run with -e heroName and leave that hero selected before testing the main screen. */
@RunWith(AndroidJUnit4::class)
class NamedBundledHeroInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MascotApp

    private fun heroName(): String {
        val name = InstrumentationRegistry.getArguments().getString("heroName")
        assumeNotNull(name)
        return requireNotNull(name)
    }

    private suspend fun importedHeroId(name: String): String {
        val heroes = app.assets.list("bundled-heroes").orEmpty()
        val matches = heroes.filter { id ->
            runCatching {
                app.assets.open("bundled-heroes/$id/name.txt").bufferedReader().use { it.readText().trim() }
            }.getOrNull() == name
        }
        assertEquals("Exactly one bundled character named $name", 1, matches.size)
        val id = matches.single()
        app.ensureBundledHeroes()
        assertTrue(id in app.heroStore.localMascotIds())
        assertEquals(name, app.heroStore.mascotName(id))
        return id
    }

    @Test fun bundledPortraitImportsAndDecodes() = runBlocking {
        val id = importedHeroId(heroName())
        val portrait = requireNotNull(app.heroStore.baseFile(id))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(portrait.absolutePath, bounds)
        assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
    }

    @Test fun bundledIdleClipDecodesOnThePhone() = runBlocking {
        val id = importedHeroId(heroName())
        val clip = requireNotNull(app.heroStore.actionVideoFile(id, "idle"))
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(clip.absolutePath)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            assertNotNull("No playable idle frame in ${clip.name}", frame)
            frame?.recycle()
        } finally {
            retriever.release()
        }
        Unit
    }

    @Test fun selectedHeroAppearsOnHomeAfterLaunch() {
        val name = heroName()
        val id = runBlocking { importedHeroId(name) }
        assertEquals("Select $name on the device before running this test", id,
            runBlocking { app.session.acceptedMascotId() })
        val heading = name.uppercase(Locale.ROOT)
        val launch = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(launch).use {
            val until = SystemClock.elapsedRealtime() + 15_000
            var homeVisible = false
            var foregroundPackage: CharSequence? = null
            while (!homeVisible && SystemClock.elapsedRealtime() < until) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                foregroundPackage = root?.packageName
                val labels = buildSet { root?.collectVisibleText(this) }
                homeVisible = heading in labels && "ЧАТ" in labels
                if (!homeVisible) SystemClock.sleep(200)
            }
            assertTrue("$heading did not appear with the chat button. Foreground package: $foregroundPackage", homeVisible)
        }
    }

    private fun AccessibilityNodeInfo.collectVisibleText(labels: MutableSet<String>) {
        if (isVisibleToUser) {
            text?.lineSequence()?.map(String::trim)?.forEach(labels::add)
        }
        for (index in 0 until childCount) {
            getChild(index)?.collectVisibleText(labels)
        }
    }
}
