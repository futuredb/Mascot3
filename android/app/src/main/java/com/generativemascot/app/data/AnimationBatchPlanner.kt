package com.generativemascot.app.data

import java.io.File

internal const val DEFAULT_ANIMATION_BATCH_SIZE = 4

internal val ANIMATION_BATCH_ORDER = HeroLocalStore.CORE_VIDEO_ACTIONS +
    HeroLocalStore.LIBRARY_VIDEO_ACTIONS.filterNot { it in HeroLocalStore.CORE_VIDEO_ACTIONS }

internal fun defaultAnimationBatchSize(remaining: Int): Int =
    minOf(DEFAULT_ANIMATION_BATCH_SIZE, remaining.coerceAtLeast(0))

/** The count includes resuming known paid jobs; accepted clips never consume a new slot. */
internal fun planAnimationBatch(
    readyActions: Set<String>,
    pendingActions: Set<String>,
    requestedCount: Int,
): List<String> {
    require(requestedCount in 1..HeroLocalStore.LIBRARY_VIDEO_ACTIONS.size) {
        "Выберите от 1 до ${HeroLocalStore.LIBRARY_VIDEO_ACTIONS.size} анимаций"
    }
    val missing = ANIMATION_BATCH_ORDER.filterNot(readyActions::contains)
    return (missing.filter(pendingActions::contains) + missing.filterNot(pendingActions::contains))
        .take(requestedCount)
}

/** Missing metadata belongs to an already authorized legacy full-catalog worker. */
internal fun validateAnimationBatch(actions: List<String>?): List<String> {
    // Never grow a pre-update worker's paid authorization when a new action is added.
    val selected = actions ?: HeroLocalStore.LEGACY_LIBRARY_VIDEO_ACTIONS
    require(selected.isNotEmpty() && selected.size <= HeroLocalStore.LIBRARY_VIDEO_ACTIONS.size + 1 &&
        selected.distinct().size == selected.size && selected.all { it in HeroLocalStore.LIBRARY_VIDEO_ACTIONS || it == "welcome" }) {
        "Повреждён сохранённый список партии; платные запросы не отправлялись"
    }
    // Do not order the retired duplicate even when resuming a v0.1.27/28 worker.
    // Existing files/provider IDs are retained, not deleted or submitted again.
    return selected.filterNot { it == "welcome" }
}

internal fun claimVideoSubmission(directory: File, requestToken: String, action: String) {
    require(requestToken.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Неверный номер запроса" }
    require(action in HeroLocalStore.LIBRARY_VIDEO_ACTIONS) { "Неизвестная анимация" }
    require(directory.isDirectory || directory.mkdirs()) { "Не удалось сохранить отметку запроса" }
    check(File(directory, "$requestToken.$action.started").createNewFile()) {
        "Отправка анимации $action была прервана до сохранения номера задания. " +
            "Повторный платный запрос не отправлялся; проверьте результат в OpenRouter перед новой попыткой."
    }
}
