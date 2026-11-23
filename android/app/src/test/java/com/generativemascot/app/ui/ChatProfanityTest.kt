package com.generativemascot.app.ui

import android.content.Context
import com.generativemascot.app.data.CharacterCommunication
import com.generativemascot.app.data.CommunicationTrait
import com.generativemascot.app.data.HeroLocalStore
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
    @Test fun currentHeroProfileReachesEveryChatRequestWithoutReopeningTheConversation() = runBlocking {
        val store = HeroLocalStore(RuntimeEnvironment.getApplication())
        val prompts = mutableListOf<String>()
        val initial = CharacterCommunication(notes = "Говори как детектив")
            .withLevel(CommunicationTrait.BREVITY, 100)
            .withLevel(CommunicationTrait.COMPLEXITY, 0)
            .withLevel(CommunicationTrait.HUMOR, 0)
            .withLevel(CommunicationTrait.PROFANITY, 50)
            .withLevel(CommunicationTrait.DIRECTNESS, 100)
            .withLevel(CommunicationTrait.LOYALTY, 0)
        store.saveCommunication("rex", initial)
        val chat = DirectMascotChat("Рэкс", apiKey = "offline", requestClient = offline(prompts),
            communication = { store.communication("rex") })
        assertEquals("Понятно, продолжим.", chat.send("Что думаешь?").getString("reply"))
        for (trait in CommunicationTrait.entries) {
            assertTrue(prompts.last().contains("${trait.title} (${initial[trait]}/100)"))
        }
        assertTrue(prompts.last().contains(initial.notes))
        assertNull(store.communication("una"))

        store.saveCommunication("rex", initial.withLevel(CommunicationTrait.PROFANITY, 100))
        assertTrue(chat.send("А теперь?").getString("reply").startsWith("Бля,"))
        store.saveCommunication("rex", initial.withLevel(CommunicationTrait.PROFANITY, 0))
        assertEquals("Понятно, продолжим.", chat.send("Продолжай").getString("reply"))
        assertTrue(prompts.last().contains("Мат (0/100)"))
    }

    @Test fun insultLocksOnlyProfanityAndLoyaltyForThatHeroUntilExpiryOrApology() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val moods = context.getSharedPreferences(CHAT_MOOD_PREFERENCES, Context.MODE_PRIVATE)
        val prompts = mutableListOf<String>()
        val changes = mutableListOf<Boolean>()
        val insult = DirectMascotChat("Рэкс", apiKey = "offline", requestClient = offline(prompts, moodChange = "insult"),
            moodPreferences = moods, moodKey = "rex")
        insult.send("Ты ужасный помощник")

        val expires = moods.communicationLockExpiry("rex")
        assertTrue(expires > System.currentTimeMillis())
        assertEquals(expires, moods.communicationLockExpiry("rex", expires - 1))
        assertEquals(0L, moods.communicationLockExpiry("rex", expires))
        assertEquals("00:00:01", communicationLockCountdown(expires, expires - 1))
        assertNull(communicationLockCountdown(expires, expires))
        assertEquals(0L, moods.communicationLockExpiry("una"))

        val saved = CharacterCommunication().withLevel(CommunicationTrait.PROFANITY, 0)
        val draft = saved.withLevel(CommunicationTrait.PROFANITY, 100).withLevel(CommunicationTrait.LOYALTY, 0)
            .withLevel(CommunicationTrait.HUMOR, 0)
        val merged = draft.keepMoodLockedLevels(saved)
        assertEquals(0, merged[CommunicationTrait.PROFANITY])
        assertEquals(50, merged[CommunicationTrait.LOYALTY])
        assertEquals(0, merged[CommunicationTrait.HUMOR])

        val chat = DirectMascotChat("Рэкс", apiKey = "offline", requestClient = offline(prompts),
            moodPreferences = moods, moodKey = "rex", communication = { saved }, onProfanityModeChanged = { changes.add(it) })
        assertFalse(chat.setProfanityEnabled(true))
        chat.send("Включи мат")
        assertTrue(prompts.last().contains("Мат (0/100)"))
        assertTrue(prompts.last().contains("уровень мата временно заблокирован"))
        assertTrue(changes.isEmpty())

        val apology = DirectMascotChat("Рэкс", apiKey = "offline", requestClient = offline(prompts, moodChange = "apology"),
            moodPreferences = moods, moodKey = "rex")
        apology.send("Прости")
        assertEquals(0L, moods.communicationLockExpiry("rex"))
        assertTrue(chat.setProfanityEnabled(true))
        assertEquals(listOf(true), changes)
    }

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

    @Test fun characterMoodSurvivesRecreationAndSelectsACompatibleLocalReaction() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val moods = context.getSharedPreferences("character_mood_test", Context.MODE_PRIVATE)
        moods.edit().clear().commit()
        val prompts = mutableListOf<String>()
        fun chat(id: String, moodChange: String = "keep", reaction: String? = null) = DirectMascotChat(
            id, apiKey = "offline", requestClient = offline(prompts, moodChange, reaction),
            moodPreferences = moods, moodKey = id,
        )

        val angryReply = chat("green", "insult", "angry")
            .send("Ты дурак", availableReactions = setOf("angry"))
        assertEquals("angry", angryReply.getString("local_reaction"))
        val neutralReply = chat("green").send("Сколько будет два плюс два?", availableReactions = setOf("angry"))
        assertTrue(prompts.last().contains("Настроение к собеседнику: hurt"))
        assertEquals("angry", neutralReply.getString("local_reaction"))

        chat("emor").send("Привет")
        assertTrue(prompts.last().contains("Настроение к собеседнику: neutral"))
        chat("green", "apology").send("Извини")
        chat("green").send("Продолжим?")
        assertTrue(prompts.last().contains("Настроение к собеседнику: neutral"))
    }

    @Test fun completedChatTurnDrivesTheHomeTextAndAvailableClip() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val moods = context.getSharedPreferences("home_chat_mood_test", Context.MODE_PRIVATE)
        var change = "insult"
        fun chat(reaction: String) = DirectMascotChat(
            "Грин", tone = "дерзкий", apiKey = "offline",
            requestClient = offline(mutableListOf(), change, reaction),
            moodPreferences = moods, moodKey = "green",
        )
        chat("angry").send("Ты дурак", availableReactions = setOf("angry"))
        val anger = requireNotNull(moods.readHomeChatMood("green", System.currentTimeMillis()))
        assertEquals("angry", anger.availableAction(setOf("idle", "angry", "annoyed")))
        assertEquals("annoyed", anger.availableAction(setOf("idle", "annoyed")))
        assertNull(anger.availableAction(setOf("idle", "chat_happy")))
        assertTrue(anger.line(true, true, "annoyed").contains("раздражает"))
        assertTrue(anger.line(true, true).startsWith("Бля,"))
        assertFalse(anger.line(true, false).startsWith("Бля,"))
        assertFalse(anger.line(false, true).startsWith("Бля,"))
        assertNull(moods.readHomeChatMood("emor", System.currentTimeMillis()))

        change = "praise"
        chat("shy").send("Ты отлично справился", availableReactions = setOf("shy"))
        val praise = requireNotNull(moods.readHomeChatMood("green", System.currentTimeMillis()))
        assertEquals("shy", praise.availableAction(setOf("idle", "shy")))
        assertTrue(praise.line(true, true).contains("смутился"))
        assertTrue(praise.line(true, true, "chat_happy").contains("Спасибо за похвалу"))
        assertNull(moods.readHomeChatMood("green", praise.expires))
    }

    /** Intercepts the real request before networking; no key or provider call. */
    private fun offline(
        prompts: MutableList<String>,
        moodChange: String = "keep",
        localReaction: String? = null,
    ) = OkHttpClient.Builder().addInterceptor { chain ->
        val buffer = okio.Buffer()
        chain.request().body!!.writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        prompts.add(body.getJSONArray("messages").getJSONObject(0).getString("content"))
        val answer = JSONObject().put("reply_ru", "Понятно, продолжим.").put("reaction_en", "The character nods calmly.")
            .put("mood_ru", "спокойствие").put("calendar_event", JSONObject.NULL)
            .put("mood_change", moodChange).put("local_reaction", localReaction ?: JSONObject.NULL)
        val event = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("delta", JSONObject().put("content", answer.toString())).put("finish_reason", "stop")))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Offline fixture")
            .body(("data: $event\n\ndata: [DONE]\n\n").toResponseBody("text/event-stream".toMediaType())).build()
    }.build()
}
