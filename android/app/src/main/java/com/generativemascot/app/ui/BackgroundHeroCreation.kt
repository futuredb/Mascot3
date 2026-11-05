package com.generativemascot.app.ui

import com.generativemascot.app.data.ContextDto
import com.generativemascot.app.data.MascotDto

/** Hide only the waiting UI; the worker and its persisted draft stay untouched. */
internal fun AppUiState.withCreationInBackground(hero: MascotDto, heroContext: ContextDto?): AppUiState = copy(
    mascot = hero, context = heroContext, name = hero.name.orEmpty(), route = "knock",
    newCreationFlow = false, backgroundCreation = true, pendingCreation = null,
    pendingCreationError = null, error = null,
)

internal fun AppUiState.withBackgroundCreationResult(draft: MascotDto?, message: String?): AppUiState = copy(
    creating = false, busy = false, generationLabel = null,
    pendingCreation = draft, pendingCreationError = message,
)

internal fun AppUiState.withCreationVisible(draft: MascotDto?): AppUiState = copy(
    mascot = draft, context = null, name = draft?.name.orEmpty(), route = "knock",
    newCreationFlow = true, backgroundCreation = false,
    error = pendingCreationError, pendingCreation = null, pendingCreationError = null,
)
