package com.generativemascot.app.ui

import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.data.GenerationRoute
import com.generativemascot.app.data.HeroLocalStore
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimationReviewInstrumentedTest {
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test fun chatReactionsWaitForTheClipEndUnlessTypingStarts() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(), "chat-reaction-").toFile()
        val action = mutableStateOf("idle")
        val interaction = mutableStateOf(PuppetInteraction())
        val typing = mutableStateOf(false)
        val visible = ConcurrentLinkedQueue<String>()
        val idleEnded = AtomicBoolean()
        val emotionEnded = AtomicBoolean()
        try {
            val clips = listOf("idle", "angry", "thinking").associateWith { label ->
                val file = folder.resolve("$label.mp4")
                app.assets.open("bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/videos/$label.mp4").use { video ->
                    file.outputStream().use { video.copyTo(it) }
                }
                file.toURI().toString()
            }
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        SoraMascotVideo(
                            videoUrls = clips, action = action.value, interaction = interaction.value,
                            modifier = Modifier.fillMaxSize(),
                            switchActionsPromptly = true, useNeutralTransitions = false,
                            playOnceActions = setOf("angry", "thinking"),
                            interruptOneShotForAction = "thinking".takeIf { typing.value },
                            onAnimationVisible = { visible.add(it) },
                            onOneShotFinished = { if (it == "angry") emotionEnded.set(true) },
                        )
                    }
                }
                waitFor("Idle played its first frame", 15_000) { "idle" in visible }
                scenario.onActivity { activity ->
                    val idle = requireNotNull(playingPlayer(activity.window.decorView))
                    idle.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) idleEnded.set(true)
                        }
                    })
                    action.value = "angry"
                }
                waitFor("Queued reaction became visible", 20_000) { "angry" in visible }
                assertTrue("Idle should reach its last frame before a normal reaction", idleEnded.get())
                scenario.onActivity {
                    typing.value = true
                    interaction.value = PuppetInteraction(actionName = "thinking", actionSerial = 1)
                }
                waitFor("The keyboard was able to interrupt the reaction", 10_000) { "thinking" in visible }
                assertFalse("The keyboard should interrupt before the emotion finishes", emotionEnded.get())
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test fun libraryPlaysAnArchivedChatClipAndKeepsItAfterClosingThePreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val hero = "test_" + UUID.randomUUID().toString().replace("-", "")
        val folder = java.io.File(app.filesDir, "hero/$hero")
        try {
            folder.mkdirs()
            app.assets.open("bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/base.png").use { image ->
                java.io.File(folder, "base.png").outputStream().use { image.copyTo(it) }
            }
            val archive = HeliosClipArchive(app, hero)
            val title = "Реакция для проверки"
            val id = archive.begin(title, "video/webm")
            val sample = instrumentation.context.assets.open("helios-sample.webm").use { it.readBytes() }
            assertTrue(archive.append(id, Base64.encodeToString(sample, Base64.NO_WRAP)))
            assertTrue(archive.finish(id))
            val automation = instrumentation.uiAutomation
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MascotTheme {
                            AnimationLibraryScreen(
                                mascotId = hero, animations = emptyMap(), videoUrls = emptyMap(), legacyVideoUrl = null,
                                neutralFrameUrl = null, heroName = "Проверка", generationRoute = GenerationRoute.OPENROUTER_DIRECT,
                                packReady = false, packBusy = false, packStatus = null, packMessage = null, generationError = null,
                                batchActions = emptyList(), batchId = null, onDelete = {}, onBack = {}, onRefresh = {}, onComplete = {},
                            )
                        }
                    }
                }
                fun findVisibleLabel(label: String) = findLabel(automation.rootInActiveWindow, label)
                waitFor("Archived clip card", 10_000) { findVisibleLabel(title) != null }
                var card = findVisibleLabel(title)
                while (card != null && !card.isClickable) card = card.parent
                assertTrue("Archived clip card should open", card?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                waitFor("Archived clip playback", 15_000) {
                    var playing = false
                    scenario.onActivity { playing = playingPlayer(it.window.decorView) != null }
                    playing
                }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                waitFor("Return to the gallery", 5_000) { findVisibleLabel("ИЗ ЧАТА") != null }
            }
            assertEquals(id, HeroLocalStore(app).chatAnimations(hero).single().id)
        } finally {
            folder.deleteRecursively()
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun playingPlayer(view: View): Player? = when (view) {
        is PlayerView -> view.player?.takeIf { it.isPlaying }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { playingPlayer(view.getChildAt(it)) }
        else -> null
    }

    private fun findLabel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
        for (index in 0 until node.childCount) {
            findLabel(node.getChild(index), label)?.let { return it }
        }
        return null
    }

    private fun waitFor(description: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue(description, condition())
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
