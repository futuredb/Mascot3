package com.generativemascot.app.data

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable at-most-once writer stage, separate from the expensive image stage. */
internal class GeneratedHeroNaming(
    private val directory: File,
    private val readName: () -> String?,
    private val saveIfAbsent: suspend (String) -> String,
) {
    suspend fun ensure(
        fallback: String,
        writeName: suspend () -> String?,
    ): String = writerLock.withLock {
        readName()?.let { return@withLock it }
        directory.mkdirs()
        val result = AtomicFile(File(directory, "name-writer.result.txt"))
        val stored = runCatching { result.openRead().bufferedReader().use { it.readText() } }.getOrNull()
        if (stored != null) return@withLock saveIfAbsent(validGeneratedHeroName(stored) ?: fallback)
        val claimed = File(directory, "name-writer.started").createNewFile()
        val name = if (claimed) {
            try {
                validGeneratedHeroName(writeName().orEmpty()) ?: fallback
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                fallback
            }
        } else {
            // An interrupted/ambiguous POST must never be resubmitted automatically.
            fallback
        }
        val output = result.startWrite()
        try {
            output.write(name.toByteArray(Charsets.UTF_8))
            result.finishWrite(output)
        } catch (error: Exception) {
            result.failWrite(output)
            throw error
        }
        saveIfAbsent(name)
    }

    companion object {
        private val writerLock = Mutex()
    }
}

internal fun validGeneratedHeroName(raw: String): String? {
    val name = raw.trim()
    if (!Regex("[А-ЯЁ][а-яё]{1,19}(?:-[А-ЯЁ][а-яё]{1,19})?").matches(name)) return null
    if (name.lowercase() in setOf("герой", "персонаж", "действия", "спит", "радость", "имя")) return null
    return name
}

internal fun fallbackHeroName(seed: Int, occupied: Set<String>): String {
    val names = listOf("Тима", "Ника", "Луми", "Миро", "Тоша", "Руни", "Сима", "Лёва",
        "Мика", "Филя", "Лада", "Юна", "Рома", "Лина", "Тея", "Сева", "Лука", "Мила",
        "Яся", "Тиша", "Рина", "Даня", "Лея", "Реми")
    val start = Math.floorMod(seed, names.size)
    return (names.indices).map { names[(start + it) % names.size] }
        .firstOrNull { candidate -> occupied.none { it.equals(candidate, ignoreCase = true) } }
        ?: names[start]
}
