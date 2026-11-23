package com.generativemascot.app.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatAngerInstrumentedTest {
    @Test fun repeatedAngerUsesAllThreeBundledPetyaClips() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val clips = app.assets.list("bundled-heroes/300392a5-3351-4add-9e22-7a21a56f4d1a/videos").orEmpty()
        val available = chatAngerReactions.filter { "$it.mp4" in clips }.toSet()
        assertEquals(chatAngerReactions.toSet(), available)
        val chat = DirectMascotChat("Петя", apiKey = "offline", requestClient = offlineInsultResponse())
        val reactions = List(6) {
            val reply = chat.send("Тестовая реплика", availableReactions = available)
            assertFalse(reply.getBoolean("custom_animation"))
            reply.getString("local_reaction")
        }
        assertEquals(available, reactions.take(3).toSet())
        assertEquals(available, reactions.drop(3).toSet())
        reactions.zipWithNext().forEach { (first, second) -> assertNotEquals(first, second) }
    }

    @Test fun angerUsesOnlyClipsAvailableForTheSelectedHero() = runBlocking {
        val chat = DirectMascotChat("Грин", apiKey = "offline", requestClient = offlineInsultResponse())
        val reply = chat.send("Тестовая реплика", availableReactions = setOf("angry", "sad"))
        assertEquals("angry", reply.getString("local_reaction"))
        val withoutAnger = chat.send("Тестовая реплика", availableReactions = setOf("sad"))
        assertEquals("sad", withoutAnger.getString("local_reaction"))
    }

    @Test fun persistentMoodSelectsCompatibleReactionsUntilAPositiveEventOrApology() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = app.getSharedPreferences("chat_mood_test", Context.MODE_PRIVATE)
        val hero = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val clips = setOf("angry", "sad", "excited", "joyful", "chat_happy", "affection", "worried")
        suspend fun react(change: String, preferred: String? = null, id: String = hero): String? =
            DirectMascotChat("Рико", apiKey = "offline", moodKey = id, moodPreferences = preferences,
                requestClient = offlineInsultResponse(change, preferred, "local"))
                .send("Тестовая реплика", availableReactions = clips).optString("local_reaction").ifBlank { null }
        try {
            assertEquals("angry", react("insult", "angry"))
            val hurt = requireNotNull(preferences.readPersistentChatMood(hero, System.currentTimeMillis()))
            assertEquals(listOf("angry", "sad"), hurt.compatibleActions(clips))
            assertEquals("angry", react("keep", "excited"))
            assertEquals(hurt, preferences.readHomeChatMood(hero, System.currentTimeMillis()))
            assertNull(react("keep", id = other))
            assertEquals("joyful", react("good_news", "sad"))
            val happy = requireNotNull(preferences.readPersistentChatMood(hero, System.currentTimeMillis()))
            assertEquals(listOf("joyful", "excited", "chat_happy", "affection"), happy.compatibleActions(clips))
            assertEquals("joyful", react("keep"))
            assertEquals("sad", react("upset", "joyful"))
            assertEquals("sad", react("keep", "excited"))
            assertEquals("affection", react("apology"))
            assertNull(preferences.readPersistentChatMood(hero, System.currentTimeMillis()))
            assertNull(react("keep"))
            assertNull(preferences.readPersistentChatMood(hero, happy.expires))
        } finally {
            val editor = preferences.edit()
            for (id in listOf(hero, other)) preferences.all.keys.filter { it == id || it.startsWith("$id.") }
                .forEach(editor::remove)
            editor.apply()
        }
    }

    private fun offlineInsultResponse(
        change: String = "insult", preferred: String? = "angry", mode: String = "custom",
    ) = OkHttpClient.Builder().addInterceptor { chain ->
        val content = JSONObject().put("reply_ru", "Давай общаться уважительно.")
            .put("reaction_en", "The character looks irritated.")
            .put("animation_mode", mode).put("mood_change", change).put("local_reaction", preferred ?: JSONObject.NULL)
        val choice = JSONObject().put("delta", JSONObject().put("content", content.toString())).put("finish_reason", "stop")
        val event = JSONObject().put("choices", JSONArray().put(choice))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Offline fixture")
            .body("data: $event\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
    }.build()
}
