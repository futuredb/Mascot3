package com.generativemascot.app.ui

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ChatProfanityTest {
    @Test fun recognizesRequestsWithoutRequiringUserToSwear() {
        listOf("Включи мат", "Общайся со мной с матом", "Давай с матом", "Матерись свободно",
            "Хочу, чтобы ты матерился", "Давай материться", "Можно теперь общаться с матом?",
            "Включи режим мата", "Используй нецензурную лексику", "Можешь материться?",
            "Начни материться", "Я прошу тебя материться", "Можешь ругаться матом").forEach {
            assertEquals(it, true, requestedChatProfanity(it))
        }
    }

    @Test fun enabledStyleIsEnforcedWhenModelOnlyAcknowledgesOrIgnoresThePrompt() {
        assertEquals("Бля, окей, буду.", enforceChatProfanity("Окей, буду.", true))
        assertEquals("Да, блядь, буду.", enforceChatProfanity("Да, блядь, буду.", true))
        assertEquals("Бля, понял.", enforceChatProfanity("Бля, понял.", true))
        assertEquals("Окей, буду.", enforceChatProfanity("Окей, буду.", false))
    }

    @Test fun settingsToggleChangesTheSamePersistentChatMode() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("mascot_chat_language", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ChatProfanityStore(context)
        val prompts = mutableListOf<String>()
        val chat = DirectMascotChat("Аким", apiKey = "offline", requestClient = offline(prompts),
            initialProfanityMode = store.read("akim"), onProfanityModeChanged = { store.write("akim", it) })

        chat.setProfanityEnabled(true)
        assertEquals(true, store.read("akim"))
        assertTrue(chat.send("Окей").getString("reply").startsWith("Бля,"))
        chat.setProfanityEnabled(false)
        assertEquals("Понятно, продолжим.", chat.send("Окей").getString("reply"))
        assertEquals(false, store.read("akim"))
    }

    @Test fun ordinarySwearingQuestionsAndQuotesAreNotConsent() {
        listOf("Бля, ничего не работает", "У тебя есть доступ к мату?", "Он написал «включи мат»",
            "Переведи включи мат", "Что значит включи мат?", "`матерись`", "> Включи мат",
            "Не включи мат").forEach { assertNull(it, requestedChatProfanity(it)) }
    }

    @Test fun explicitCancellationWinsAndLastCommandDeterminesPreference() {
        listOf("Выключи мат", "Давай без мата", "Не матерись", "Не используй мат",
            "Перестань материться", "Без мата", "Не общайся со мной с матом", "Отключи нецензурную лексику",
            "Включи мат. Нет, выключи мат.").forEach { assertEquals(it, false, requestedChatProfanity(it)) }
        assertEquals(true, requestedChatProfanity("Выключи мат. Нет, включи мат."))
    }

    @Test fun modeSurvivesContextWindowAndNeutralMessagesThenTurnsOff() = runBlocking {
        val prompts = mutableListOf<String>()
        val saved = mutableListOf<Boolean>()
        val chat = DirectMascotChat("Аким", apiKey = "offline", requestClient = offline(prompts), onProfanityModeChanged = saved::add)
        val firstReply = chat.send("Включи мат").getString("reply")
        assertTrue(firstReply.startsWith("Бля,"))
        repeat(12) { chat.send("Объясни это проще") }
        assertEquals(listOf(true), saved)
        assertTrue(prompts.last().contains("ВКЛЮЧЁН постоянный режим"))
        assertTrue(prompts.last().contains("В КАЖДОМ ответе используй минимум одно"))
        assertTrue(prompts.last().contains("и не жди, пока пользователь снова выругается"))
        chat.send("Давай без мата")
        chat.send("Расскажи ещё")
        assertEquals(listOf(true, false), saved)
        assertTrue(prompts.last().contains("Режим общения с матом ВЫКЛЮЧЕН"))
    }

    @Test fun savedModeIsIsolatedByHeroAndSurvivesServiceRecreation() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("mascot_chat_language", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ChatProfanityStore(context)
        val prompts = mutableListOf<String>()
        val a = DirectMascotChat("Аким", apiKey = "offline", requestClient = offline(prompts),
            initialProfanityMode = store.read("akim"), onProfanityModeChanged = { store.write("akim", it) })
        a.send("Включи мат")
        assertEquals(true, ChatProfanityStore(context).read("akim"))
        assertNull(store.read("mars"))
        DirectMascotChat("Аким", apiKey = "offline", requestClient = offline(prompts), initialProfanityMode = store.read("akim"))
            .send("Привет, как дела?")
        assertTrue(prompts.last().contains("ВКЛЮЧЁН постоянный режим"))
        DirectMascotChat("Марс", apiKey = "offline", requestClient = offline(prompts), initialProfanityMode = store.read("mars"))
            .send("Бля, сломалось")
        assertTrue(prompts.last().contains("Режим общения с матом ВЫКЛЮЧЕН"))
    }

    @Test fun existingConversationRestoresPreferenceButSavedCancellationHasPriority() = runBlocking {
        val history = listOf(ChatMessage(true, "Включи мат"), ChatMessage(false, "Хорошо")) +
            List(30) { ChatMessage(it % 2 == 0, "Обычное сообщение") }
        val prompts = mutableListOf<String>()
        DirectMascotChat("Аким", initialMessages = history, apiKey = "offline", requestClient = offline(prompts)).send("Продолжим")
        assertTrue(prompts.last().contains("ВКЛЮЧЁН постоянный режим"))
        DirectMascotChat("Аким", initialMessages = history, apiKey = "offline", initialProfanityMode = false,
            requestClient = offline(prompts)).send("Продолжим")
        assertTrue(prompts.last().contains("Режим общения с матом ВЫКЛЮЧЕН"))
    }

    /** Intercepts the real request before networking; no key or provider call. */
    private fun offline(prompts: MutableList<String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val buffer = okio.Buffer()
        chain.request().body!!.writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        prompts.add(body.getJSONArray("messages").getJSONObject(0).getString("content"))
        val answer = JSONObject().put("reply_ru", "Понятно, продолжим.").put("reaction_en", "The character nods calmly.")
            .put("mood_ru", "спокойствие").put("calendar_event", JSONObject.NULL)
        val event = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("delta", JSONObject().put("content", answer.toString())).put("finish_reason", "stop")))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Offline fixture")
            .body(("data: $event\n\ndata: [DONE]\n\n").toResponseBody("text/event-stream".toMediaType())).build()
    }.build()
}
