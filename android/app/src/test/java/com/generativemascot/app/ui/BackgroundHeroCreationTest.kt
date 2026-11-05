package com.generativemascot.app.ui

import com.generativemascot.app.data.MascotDto
import org.junit.Assert.*
import org.junit.Test

class BackgroundHeroCreationTest {
    private val old = MascotDto(id = "old", name = "Старый", status = "READY", previewUrl = "file:///old.png")
    private val draft = MascotDto(id = "draft", name = "Новый", status = "AWAITING_ACCEPTANCE", previewUrl = "file:///draft.png")

    @Test fun hidingDoesNotStopGenerationAndFinishedDraftDoesNotReplaceCurrentHero() {
        val waiting = AppUiState(ready = true, creating = true, busy = true, newCreationFlow = true)
        val background = waiting.withCreationInBackground(old, null)
        assertTrue(background.creating)
        assertTrue(background.busy)
        assertTrue(background.backgroundCreation)
        assertTrue(background.showsHeroHome)
        assertEquals("old", background.mascot?.id)
        val result = background.copy(route = "animations").withBackgroundCreationResult(draft, null)
        assertFalse(result.creating)
        assertEquals("animations", result.route)
        assertEquals("old", result.mascot?.id)
        assertEquals("draft", result.pendingCreation?.id)
        val reopened = result.withCreationVisible(result.pendingCreation)
        assertFalse(reopened.backgroundCreation)
        assertTrue(reopened.newCreationFlow)
        assertEquals("draft", reopened.mascot?.id)
        assertEquals("knock", reopened.route)
    }

    @Test fun reopeningRunningGenerationKeepsTheWorkerStateAndReportsFailures() {
        val waiting = AppUiState(ready = true, creating = true, busy = true, newCreationFlow = true)
        val background = waiting.withCreationInBackground(old, null)
        val reopened = background.withCreationVisible(MascotDto(id = "draft", status = "BASE_GENERATING"))
        assertTrue(reopened.creating)
        assertTrue(reopened.busy)
        assertFalse(reopened.backgroundCreation)
        val failed = background.withBackgroundCreationResult(null, "Ошибка")
        assertEquals("old", failed.mascot?.id)
        assertEquals("Ошибка", failed.withCreationVisible(null).error)
    }
}
