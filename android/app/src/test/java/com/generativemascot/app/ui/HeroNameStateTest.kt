package com.generativemascot.app.ui

import com.generativemascot.app.data.MascotDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeroNameStateTest {
    @Test fun everyHeadingRecoversSavedNamesInsteadOfGenericLabels() {
        for (name in listOf("Аким", "Марс", "Уна")) {
            assertEquals(name, heroHeadingName(name, null))
            assertEquals(name, heroHeadingName(" $name ", " "))
            assertEquals(name, heroHeadingName(name, "Старое имя"))
            assertEquals(name, heroHeadingName(null, name))
            assertEquals(name, heroHeadingName(null, null, name))
        }
        assertEquals("ГЕРОЙ", heroHeadingName(null, " "))
    }
    private fun hero(id: String, name: String? = null) = MascotDto(id = id, name = name, status = "READY")

    @Test
    fun savedNameWinsOverStaleGenerationAndLibrarySnapshots() {
        assertEquals(
            "Аким",
            resolveHeroName("a", " Аким ", hero("a", "Старое"), "Старое", hero("a", "Старое")),
        )
    }

    @Test
    fun completionOrInterruptionCanRecoverTheSameHerosName() {
        assertEquals("Аким", resolveHeroName("a", null, hero("a", "Аким")))
        assertEquals("Аким", resolveHeroName("a", null, previousMascot = hero("a", "Аким")))
        assertEquals("Аким", resolveHeroName("a", " ", libraryName = "Аким"))
    }

    @Test
    fun newHeroNeverInheritsThePreviousHerosName() {
        assertNull(resolveHeroName("b", null, hero("a", "Аким"), previousMascot = hero("a", "Аким")))
    }

    @Test
    fun renameUpdatesHomeEditorAndPickerWithoutResettingNewerAnimationState() {
        val current = AppUiState(
            mascot = hero("a", "Старое").copy(stages = mapOf("animations" to "ready")),
            name = "Старое",
            route = "animations",
            heroLibrary = listOf(HeroLibraryItem("a", "Старое"), HeroLibraryItem("b", "Павел")),
        )

        val renamed = current.withSavedHeroName("a", " Аким ")

        assertEquals("Аким", renamed.mascot?.name)
        assertEquals("Аким", renamed.name)
        assertEquals(listOf("Аким", "Павел"), renamed.heroLibrary.map { it.name })
        assertEquals("ready", renamed.mascot?.stages?.get("animations"))
        assertEquals("animations", renamed.route)
    }

    @Test
    fun delayedRenameDoesNotSwitchBackToAnotherHero() {
        val current = AppUiState(
            mascot = hero("b", "Павел"),
            name = "Павел",
            heroLibrary = listOf(HeroLibraryItem("a", "Старое"), HeroLibraryItem("b", "Павел")),
        )

        val renamed = current.withSavedHeroName("a", "Аким")

        assertEquals(current.mascot, renamed.mascot)
        assertEquals("Павел", renamed.name)
        assertEquals("Аким", renamed.heroLibrary.first().name)
    }
}
