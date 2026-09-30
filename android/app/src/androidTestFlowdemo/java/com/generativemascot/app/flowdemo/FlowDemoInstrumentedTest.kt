package com.generativemascot.app.flowdemo

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.BuildConfig
import com.generativemascot.app.ui.GenerationScene
import com.generativemascot.app.ui.AppUiState
import com.generativemascot.app.ui.HeroLibraryItem
import com.generativemascot.app.ui.KnockHeroScreen
import com.generativemascot.app.ui.MascotTheme
import com.generativemascot.app.ui.showsHeroHome
import com.generativemascot.app.ui.withGreetingStarted
import com.generativemascot.app.data.MascotDto
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FlowDemoInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<FlowDemoActivity>()

    @Test fun oneShotGreetingNotifiesResolverAtTheActualVideoBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val video = java.io.File(context.cacheDir, "one-shot-boundary-test.mp4")
        context.assets.open("bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/videos/greeting.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        val action = mutableStateOf("greeting")
        val finished = mutableListOf<String>()
        val visible = mutableListOf<String>()
        compose.runOnUiThread {
            compose.activity.setContent {
                MascotTheme {
                    com.generativemascot.app.ui.SoraMascotVideo(
                        videoUrls = mapOf("greeting" to video.toURI().toString(), "idle" to video.toURI().toString()),
                        modifier = Modifier.fillMaxSize(),
                        action = action.value,
                        onAnimationVisible = { visible += it },
                        onOneShotFinished = { finished += it; action.value = "idle" })
                }
            }
        }
        compose.waitUntil(20_000) { "idle" in visible }
        compose.runOnIdle { assertEquals(listOf("greeting"), finished); assertEquals("greeting", visible.first()) }
    }

    @Test fun productionGesturesAreForwardedToSharedResolverOnlyOnRelease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "resolver-gesture-test.png")
        context.assets.open("bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/base.png").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val video = java.io.File(context.cacheDir, "resolver-gesture-test.mp4")
        context.assets.open("bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/videos/greeting.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        val events = mutableListOf<com.generativemascot.app.state.StateEvent>()
        compose.runOnUiThread {
            compose.activity.setContent {
                MascotTheme {
                    KnockHeroScreen(generating = false, previewUrl = file.toURI().toString(),
                        error = null, accepted = true, mascotId = "gesture-fixture", heroName = "Марс",
                        animationVideoUrls = mapOf("greeting" to video.toURI().toString()),
                        neutralVideoFrameUrl = file.toURI().toString(),
                        onStateGesture = { events += it },
                        onKnockComplete = { error("No paid generation in gesture test") },
                        onAccept = {}, onPoll = {}, acceptEnabled = false)
                }
            }
        }
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        compose.onNodeWithTag("hero-interaction").performTouchInput { down(center); advanceEventTime(600); up() }
        compose.runOnIdle { assertEquals(listOf(com.generativemascot.app.state.StateEvent.HOLD), events) }
        compose.onNodeWithTag("hero-interaction").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(com.generativemascot.app.state.StateEvent.TAP, events.last()) }
        compose.onNodeWithTag("hero-interaction").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(com.generativemascot.app.state.StateEvent.JOY, events.last()); assertEquals(3, events.size) }
    }

    @Test fun characterCarouselHidesTheWholeQuoteAndRestoresItAfterSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "carousel-test.png")
        context.assets.open("bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/base.png").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val video = java.io.File(context.cacheDir, "carousel-test-greeting.mp4")
        context.assets.open("bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/videos/greeting.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        val heroes = listOf(HeroLibraryItem("fixture-a", "Аким", baseStill = file.toURI().toString()),
            HeroLibraryItem("fixture-b", "Марс", baseStill = file.toURI().toString()))
        val selected = mutableStateOf("fixture-a")
        compose.runOnUiThread {
            compose.activity.setContent {
                MascotTheme {
                    val hero = heroes.first { it.id == selected.value }
                    KnockHeroScreen(generating = false, previewUrl = hero.baseStill, error = null,
                        accepted = true, mascotId = hero.id, heroName = hero.name, heroLibrary = heroes,
                        animationVideoUrls = mapOf("greeting" to video.toURI().toString()),
                        neutralVideoFrameUrl = hero.baseStill,
                        onKnockComplete = { error("No paid generation in carousel test") },
                        onSelectHero = { selected.value = it }, onAccept = {}, onPoll = {}, acceptEnabled = false)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onNodeWithTag("hero-quote").isDisplayed() }
        compose.onNodeWithTag("hero-quote").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Открыть моих героев") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Открыть моих героев").performClick()
        compose.onNodeWithTag("hero-quote").assertDoesNotExist()
        compose.waitUntil(5_000) { compose.onNodeWithTag("hero-picker-carousel").isDisplayed() }
        compose.onNodeWithTag("hero-picker-carousel").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("hero-quote").assertDoesNotExist()
        compose.onNodeWithContentDescription("Выбрать героя").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithTag("hero-quote").isDisplayed() }
        compose.onNodeWithTag("hero-quote").assertIsDisplayed()
    }

    /** Production state transitions/rendering; fake callbacks and a bundled still only. */
    @Test fun productionAcceptanceImmediatelyShowsHomeAndLoader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "production-flow-test.png")
        context.assets.open("bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/base.png").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val state = mutableStateOf(AppUiState(ready = true, mascot = MascotDto(
            id = "fixture", name = "Аким", status = "AWAITING_ACCEPTANCE", previewUrl = file.toURI().toString())))
        var approvals = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MascotTheme {
                    val current = state.value
                    if (current.showsHeroHome) {
                        KnockHeroScreen(generating = current.creating, previewUrl = current.mascot?.previewUrl,
                            error = current.error, accepted = true,
                            heroLoading = current.greetingPending && current.creating,
                            mascotId = current.mascot?.id, heroName = current.mascot?.name,
                            onKnockComplete = { error("Home must not request another character") },
                            onAccept = {}, onPoll = {}, acceptEnabled = false)
                    } else {
                        GenerationScene(generating = false, previewUrl = current.mascot?.previewUrl,
                            error = null, heroName = current.mascot?.name,
                            onKnockComplete = {}, onAccept = { approvals++; state.value = current.withGreetingStarted() },
                            onPoll = {}, acceptEnabled = true)
                    }
                }
            }
        }
        compose.onNodeWithText("ОСТАВИМ").performClick()
        compose.onNodeWithTag("home-animation-loader").assertIsDisplayed()
        compose.onNodeWithText("АКИМ").assertIsDisplayed()
        compose.onNodeWithText("ОСТАВИМ").assertDoesNotExist()
        compose.onNodeWithText("ПОСТУЧИ").assertDoesNotExist()
        compose.onNodeWithText("ТАМ\nКТО-ТО\nЕСТЬ").assertDoesNotExist()
        compose.onNodeWithContentDescription("Открыть моих героев").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Создать нового героя").assertIsNotEnabled()
        // Even before a remote preview is downloaded, home must retain its round loader.
        compose.runOnIdle { state.value = state.value.copy(mascot = state.value.mascot?.copy(previewUrl = null)) }
        compose.onNodeWithTag("home-animation-loader").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, approvals)
            state.value = state.value.copy(creating = false, busy = false, greetingPending = false,
                mascot = state.value.mascot?.copy(previewUrl = file.toURI().toString()))
        }
        compose.onNodeWithTag("home-animation-loader").assertDoesNotExist()
        compose.onNodeWithText("АКИМ").assertIsDisplayed()
    }

    @Test fun interruptedGreetingOffersExplicitResumeOrSkipWithoutBuyingAnotherHero() {
        val interrupted = mutableStateOf(true)
        var resumes = 0
        var skips = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MascotTheme {
                    KnockHeroScreen(generating = !interrupted.value, previewUrl = null,
                        error = "Герой сохранён", accepted = true, heroLoading = !interrupted.value,
                        greetingInterrupted = interrupted.value,
                        onResumeGreeting = { resumes++; interrupted.value = false },
                        onDeferGreeting = { skips++; interrupted.value = false },
                        mascotId = "fixture", heroName = "Марс",
                        onKnockComplete = { error("No new hero may be requested") },
                        onAccept = {}, onPoll = {}, acceptEnabled = false)
                }
            }
        }
        compose.onNodeWithText("Приветствие прервалось").assertIsDisplayed()
        compose.onNodeWithText("Продолжить").performClick()
        compose.onNodeWithTag("home-animation-loader").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, resumes); assertEquals(0, skips); interrupted.value = true }
        compose.onNodeWithText("Пока без приветствия").performClick()
        compose.runOnIdle { assertEquals(1, resumes); assertEquals(1, skips) }
    }

    @Test fun thirdKnockImmediatelyShowsOpaqueWaitingEvenBeforeParentResponds() {
        var requests = 0
        var sounds = 0
        val error = mutableStateOf<String?>(null)
        compose.runOnUiThread {
            compose.activity.setContent {
                GenerationScene(generating = false, previewUrl = null, error = error.value,
                    onKnockComplete = { requests++; error.value = null }, onAccept = {}, onPoll = {},
                    acceptEnabled = false, onGenerationStartedSound = { sounds++ })
            }
        }
        repeat(2) { compose.onRoot().performTouchInput { click(center) } }
        compose.runOnIdle { assertEquals(0, sounds) }
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("ПОСТУЧИ").assertDoesNotExist()
        compose.onNodeWithText("ТАМ\nКТО-ТО\nЕСТЬ").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(getLayout(layouts))
                assertEquals(1f, layouts.single().layoutInput.style.color.alpha, 0f)
            }
        repeat(4) {
            compose.onRoot().performTouchInput { click(center) }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.runOnIdle { assertEquals(1, requests); assertEquals(1, sounds) }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { error.value = "Тестовая ошибка, расходов нет" }
        compose.onNodeWithText("ПОСТУЧИ").assertExists()
        repeat(2) { compose.onRoot().performTouchInput { click(center) } }
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("ПОСТУЧИ").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, requests); assertEquals(2, sounds) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun offlineFullCreationGreetingAndReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.mascot3.flowdemo", context.packageName)
        assertEquals(android.app.Application::class.java, context.applicationContext.javaClass)
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.INTERNET))
        assertEquals("", BuildConfig.OPENROUTER_API_KEY)
        assertEquals("", BuildConfig.MASCOT3_CLIENT_TOKEN)
        fun waitFor(text: String) {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }
        waitFor("ПОСТУЧИ")
        repeat(3) { compose.onRoot().performTouchInput { click(center) } }
        waitFor("Создаём внешность героя…")
        waitFor("ОСТАВИМ")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("ОСТАВИМ").performClick()
        compose.mainClock.advanceTimeBy(100)
        // Home is present immediately, but the actual character/player is not mounted yet.
        compose.onNodeWithContentDescription("Эмоции героя").assertExists()
        compose.onNodeWithText("УНА").assertIsDisplayed()
        compose.onNodeWithText("ГЕРОЙ").assertDoesNotExist()
        compose.onNodeWithTag("home-animation-loader").assertIsDisplayed()
        compose.onNodeWithText("Готовим приветствие — одну анимацию…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Герой").assertDoesNotExist()
        fun players(): List<androidx.media3.ui.PlayerView> {
            val result = mutableListOf<androidx.media3.ui.PlayerView>()
            fun visit(view: android.view.View) {
                if (view is androidx.media3.ui.PlayerView) result += view
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            visit(compose.activity.findViewById(android.R.id.content))
            return result
        }
        compose.runOnUiThread { assertTrue("No hidden player beneath loader", players().isEmpty()) }
        compose.onNodeWithText("ПОСТУЧИ").assertDoesNotExist()
        compose.onNodeWithText("ТАМ\nКТО-ТО\nЕСТЬ").assertDoesNotExist()
        compose.onNodeWithText("ОСТАВИМ").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("home-animation-loader").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("home-animation-loader").fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(5_000) {
            var playing = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                playing = players().any { it.player?.isPlaying == true }
            }
            playing
        }
        compose.onNodeWithContentDescription("Эмоции героя").performClick()
        waitFor("Готово 1 из 17 · осталось 16")
        waitFor("Оживить персонажа")
        compose.onNodeWithText("Выбери настроение").assertDoesNotExist()
        compose.onNodeWithText("Выбери, сколько анимаций создать", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Они будут появляться здесь по мере готовности", substring = true).assertDoesNotExist()
        compose.onNodeWithText("УНА").assertIsDisplayed()
        compose.onNodeWithText("ДЕЙСТВИЯ").assertDoesNotExist()
        compose.onNodeWithText("Готово 1 из 17 · осталось 16").assertExists()
        compose.onNodeWithText("Оживить персонажа").performClick()
        waitFor("Сколько анимаций создать?")
        compose.onNodeWithText("Создать · 4").performClick()
        waitFor("Готово 5 из 17 · осталось 12")
        // Renaming is local in this offline app; every screen must follow the saved name.
        for (name in listOf("Марс", "Аким")) {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Изменить имя").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Изменить имя").performClick()
            compose.onNodeWithTag("hero-name-input").assertIsFocused()
            compose.onNodeWithTag("hero-name-input").performTextReplacement("   ")
            compose.onNodeWithContentDescription("Сохранить имя").assertIsNotEnabled()
            compose.onNodeWithTag("hero-name-input").performTextReplacement(name)
            if (name == "Марс") {
                compose.onNodeWithTag("hero-name-input").performImeAction()
            } else {
                compose.onNodeWithContentDescription("Сохранить имя").performClick()
            }
            compose.onNodeWithTag("hero-name-input").assertDoesNotExist()
            compose.onNodeWithText(name.uppercase()).assertIsDisplayed()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText(name.uppercase()).assertIsDisplayed()
            compose.onNodeWithText("ГЕРОЙ").assertDoesNotExist()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription("Эмоции героя") and isEnabled())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Эмоции героя").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Изменить имя").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(name.uppercase()).assertIsDisplayed()
            compose.onNodeWithText("ДЕЙСТВИЯ").assertDoesNotExist()
        }
        compose.onNodeWithText("ДЕМО · 0 ₽ · начать заново").performClick()
        waitFor("ПОСТУЧИ")
    }
}
