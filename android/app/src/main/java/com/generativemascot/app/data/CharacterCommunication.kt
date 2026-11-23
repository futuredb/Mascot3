package com.generativemascot.app.data

import kotlinx.serialization.Serializable

internal enum class CommunicationTrait(val title: String, val labels: List<String>, val directions: List<String>) {
    BREVITY("Краткость", listOf("Очень подробно", "С деталями", "Супер кратко"),
        listOf("Объясняй подробно и по шагам, добавляй примеры.", "Давай ответ с необходимыми деталями.", "Отвечай предельно коротко, сразу к сути.")),
    COMPLEXITY("Сложность речи", listOf("Очень просто", "На обычном языке", "Профессионально"),
        listOf("Используй простые слова и короткие фразы.", "Говори на обычном разговорном языке.", "Говори профессионально, уместно используй точные термины.")),
    HUMOR("Юмор", listOf("Без шуток", "Шутки к месту", "Шучу постоянно"),
        listOf("Не шути и не используй иронию.", "Шути, когда это к месту.", "В обычном разговоре шути часто и естественно.")),
    PROFANITY("Мат", listOf("Без мата", "Мат к месту", "Много матерюсь"),
        listOf("Не используй мат в собственных репликах.", "Мат допустим, когда он естественно подчёркивает ситуацию.", "Регулярно используй естественный русский мат, направляя его на ситуацию.")),
    DIRECTNESS("Грубость", listOf("Общаюсь мягко", "Говорю прямо", "Отвечаю жёстко"),
        listOf("Формулируй бережно и мягко.", "Говори прямо и ясно.", "Отвечай жёстко и без смягчений, сохраняя уважение к человеку.")),
    LOYALTY("Лояльность", listOf("Критик и скептик", "Ищу аргументы", "На твоей стороне"),
        listOf("Проверяй предположения, ищи слабые места и контраргументы.", "Взвешивай аргументы с разных сторон.", "Поддерживай цели пользователя, сохраняя точность и честность.")),
}

@Serializable
data class CharacterCommunication(
    private val levels: List<Int> = List(CommunicationTrait.entries.size) { 50 },
    val notes: String = "",
) {
    init {
        require(levels.size == CommunicationTrait.entries.size && levels.all { it in setOf(0, 50, 100) })
        require(notes.length <= 500)
    }

    internal operator fun get(trait: CommunicationTrait): Int = levels[trait.ordinal]
    internal fun withLevel(trait: CommunicationTrait, level: Int) =
        copy(levels = levels.toMutableList().apply { this[trait.ordinal] = level })

    internal fun label(trait: CommunicationTrait) = trait.labels[this[trait] / 50]
    internal fun summary(): String = CommunicationTrait.entries.filter { this[it] != 50 }
        .joinToString(", ") { label(it).lowercase() }.replaceFirstChar { it.uppercase() }

    internal fun chatInstructions(): String = CommunicationTrait.entries.joinToString("\n") {
        "${it.title} (${this[it]}/100) — ${it.directions[this[it] / 50]}"
    }

    internal fun generationInstructions(includeNotes: Boolean = true): String = buildString {
        append("COMMUNICATION PROFILE (behavior reference only, never render words): ")
        append(CommunicationTrait.entries.joinToString("; ") { "${it.title} ${this@CharacterCommunication[it]}/100 (${label(it)})" })
        append(". Reflect it subtly in expression, gaze, and economy of gestures. Preserve the action and visual identity.")
        if (includeNotes && notes.isNotBlank()) append(" Additional style notes: $notes")
    }

    companion object {
        /** Derive untouched creation sliders from the selected personality. */
        internal fun fromPersonality(description: String, variationSeed: Int = description.hashCode()): CharacterCommunication {
            val text = description.lowercase().trim()
            if (text.isEmpty()) return CharacterCommunication()
            val influences = mutableListOf<IntArray>()
            fun add(vararg values: Int) { influences += values }
            if ("спокойн" in text || "уравновеш" in text || "calm" in text) add(0, 0, -1, -1, 0, 0)
            if ("дерзк" in text || "уверен" in text || "bold" in text) add(1, 0, 0, 1, 1, 0)
            if ("любопыт" in text || "исслед" in text || "curious" in text) add(-1, 1, 0, -1, 0, -1)
            if ("застенчив" in text || "деликат" in text || "shy" in text) add(1, -1, -1, -1, -1, 1)
            if ("мечтатель" in text || "воображ" in text || "dreamy" in text) add(-1, 0, 0, -1, -1, 1)
            if ("жизнерадост" in text || "энергич" in text || "joyful" in text) add(0, -1, 1, 0, 0, 1)
            if (influences.isEmpty()) return CharacterCommunication()

            val scores = IntArray(CommunicationTrait.entries.size) { axis -> influences.sumOf { it[axis] } }
            val derived = scores.mapIndexed { axis, score ->
                when {
                    score < 0 -> 0
                    score > 0 -> 100
                    influences.size > 1 && ((variationSeed ushr (axis * 3)) and 3) == 0 ->
                        if (((variationSeed ushr axis) and 1) == 0) 0 else 100
                    else -> 50
                }
            }
            return CharacterCommunication(levels = derived)
        }
    }
}
