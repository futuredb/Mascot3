package com.generativemascot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAnimationRoutingTest {
    @Test fun recognizableMessagesGetDifferentSavedReactions() {
        val expected = mapOf(
            "Привет!" to "greeting",
            "У меня получилось!" to "proud",
            "Мне тяжело сегодня" to "worried",
            "Ха-ха, вот это история" to "laughing",
            "Спасибо!" to "chat_happy",
            "Объясни, почему это работает?" to "curious",
            "Извини, я погорячился" to "affection",
            "Потанцуй" to "dancing",
        )
        expected.forEach { (message, reaction) ->
            assertEquals(message, reaction, savedMessageReaction(message, false, expected.values.toSet()))
        }
        assertTrue("encouraging" in chatMoodActions("upset"))
    }

    @Test fun fallbackRequiresASavedMatchingClip() {
        assertEquals("encouraging", savedMessageReaction("Мне грустно", false, setOf("encouraging", "excited")))
        assertEquals("surprised", savedMessageReaction("", true, setOf("surprised")))
        assertNull(savedMessageReaction("Потанцуй", false, setOf("sad", "idle")))
        assertNull(savedMessageReaction("Я купил хлеб", false, setOf("chat_happy", "curious")))
    }
}
