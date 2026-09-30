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
