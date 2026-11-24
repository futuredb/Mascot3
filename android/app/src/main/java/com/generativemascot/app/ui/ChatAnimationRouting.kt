package com.generativemascot.app.ui

import com.generativemascot.app.data.HeroLocalStore

private val messageReactions = listOf(
    Regex("""(?iu)(?<![\p{L}\p{N}_])(привет|здравствуй|здравствуйте|доброе утро|добрый день|добрый вечер)(?![\p{L}\p{N}_])""") to
        listOf("greeting", "chat_happy", "joyful"),
    Regex("""(?iu)(?<![\p{L}\p{N}_])(танцуй|потанцуй|станцуй)(?![\p{L}\p{N}_])""") to listOf("dancing", "joyful"),
    Regex("""(?iu)(?<![\p{L}\p{N}_])(извини|прости|извиняюсь)(?![\p{L}\p{N}_])""") to listOf("affection", "chat_happy"),
    Regex("(?iu)(мне страшно|мне плохо|мне тяжело|расстроен|расстроилась|одиноко|грустно|печально|устал|устала|злюсь|раздраж[её]н|бесит|возмущ[её]н)") to
        listOf("worried", "encouraging", "tender", "sad"),
    Regex("(?iu)(у меня получилось|я справил(ся|ась)|мы победили|отличные новости)") to
        listOf("proud", "excited", "chat_happy", "joyful"),
    Regex("(?iu)(ха-ха|ахаха|смешно|уморительно)") to listOf("laughing", "chat_happy", "joyful"),
    Regex("(?iu)(спасибо|благодарю|классно|здорово|отлично|супер|люблю тебя|молодец)") to
        listOf("chat_happy", "affection", "joyful", "tender"),
    Regex("(?iu)(почему|как мне|объясни|расскажи|помоги|что думаешь|можешь ли|подскажи|\\?)") to
        listOf("curious", "confused"),
)
private val sleepRequest = Regex("""(?iu)(?<![\p{L}\p{N}_])(спи|засыпай|поспи|сон)(?![\p{L}\p{N}_])""")

/** Fill a missing model reaction only when a matching clip is already saved. */
internal fun savedMessageReaction(text: String, hasImage: Boolean, available: Set<String>): String? {
    val candidates = if (hasImage) listOf("curious", "surprised")
    else messageReactions.firstOrNull { (cue, _) -> cue.containsMatchIn(text) }?.second.orEmpty()
    return candidates.firstOrNull(available::contains)
}

/** Pick an already-generated reaction from the user's message, without a model call. */
internal fun chatAnimationForMessage(text: String, hasImage: Boolean, available: Set<String>): String {
    val saved = available.map(HeroLocalStore::normalizeVideoAction).toSet()
    val sleep = if (!hasImage && sleepRequest.containsMatchIn(text)) {
        listOf("sleeping", "resting").firstOrNull(saved::contains)
    } else null
    return sleep ?: savedMessageReaction(text, hasImage, saved)
        ?: listOf("thinking", "watching", "idle").firstOrNull(saved::contains)
        ?: resolveAvailableHomeAction("thinking", saved)
}
