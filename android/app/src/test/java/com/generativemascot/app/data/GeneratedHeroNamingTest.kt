package com.generativemascot.app.data

import android.app.Application
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GeneratedHeroNamingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val id = "name-test-${UUID.randomUUID()}"
    private val store get() = HeroLocalStore(context)
    private fun naming(): GeneratedHeroNaming {
        store.prepareNameWriter(id)
        return GeneratedHeroNaming(store.nameWriterDirectory(id)!!,
            { store.mascotName(id) }, { store.saveGeneratedNameIfAbsent(id, it) })
    }

    @Test fun writerNamePersistsAndResumeDoesNotCallWriterAgain() = runBlocking {
        var calls = 0
        assertEquals("Луми", naming().ensure("Тима") { calls++; "Луми" })
        assertEquals("Луми", naming().ensure("Тима") { calls++; "Миро" })
        assertEquals(1, calls)
        assertEquals("Луми", store.mascotName(id))
    }

    @Test fun manualNameDoesNotCallWriterOrChange() = runBlocking {
        store.saveMascotName(id, "Марс")
        assertEquals("Марс", naming().ensure("Тима") { fail("Must not call writer"); null })
    }

    @Test fun manualRenameDuringRequestWins() = runBlocking {
        assertEquals("Аким", naming().ensure("Тима") {
            store.saveMascotName(id, "Аким")
            "Луми"
        })
        assertEquals("Аким", store.mascotName(id))
    }

    @Test fun timeoutUsesFallbackAndDoesNotRepeatPost() = runBlocking {
        var calls = 0
        assertEquals("Тима", naming().ensure("Тима") { calls++; throw IOException("timeout") })
        assertEquals("Тима", naming().ensure("Миро") { calls++; "Луми" })
        assertEquals(1, calls)
    }

    @Test fun interruptedPostIsNotResubmittedAfterRestart() = runBlocking {
        var calls = 0
        try {
            naming().ensure("Тима") { calls++; throw CancellationException("Process interrupted") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertNull(store.mascotName(id))
        assertEquals("Тима", naming().ensure("Тима") { calls++; "Луми" })
        assertEquals(1, calls)
    }

    @Test fun savedWriterResultSurvivesCrashBeforeNamePublication() = runBlocking {
        naming()
        File(store.nameWriterDirectory(id), "name-writer.result.txt").writeText("Луми")
        assertEquals("Луми", naming().ensure("Тима") { fail("Must reuse result"); null })
    }

    @Test fun concurrentStagesMakeOneRequest() = runBlocking {
        var calls = 0
        val results = List(4) { async { naming().ensure("Тима") { calls++; "Луми" } } }.awaitAll()
        assertEquals(listOf("Луми", "Луми", "Луми", "Луми"), results)
        assertEquals(1, calls)
    }

    @Test fun invalidOrEmptyWriterNameFallsBack() = runBlocking {
        assertEquals("Тима", naming().ensure("Тима") { "Герой" })
    }

    @Test fun legacyHeroesAreNotOptedIntoNaming() {
        assertNull(store.nameWriterDirectory(id))
    }

    @Test fun namesAreValidatedAndFallbackIsStableAndAvoidsOccupiedNames() {
        assertEquals("Луми", validGeneratedHeroName("  Луми\n"))
        for (name in listOf("", "Герой", "Спит", "Действия", "Name", "Луми!", "Имя: Луми",
            "Луми\nМиро", "123", "<script>", "А")) assertNull(name, validGeneratedHeroName(name))
        for (seed in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            val first = fallbackHeroName(seed, emptySet())
            assertEquals(first, fallbackHeroName(seed, emptySet()))
            assertNotNull(validGeneratedHeroName(first))
            assertNotEquals(first, fallbackHeroName(seed, setOf(first)))
        }
    }
}
