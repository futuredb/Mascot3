package com.generativemascot.app.ui

import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.generativemascot.app.data.CharacterCommunication
import com.generativemascot.app.data.GenerationRoute
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Pipe
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ReleaseUiRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun chatPackIsTheFirstActionInTheCountSheet() {
        var requests = 0
        var standardCount = 0
        compose.setContent {
            MascotTheme {
                AnimationLibraryScreen(
                    mascotId = "release_qa_preview",
                    animations = emptyMap(),
                    videoUrls = emptyMap(),
                    legacyVideoUrl = null,
                    neutralFrameUrl = null,
                    heroName = "Проверка",
                    generationRoute = GenerationRoute.OPENROUTER_DIRECT,
                    packReady = false,
                    packBusy = false,
                    packStatus = null,
                    packMessage = null,
                    generationError = null,
                    batchActions = emptyList(),
                    batchId = null,
                    onDelete = {},
                    onBack = {},
                    onRefresh = {},
                    onComplete = { standardCount = it },
                    onGenerateChatPack = { requests++ },
                )
            }
        }
        val chatPack = compose.onNodeWithText("Создать все для чата", substring = true, ignoreCase = true)
        chatPack.assertDoesNotExist()
        compose.onNodeWithText("ОЖИВИТЬ ПЕРСОНАЖА").performClick()
        compose.onNodeWithText("Сколько анимаций создать?").assertIsDisplayed()
        val standardPack = compose.onNodeWithText("Создать · 4").assertIsDisplayed()
        assertTrue(chatPack.fetchSemanticsNode().boundsInRoot.bottom < standardPack.fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("По умолчанию", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Все ·", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Генерация платная", substring = true).assertDoesNotExist()
        chatPack.assertIsDisplayed().performClick()
        compose.onNodeWithText("Все анимации для чата").assertIsDisplayed()
        compose.onNodeWithText("Генерация платная", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText("Назад").performClick()
        compose.onNodeWithText("Сколько анимаций создать?").assertIsDisplayed()
        chatPack.performClick()
        compose.onNodeWithText("Создать ·", substring = true).performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("ОЖИВИТЬ ПЕРСОНАЖА").performClick()
        compose.onNodeWithText("Создать · 4").performClick()
        compose.runOnIdle {
            assertEquals(4, standardCount)
            assertEquals(1, requests)
        }
    }

    @Test fun longStreamKeepsTheLatestReplyControlsVisibleWhenItFinishes() = verifyStream(reviseFinalText = false)

    @Test fun revisedFinalReplyKeepsAlreadyTypedTextVisible() = verifyStream(reviseFinalText = true)

    private fun verifyStream(reviseFinalText: Boolean) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val hero = "release_qa_" + UUID.randomUUID()
        val setup = app.getSharedPreferences("mascot_chat_interaction", Context.MODE_PRIVATE)
        setup.edit().putString(hero, "request_guidance").commit()
        val pipe = Pipe(8_192)
        val sink = pipe.sink.buffer()
        val source = pipe.source.buffer()
        val chat = DirectMascotChat("Проверка", apiKey = "offline", initialProfanityMode = reviseFinalText,
            requestClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Offline fixture")
                    .body(object : ResponseBody() {
                        override fun contentType() = "text/event-stream".toMediaType()
                        override fun contentLength() = -1L
                        override fun source() = source
                    }).build()
            }.build())
        fun chunk(text: String, finished: Boolean = false) {
            val choice = JSONObject().put("delta", JSONObject().put("content", text))
            if (finished) choice.put("finish_reason", "stop")
            sink.writeUtf8("data: " + JSONObject().put("choices", JSONArray().put(choice)) + "\n\n").flush()
        }
        try {
            compose.runOnUiThread {
                WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
                compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            compose.setContent {
                MascotTheme {
                    MascotChatScreen(
                        mascotId = hero, heroName = "Проверка", previewUrl = null, animationVideoUrl = null,
                        animationFrames = emptyList(), animationFps = 24, animationVideoUrls = emptyMap(),
                        neutralFrameUrl = null, portraitVisible = false, chatOverride = chat, onBack = {},
                    )
                }
            }
            compose.onNode(hasSetTextAction()).performTextInput("Проверка прокрутки")
            compose.onNodeWithContentDescription("Отправить сообщение").performClick()
            compose.onNodeWithText("Думает…").assertIsDisplayed()
            chunk("""{"reply_ru":"Начало ответа. """)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Начало ответа.", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(compose.onAllNodesWithContentDescription("Скопировать ответ").fetchSemanticsNodes().isEmpty())
            val lines = if (reviseFinalText) 5 else 32
            chunk("Длинная строка проверяет закреплённую прокрутку. ".repeat(lines) + "Конец ответа.\"")
            fun replyEndExists() = compose.onAllNodesWithText("Конец ответа.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
            if (reviseFinalText) {
                // Keep the stream open until the complete draft is on screen.
                compose.waitUntil(20_000) { replyEndExists() }
            }
            chunk(""","animation_mode":"local","mood_change":"keep"}""", finished = true)
            sink.writeUtf8("data: [DONE]\n\n").flush()
            val copyReply = compose.onNodeWithContentDescription("Скопировать ответ")
            compose.waitUntil(20_000) {
                if (reviseFinalText) assertTrue("The displayed reply lost its final line during replacement",
                    replyEndExists())
                copyReply.isDisplayed()
            }
            copyReply.assertIsDisplayed()
            compose.onNodeWithContentDescription("Поделиться ответом").assertIsDisplayed()
            val saved = ChatHistoryStore(app).read(hero)
            assertTrue(saved.last().text.endsWith("Конец ответа."))
            if (reviseFinalText) assertTrue(saved.last().text.startsWith("Бля, начало ответа."))
            assertEquals(2, saved.size)
        } finally {
            sink.close()
            setup.edit().remove(hero).apply()
            app.getSharedPreferences("mascot_chat_history", Context.MODE_PRIVATE).edit().remove(hero).apply()
        }
    }

    @Test fun switchingCharacterKeepsTheSettingsLayoutInPlace() {
        val first = HeroLibraryItem("settings-short", "Кай")
        val second = HeroLibraryItem("settings-long", "Сирин с очень длинным именем")
        val heroes = listOf(first, second)
        val selected = mutableStateOf(first)
        compose.setContent {
            MascotTheme {
                val hero = selected.value
                CharacterCommunicationSettings(
                    heroId = hero.id, name = requireNotNull(hero.name), portrait = null,
                    initial = CharacterCommunication(), heroes = heroes, stripState = rememberLazyListState(),
                    onClose = {}, onSave = { true }, onRename = {}, onOpenAnimations = {}, onNew = {},
                    onSelect = { id -> selected.value = heroes.first { it.id == id } },
                )
            }
        }
        val rename = compose.onNodeWithContentDescription("Изменить имя")
        val style = compose.onNodeWithText("Стиль общения")
        val originalRename = rename.fetchSemanticsNode().boundsInRoot
        val originalStyle = style.fetchSemanticsNode().boundsInRoot
        compose.onNode(hasClickAction() and hasContentDescription(requireNotNull(second.name))).performClick()
        compose.onNodeWithText(requireNotNull(second.name).uppercase()).assertIsDisplayed()
        assertEquals(originalRename, rename.fetchSemanticsNode().boundsInRoot)
        assertEquals(originalStyle, style.fetchSemanticsNode().boundsInRoot)
        compose.onNode(hasClickAction() and hasContentDescription(requireNotNull(first.name))).performClick()
        compose.onNodeWithText(requireNotNull(first.name).uppercase()).assertIsDisplayed()
        assertEquals(originalStyle, style.fetchSemanticsNode().boundsInRoot)
    }
}
