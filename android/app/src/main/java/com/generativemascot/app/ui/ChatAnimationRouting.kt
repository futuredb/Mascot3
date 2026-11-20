package com.generativemascot.app.ui

import com.generativemascot.app.data.HeroLocalStore

/** Pick an already-generated reaction from the user's message, without a model call. */
internal fun chatAnimationForMessage(text: String, hasImage: Boolean, available: Set<String>): String {
    val normalized = text.lowercase().trim()
    val preferred = when {
        hasImage -> "curious"
        Regex("(?iu)\\b(привет|здравствуй|здравствуйте|доброе утро|добрый день|добрый вечер)\\b").containsMatchIn(normalized) -> "greeting"
        Regex("(?iu)\\b(танцуй|потанцуй|станцуй|танец)\\b").containsMatchIn(normalized) -> "dancing"
        Regex("(?iu)\\b(спи|засыпай|поспи|сон)\\b").containsMatchIn(normalized) -> "sleeping"
        Regex("(?iu)(спасибо|благодарю|классно|здорово|отлично|супер|люблю тебя|молодец)").containsMatchIn(normalized) -> "joyful"
        Regex("(?iu)(грустно|печально|плохо|тяжело|расстроен|расстроилась|устал|устала|одиноко)").containsMatchIn(normalized) -> "tender"
        Regex("(?iu)(злюсь|раздражен|раздражена|бесит|возмущен|возмущена)").containsMatchIn(normalized) -> "tender"
        Regex("(?iu)(почему|как мне|объясни|расскажи|помоги|что думаешь|можешь ли|подскажи)").containsMatchIn(normalized) || '?' in normalized -> "curious"
        else -> "thinking"
    }
    val saved = available.map(HeroLocalStore::normalizeVideoAction).toSet()
    val alternatives = when (preferred) {
        "tender" -> listOf("tender", "sad", "curious", "thinking")
        "curious" -> listOf("curious", "thinking", "watching", "joyful")
        "thinking" -> listOf("thinking", "curious", "watching", "idle")
        "greeting" -> listOf("greeting", "joyful", "watching", "idle")
        "joyful" -> listOf("joyful", "tender", "curious", "idle")
        "dancing" -> listOf("dancing", "joyful", "idle")
        "sleeping" -> listOf("sleeping", "resting", "idle")
        else -> listOf(preferred)
    }
    return alternatives.firstOrNull { it in saved } ?: resolveAvailableHomeAction(preferred, saved)
}
