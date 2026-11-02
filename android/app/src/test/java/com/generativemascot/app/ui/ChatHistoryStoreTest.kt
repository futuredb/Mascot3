package com.generativemascot.app.ui

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ChatHistoryStoreTest {
    @Test fun conversationsSurviveStoreRecreationAndRemainSeparateForEachHero() {
        val context = RuntimeEnvironment.getApplication()
        val first = ChatHistoryStore(context)
        val conversation = listOf(ChatMessage(true, "Привет!"), ChatMessage(false, "Рад тебя видеть."))
        first.write("hero_one", conversation)
        first.write("hero_two", listOf(ChatMessage(true, "Другой разговор")))

        val restored = ChatHistoryStore(context)
        assertEquals(conversation, restored.read("hero_one"))
        assertEquals(listOf(ChatMessage(true, "Другой разговор")), restored.read("hero_two"))
        assertEquals(emptyList<ChatMessage>(), restored.read("unknown"))
    }
}
