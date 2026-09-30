package com.generativemascot.app.ui

import com.generativemascot.app.data.MascotDto

/** Use the same authoritative name for home, library and action-detail headings. */
internal fun heroHeadingName(persistedName: String?, providedName: String?, libraryName: String? = null): String =
    sequenceOf(persistedName, providedName, libraryName)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull() ?: "ГЕРОЙ"

/** A refreshed/generation DTO must not erase a name already saved for this hero. */
internal fun resolveHeroName(
    mascotId: String,
    persistedName: String?,
    currentMascot: MascotDto? = null,
    libraryName: String? = null,
    previousMascot: MascotDto? = null,
): String? = sequenceOf(
    persistedName,
    currentMascot?.takeIf { it.id == mascotId }?.name,
    libraryName,
    previousMascot?.takeIf { it.id == mascotId }?.name,
).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()

/** Update every cached presentation without replacing newer state or another selected hero. */
internal fun AppUiState.withSavedHeroName(mascotId: String, savedName: String): AppUiState {
    val cleanName = savedName.trim()
    return copy(
        mascot = mascot?.let { if (it.id == mascotId) it.copy(name = cleanName) else it },
        name = if (mascot?.id == mascotId) cleanName else name,
        heroLibrary = heroLibrary.map { if (it.id == mascotId) it.copy(name = cleanName) else it },
    )
}
