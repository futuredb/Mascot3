package com.generativemascot.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Production rendering with local fixtures and fake callbacks: no ViewModel, API or paid worker. */
class GenerationSceneInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun sampleStill(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "generation-scene-test.png")
        context.assets.open("bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/base.png").use { source ->
            file.outputStream().use { source.copyTo(it) }
        }
        return file.toURI().toString()
    }

    @Test fun threeKnocksOpenWaitingAndDoNotRestartOnFurtherTaps() {
        var requests = 0
        val generating = mutableStateOf(false)
        compose.setContent {
            GenerationScene(generating = generating.value, previewUrl = null, error = null,
                onKnockComplete = { requests++; generating.value = true }, onAccept = {}, onPoll = {},
                acceptEnabled = false, generationLabel = "Создаём внешность героя…")
        }
        compose.onNodeWithText("ПОСТУЧИ").assertExists()
        repeat(3) { compose.onRoot().performTouchInput { click(center) } }
        compose.onNodeWithText("ТАМ\nКТО-ТО\nЕСТЬ").assertExists()
        repeat(4) { compose.onRoot().performTouchInput { click(center) } }
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun approvalCannotBuyAnotherHeroByTappingPreviewAndAcceptanceShowsGreetingProgress() {
        val still = sampleStill()
        var baseRequests = 0
        var approvals = 0
        val accepted = mutableStateOf(false)
        compose.setContent {
            GenerationScene(generating = accepted.value, previewUrl = still, error = null, heroName = "Тест",
                onKnockComplete = { baseRequests++ }, onAccept = { approvals++; accepted.value = true },
                onPoll = {}, acceptEnabled = !accepted.value, greetingStage = accepted.value,
                generationLabel = "Готовим приветствие — одну анимацию…")
        }
        repeat(4) { compose.onRoot().performTouchInput { click(center) } }
        compose.runOnIdle { assertEquals(0, baseRequests) }
        compose.onNodeWithText("ОСТАВИМ").performClick()
        compose.onNodeWithText("Готовим приветствие — одну анимацию…").assertExists()
        compose.onNodeWithText("ОСТАВИМ").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, approvals) }
    }

    @Test fun interruptedGreetingOffersResumeNotAnotherPaidHero() {
        val still = sampleStill()
        compose.setContent {
            GenerationScene(generating = false, previewUrl = still, error = "Готовое сохранено",
                onKnockComplete = {}, onAccept = {}, onPoll = {}, acceptEnabled = true,
                greetingStage = true)
        }
        compose.onNodeWithText("ПРОДОЛЖИТЬ").assertExists()
        compose.onNodeWithContentDescription("Другой герой").assertIsNotEnabled()
        compose.onNodeWithText("Готовое сохранено").assertExists()
    }

    @OptIn(UnstableApi::class)
    @Test fun oneSavedGreetingPlaysOnceThenRestsAndCanBeReplayedWithoutGeneration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun fixture(name: String): String {
            val file = File(context.cacheDir, "generation-video-test-$name")
            context.assets.open("bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/videos/$name").use { source ->
                file.outputStream().use { source.copyTo(it) }
            }
            return file.toURI().toString()
        }
        val video = fixture("greeting.mp4")
        val neutral = fixture("neutral.png")
        val interaction = mutableStateOf(PuppetInteraction())
        compose.setContent {
            SoraMascotVideo(videoUrls = mapOf("greeting" to video), neutralFrameUrl = neutral,
                action = "idle", interaction = interaction.value, modifier = Modifier.fillMaxSize())
        }
        fun players(): List<PlayerView> {
            val result = mutableListOf<PlayerView>()
            fun visit(view: View) {
                if (view is PlayerView) result += view
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            visit(compose.activity.findViewById(android.R.id.content))
            return result
        }
        fun finished(): Boolean {
            var done = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                done = players().any { it.player?.playbackState == Player.STATE_ENDED } &&
                    players().none { it.player?.isPlaying == true }
            }
            return done
        }
        compose.waitUntil(20_000) { finished() }
        android.os.SystemClock.sleep(1_000)
        org.junit.Assert.assertTrue("Greeting must not restart as an idle loop", finished())
        compose.runOnIdle { interaction.value = PuppetInteraction(actionName = "greeting", actionSerial = 1) }
        compose.waitUntil(5_000) {
            var playing = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                playing = players().any { it.player?.isPlaying == true }
            }
            playing
        }
    }
}
