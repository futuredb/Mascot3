package com.generativemascot.app.ui

import android.content.SharedPreferences
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.characterPersonalityById

internal const val CHAT_MOOD_PREFERENCES = "mascot_chat_mood"

internal val homeReactionLines = mapOf(
    "curious" to "Теперь мне любопытно.",
    "chat_happy" to "У меня сегодня хорошее настроение.",
    "excited" to "Вот это меня порадовало!",
    "laughing" to "До сих пор смешно.",
    "shy" to "Я немного смутился.",
    "proud" to "Есть чем гордиться.",
    "affection" to "Мне стало теплее после нашего разговора.",
    "surprised" to "Вот этого я не ожидал.",
    "confused" to "Я всё ещё пытаюсь разобраться.",
    "skeptical" to "У меня пока остались сомнения.",
    "sad" to "Мне немного грустно.",
    "offended" to "Меня задело то, что ты сказал.",
    "annoyed" to "Я всё ещё раздражён.",
    "angry" to "Я злюсь. Со мной так нельзя.",
    "worried" to "Я немного переживаю за тебя.",
    "encouraging" to "Я верю, что у тебя получится.",
    "bored" to "Мне скучновато.",
    "sleepy" to "Меня клонит в сон.",
)

internal data class HomeChatMood(val action: String, val change: String, val at: Long, val expires: Long) {
    fun line(bold: Boolean, profanity: Boolean, displayedAction: String? = null): String {
        val expression = displayedAction ?: action
        return when (change) {
            "praise" -> if (expression == "shy") "Спасибо. Я даже смутился." else "Спасибо за похвалу. Мне приятно."
            "apology" -> "Спасибо за извинение. Всё в порядке."
            "insult" -> when {
                bold && profanity && expression == "angry" -> "Бля, я злюсь. Со мной так нельзя."
                bold && profanity && expression == "annoyed" -> "Бля, меня это раздражает. Давай без этого."
                else -> homeReactionLines.getValue(expression)
            }
            else -> homeReactionLines.getValue(expression)
        }
    }

    fun availableAction(actions: Set<String>): String? = (
        listOf(action) + when (change) {
            "insult" -> listOf("annoyed", "angry", "offended")
            "praise" -> listOf("chat_happy", "affection", "shy", "excited", "joyful")
            "apology" -> listOf("affection", "chat_happy", "joyful")
            else -> emptyList()
        }
    ).firstOrNull { it in actions && it != "apology" }
}

internal fun SharedPreferences.readHomeChatMood(id: String, now: Long): HomeChatMood? {
    val at = getLong("$id.home.at", 0)
    val expires = getLong("$id.home.expires", 0)
    val action = getString("$id.home.action", null)
    val change = getString("$id.home.change", "").orEmpty()
    if (expires > now && (action in homeReactionLines || action == "apology" && change == "apology")) {
        return HomeChatMood(requireNotNull(action), change, at, expires)
    }
    // Existing heroes already have relationship mood from earlier chat versions.
    val moodExpires = getLong("$id.expires", 0)
    if (moodExpires <= now) return null
    return when (getString(id, null)) {
        "hurt" -> HomeChatMood("offended", "insult", 0, moodExpires)
        "pleased" -> HomeChatMood("chat_happy", "praise", 0, moodExpires)
        else -> null
    }
}

internal fun HeroLocalStore.chatCharacterTone(id: String): String? =
    creativeContract(id)?.behavior?.worldview?.takeIf { it.isNotBlank() }
        ?: characterChoice(id)?.let { choice ->
            choice.personalityPrompt.ifBlank { characterPersonalityById(choice.style)?.tone.orEmpty() }
                .takeIf { it.isNotBlank() }
        }
