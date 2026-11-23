package com.generativemascot.app.ui

internal enum class AnimationCardStatus {
    READY, WORKING, QUEUED, PROCESSING, SAVING, PAUSED, NOT_STARTED,
}

internal data class AnimationPackProgress(val readyActions: Set<String>) {
    val readyCount: Int get() = readyActions.size
    val remainingCount: Int get() = ANIMATION_PACK_SIZE - readyCount

    fun activeAction(inProgress: Boolean, sequential: Boolean, batchActions: List<String> = ANIMATION_STATE_KEYS): String? =
        if (inProgress && sequential) batchActions.firstOrNull { it !in readyActions } else null

    fun cardStatus(
        action: String,
        inProgress: Boolean,
        syncing: Boolean,
        interrupted: Boolean,
        sequential: Boolean,
        batchActions: List<String> = ANIMATION_STATE_KEYS,
    ): AnimationCardStatus = when {
        action in readyActions -> AnimationCardStatus.READY
        action !in batchActions -> AnimationCardStatus.NOT_STARTED
        action == activeAction(inProgress, sequential, batchActions) -> AnimationCardStatus.WORKING
        inProgress && !sequential -> AnimationCardStatus.PROCESSING
        inProgress -> AnimationCardStatus.QUEUED
        syncing -> AnimationCardStatus.SAVING
        interrupted -> AnimationCardStatus.PAUSED
        else -> AnimationCardStatus.NOT_STARTED
    }
}

/** Count only the individual saved catalog videos, not free playback derivatives. */
internal fun animationPackProgress(videoUrls: Map<String, String>): AnimationPackProgress =
    AnimationPackProgress(ANIMATION_STATE_KEYS.filter { !videoUrls[it].isNullOrBlank() }.toSet())

internal fun animationPlayableActions(
    videoUrls: Map<String, String>,
    animationFrames: Map<String, List<String>>,
    legacyVideoAvailable: Boolean,
): Set<String> =
    ANIMATION_STATE_KEYS.filter { action ->
        !videoUrls[action].isNullOrBlank() || animationFrames[action].orEmpty().size > 1 ||
            (legacyVideoAvailable && action in LEGACY_PERFORMANCE_ACTIONS)
    }.toSet()

internal val LEGACY_PERFORMANCE_ACTIONS = setOf("idle", "joyful", "sleeping", "dancing")

/** Keep alternate playback states out of the gallery and list shared media once. */
internal fun galleryAnimationActions(
    videos: Map<String, String>, frames: Map<String, List<String>>, legacyVideo: String?,
): List<String> {
    val shownVideos = mutableSetOf<String>()
    val shownFrames = mutableSetOf<List<String>>()
    return ANIMATION_STATE_KEYS.filter { action ->
        if (action == "idle" || action == "typing_reaction") return@filter false
        val video = videos[action]?.takeIf(String::isNotBlank)
            ?: legacyVideo?.takeIf { it.isNotBlank() && action in LEGACY_PERFORMANCE_ACTIONS &&
                frames[action].orEmpty().size <= 1 }
        val sequence = frames[action].orEmpty()
        when {
            video != null -> shownVideos.add(video)
            sequence.size > 1 -> shownFrames.add(sequence)
            else -> true // Keep individual generation available.
        }
    }
}
