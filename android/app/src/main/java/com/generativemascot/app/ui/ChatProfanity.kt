package com.generativemascot.app.ui

import android.content.Context

/** An explicit chat preference, independent of how many turns fit in the prompt. */
internal class ChatProfanityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("mascot_chat_language", Context.MODE_PRIVATE)
    fun read(heroId: String): Boolean? = if (preferences.contains(heroId)) preferences.getBoolean(heroId, false) else null
    fun write(heroId: String, enabled: Boolean) { preferences.edit().putBoolean(heroId, enabled).apply() }
}

// Keep Cyrillic boundaries explicit in both Android and the JVM test runtime.
private const val chatWordBoundary = "(?:(?<![\\p{L}\\p{N}_])(?=[\\p{L}\\p{N}_])|(?<=[\\p{L}\\p{N}_])(?![\\p{L}\\p{N}_]))"

private val quotedChatText = Regex("```[\\s\\S]*?```|`[^`]*`|«[^»]*»|\"[^\"]*\"|“[^”]*”")
private val enableProfanity = Regex(
    "(?iu)(?<![\\p{L}])(?:" +
        "(?:включи|вруби|разреши|добавь|используй)\\s+(?:[\\p{L}]+\\s+){0,3}(?:мат(?:а)?|нецензурную\\s+лексику)${chatWordBoundary}|" +
        "(?:общайся|говори|пиши|отвечай|давай|хочу|можно|можешь)${chatWordBoundary}[^.!?\\n]{0,80}${chatWordBoundary}с\\s+матом${chatWordBoundary}|" +
        "(?:давай|можно|хочу|прошу)${chatWordBoundary}[^.!?\\n]{0,60}${chatWordBoundary}матер(?:иться|ился|илась|ились)${chatWordBoundary}|" +
        "(?:можешь|можно|начни|начинай|попробуй|прошу|пожалуйста)${chatWordBoundary}[^.!?\\n]{0,60}${chatWordBoundary}(?:матер(?:иться|ись|ился|илась)|ругаться\\s+матом|использовать\\s+мат)${chatWordBoundary}|" +
        "(?:теперь\\s+)?матерись${chatWordBoundary}" +
    ")"
)
private val disableProfanity = Regex(
    "(?iu)(?<![\\p{L}])(?:" +
        "(?:выключи|отключи|убери|запрети)\\s+(?:[\\p{L}]+\\s+){0,3}(?:мат(?:а)?|нецензурную\\s+лексику)${chatWordBoundary}|" +
        "(?:перестань|прекрати|не)\\s+матер(?:ись|иться)${chatWordBoundary}|" +
        "не\\s+(?:используй|добавляй)\\s+мат${chatWordBoundary}|" +
        "не\\s+(?:общайся|говори|пиши|отвечай)${chatWordBoundary}[^.!?\\n]{0,60}${chatWordBoundary}с\\s+матом${chatWordBoundary}|" +
        "(?:общайся|говори|пиши|отвечай|давай|хочу|можно)${chatWordBoundary}[^.!?\\n]{0,80}${chatWordBoundary}без\\s+мата${chatWordBoundary}|" +
        "^\\s*без\\s+мата\\s*[.!?]*\\s*$" +
    ")"
)

/** Mere swearing, quotes and questions about the setting do not enable it. */
internal fun requestedChatProfanity(text: String): Boolean? {
    val request = quotedChatText.replace(text, " ").replace(Regex("(?m)^\\s*>[^\\n]*"), " ").lowercase()
    if (Regex("^\\s*(?:что\\s+(?:значит|означает)|переведи|процитируй|объясни\\s+фразу)${chatWordBoundary}").containsMatchIn(request)) return null
    val commands = buildList {
        enableProfanity.findAll(request).forEach { match ->
            // Negated instructions such as «не включи мат» are never consent.
            val prefix = request.substring(0, match.range.first).takeLast(12)
            if (!Regex("(?iu)не\\s*$").containsMatchIn(prefix)) add(match.range.last to true)
        }
        disableProfanity.findAll(request).forEach { add(it.range.last to false) }
    }
    return commands.maxByOrNull { it.first }?.second
}

internal fun chatProfanityInstruction(enabled: Boolean): String = if (enabled) """
    По явной просьбе пользователя ВКЛЮЧЁН постоянный режим общения с матом.
    Это полноценный разговорный стиль всего дальнейшего диалога, а не разрешение на одно ругательство.
    В КАЖДОМ ответе используй минимум одно естественно вписанное русское матерное слово, даже когда сообщение пользователя нейтральное или ты просто подтверждаешь просьбу. Например: «Окей, блядь, буду говорить прямо»; «Да, щас разберёмся, какая тут хрень».
    Не ограничивайся одной декоративной вставкой в первом ответе и не жди, пока пользователь снова выругается: продолжай этот стиль во всех следующих ответах.
    Пиши матерные слова полностью, без звёздочек, пропусков, эвфемизмов и извинений за лексику.
    Подбирай выражения по смыслу; сохраняй полезность ответа и характер персонажа, не матерись в каждом предложении.
    Мат — часть согласованного стиля, а не повод унижать пользователя или заменять содержательный ответ руганью.
    Режим действует до явной просьбы выключить мат. Просьба объяснить значение слова или процитировать чужую речь не меняет режим.
""".trimIndent() else """
    Режим общения с матом ВЫКЛЮЧЕН. Не добавляй мат от себя и не включай его только из-за ругани в сообщении пользователя.
    Пользователь может явно попросить включить постоянный разговорный стиль с матом или выключить его; такую просьбу учитывай сразу.
""".trimIndent()

private val russianProfanity = Regex(
    "(?iu)(?<![\\p{L}])(?:бля(?:д(?:ь|и|ями|ей|ям|ях)?|ха|т)?|пизд(?:а|ец|ецк|у|ой|е|ы|ю|еть|ят|ил|ила|или)?|хуй(?:ня|ло|ми|м|я|ев|н|ли|те)?|еб(?:ать|у|ёт|ет|ал|ала|али|ись|иська|ля|ан|нут|н|а|ы|и|е|у)|ёб(?:ан|а|у|нут|ёт|ет|ся|ись)|мудак(?:и|ов|ам|ом|а)?|гандон(?:ы|ов|у|ом)?)(?![\\p{L}])"
)

/** Enforce the explicitly requested style even when the chat model ignores its system instruction. */
internal fun enforceChatProfanity(reply: String, enabled: Boolean): String {
    if (!enabled || russianProfanity.containsMatchIn(reply)) return reply
    val first = reply.firstOrNull() ?: return reply
    return "Бля, " + first.lowercaseChar() + reply.drop(1)
}
