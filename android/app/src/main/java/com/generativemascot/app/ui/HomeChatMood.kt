package com.generativemascot.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.generativemascot.app.data.CharacterCommunication
import com.generativemascot.app.data.CommunicationTrait
import java.util.Locale
import kotlinx.coroutines.delay
import com.generativemascot.app.data.HeroLocalStore
import com.generativemascot.app.data.characterPersonalityById

internal const val CHAT_MOOD_PREFERENCES = "mascot_chat_mood"

internal val moodLockedCommunicationTraits = setOf(CommunicationTrait.PROFANITY, CommunicationTrait.LOYALTY)

internal fun SharedPreferences.communicationLockExpiry(id: String, now: Long = System.currentTimeMillis()): Long {
    val expires = getLong("$id.expires", 0)
    return if (expires > now && getString(id, null) == "hurt") expires else 0L
}

internal fun CharacterCommunication.keepMoodLockedLevels(saved: CharacterCommunication): CharacterCommunication =
    moodLockedCommunicationTraits.fold(this) { draft, trait -> draft.withLevel(trait, saved[trait]) }

internal fun defaultCommunicationProfile(context: Context, heroId: String): CharacterCommunication =
    CharacterCommunication().withLevel(CommunicationTrait.PROFANITY,
        if (ChatProfanityStore(context).read(heroId) == true) 100 else 0)

@Composable
internal fun rememberCommunicationLockExpiry(heroId: String): Long {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(CHAT_MOOD_PREFERENCES, Context.MODE_PRIVATE) }
    var expires by remember(prefs, heroId) { mutableLongStateOf(prefs.communicationLockExpiry(heroId)) }
    DisposableEffect(prefs, heroId) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == heroId || key == "$heroId.expires") {
                expires = prefs.communicationLockExpiry(heroId)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        expires = prefs.communicationLockExpiry(heroId)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(prefs, heroId, expires) {
        while (expires > 0) {
            val remaining = expires - System.currentTimeMillis()
            if (remaining <= 0) {
                expires = prefs.communicationLockExpiry(heroId)
                break
            }
            delay(remaining.coerceAtMost(60_000))
        }
    }
    return expires
}

internal fun communicationLockCountdown(expires: Long, now: Long): String? {
    if (expires <= now) return null
    val seconds = (expires - now + 999) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

internal val homeReactionLines = mapOf(
    "curious" to "Теперь мне любопытно.",
    "chat_happy" to "У меня сегодня хорошее настроение.",
    "joyful" to "У меня сегодня хорошее настроение.",
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

internal val chatAngerReactions = listOf("offended", "annoyed", "angry")

internal fun chatMoodActions(change: String): List<String> = when (change) {
    "insult" -> listOf("annoyed", "angry", "offended", "sad")
    "upset" -> listOf("sad", "worried", "encouraging")
    "praise" -> listOf("chat_happy", "affection", "shy", "excited", "joyful", "proud", "laughing")
    "good_news" -> listOf("joyful", "excited", "laughing", "chat_happy", "proud", "affection")
    "apology" -> listOf("affection", "chat_happy", "joyful")
    else -> emptyList()
}

internal val chatMoodReactionActions =
    listOf("insult", "upset", "praise", "good_news", "apology").flatMap(::chatMoodActions).toSet()

internal class ChatAngerRotation {
    private val used = mutableSetOf<String>()
    private var last: String? = null

    fun select(preferred: String?, available: Set<String>, bold: Boolean): String? {
        val order = (if (bold) chatAngerReactions.asReversed() else chatAngerReactions)
            .filter { it in available }
        if (order.isEmpty()) return null
        used.retainAll(order.toSet())
        if (used.containsAll(order)) used.clear()
        val unused = order.filterNot(used::contains)
        val candidates = unused.filterNot { it == last }.ifEmpty { unused }
        return (preferred?.takeIf { it in candidates } ?: candidates.first()).also {
            used.add(it)
            last = it
        }
    }
}

internal data class HomeChatMood(val action: String, val change: String, val at: Long, val expires: Long) {
    fun line(bold: Boolean, profanity: Boolean, displayedAction: String? = null): String {
        val expression = displayedAction ?: action
        return when (change) {
            "praise" -> if (expression == "shy") "Спасибо. Я даже смутился." else "Спасибо за похвалу. Мне приятно."
            "apology" -> "Спасибо за извинение. Всё в порядке."
            "good_news" -> "Я рад хорошим новостям."
            "insult" -> when {
                bold && profanity && expression == "angry" -> "Бля, я злюсь. Со мной так нельзя."
                bold && profanity && expression == "annoyed" -> "Бля, меня это раздражает. Давай без этого."
                else -> homeReactionLines.getValue(expression)
            }
            else -> homeReactionLines.getValue(expression)
        }
    }

    fun compatibleActions(actions: Set<String>): List<String> =
        (listOf(action) + chatMoodActions(change)).distinct().filter { it in actions && it != "apology" }

    fun availableAction(actions: Set<String>): String? = compatibleActions(actions).firstOrNull()
}

internal fun SharedPreferences.readPersistentChatMood(id: String, now: Long): HomeChatMood? {
    val expires = getLong("$id.expires", 0)
    if (expires <= now) return null
    val savedChange = getString("$id.home.change", null)
    val (change, fallbackAction) = when (getString(id, null)) {
        "hurt" -> "insult" to "offended"
        "sad" -> "upset" to "sad"
        "pleased" -> (savedChange.takeIf { it == "good_news" } ?: "praise") to "chat_happy"
        else -> return null
    }
    val savedAction = getString("$id.home.action", null)
        ?.takeIf { savedChange == change && it in homeReactionLines }
    return HomeChatMood(savedAction ?: fallbackAction, change,
        if (savedAction == null) 0 else getLong("$id.home.at", 0), expires)
}

internal fun SharedPreferences.readHomeChatMood(id: String, now: Long): HomeChatMood? {
    readPersistentChatMood(id, now)?.let { return it }
    val at = getLong("$id.home.at", 0)
    val expires = getLong("$id.home.expires", 0)
    val action = getString("$id.home.action", null)
    val change = getString("$id.home.change", "").orEmpty()
    if (expires > now && (action in homeReactionLines || action == "apology" && change == "apology")) {
        return HomeChatMood(requireNotNull(action), change, at, expires)
    }
    return null
}

internal fun HeroLocalStore.chatCharacterTone(id: String): String? =
    creativeContract(id)?.behavior?.worldview?.takeIf { it.isNotBlank() }
        ?: characterChoice(id)?.let { choice ->
            choice.personalityPrompt.ifBlank { characterPersonalityById(choice.style)?.tone.orEmpty() }
                .takeIf { it.isNotBlank() }
        }
