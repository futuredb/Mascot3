package com.generativemascot.app.ui

import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.MainActivity
import com.generativemascot.app.data.HeroLocalStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimationReviewInstrumentedTest {
    @Test fun homeReplaysAnArchivedClipAndReturnsToItsNormalState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val hero = "test_" + UUID.randomUUID().toString().replace("-", "")
        val baseline = java.io.File(app.filesDir, "hero/$hero/base.png")
        try {
            baseline.parentFile?.mkdirs()
            app.assets.open("bundled-heroes/300392a5-3351-4add-9e22-7a21a56f4d1a/base.png").use { source ->
                baseline.outputStream().use { source.copyTo(it) }
            }
            val archive = HeliosClipArchive(app, hero)
            val id = archive.begin("Реакция для проверки", "video/webm")
            val sample = instrumentation.context.assets.open("helios-sample.webm").use { it.readBytes() }
            assertTrue(archive.append(id, Base64.encodeToString(sample, Base64.NO_WRAP)))
            assertTrue(archive.finish(id))
            val completed = CountDownLatch(1)
            val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MascotTheme {
                            KnockHeroScreen(generating = false, previewUrl = baseline.toURI().toString(),
                                error = null, accepted = true, mascotId = hero, onKnockComplete = {}, onAccept = {},
                                onPoll = {}, acceptEnabled = false,
                                onOneShotFinished = { if (it == "reaction") completed.countDown() })
                        }
                    }
                }
                assertEquals(id, HeroLocalStore.current?.chatAnimations(hero)?.singleOrNull()?.id)
                val automation = instrumentation.uiAutomation
                val label = "Повторить реакцию из чата"
                fun replayButton(): AccessibilityNodeInfo? {
                    val root = automation.rootInActiveWindow ?: return null
                    return findButton(root, label)?.takeIf { it.isEnabled }
                }
                val deadline = SystemClock.uptimeMillis() + 10_000L
                var button = replayButton()
                while (button == null && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(150)
                    button = replayButton()
                }
                assertTrue("Replay button must be visible and clickable",
                    button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                assertTrue("Saved video must finish playing", completed.await(20, TimeUnit.SECONDS))
                assertEquals(id, HeroLocalStore(app).chatAnimations(hero).single().id)
            }
        } finally {
            baseline.parentFile?.deleteRecursively()
        }
    }

    private fun findButton(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isClickable && node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { findButton(it, label) }?.let { return it }
        }
        return null
    }

    @Test fun galleryHidesPlaybackStatesAndShowsSharedMediaOnce() {
        val actions = galleryAnimationActions(
            videos = mapOf("idle" to "idle.mp4", "typing_reaction" to "typing.mp4",
                "angry" to "same.mp4", "annoyed" to "same.mp4", "offended" to "offended.mp4"),
            frames = emptyMap(), legacyVideo = "old-performance.mp4",
        )
        assertFalse("idle" in actions)
        assertFalse("typing_reaction" in actions)
        assertEquals(listOf("angry"), actions.filter { it in setOf("angry", "annoyed") })
        assertEquals(listOf("sleeping"), actions.filter { it in LEGACY_PERFORMANCE_ACTIONS })
        assertTrue("offended" in actions)
    }

    @Test fun quietReactionUsesLocalTimeAndRequiresAnExistingClip() {
        val actions = setOf("idle", "bored", "sleepy")
        assertEquals("bored", quietHomeReaction(14, actions))
        assertEquals("sleepy", quietHomeReaction(21, actions))
        assertNull(quietHomeReaction(23, actions))
        assertNull(quietHomeReaction(21, setOf("bored")))
    }
}
