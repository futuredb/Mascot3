package com.generativemascot.app.data

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val VIDEO_PROMPT_CATALOG_VERSION = 5
internal const val VIDEO_PACK_MANIFEST_VERSION = 1
internal const val DERIVED_SLEEP_START_MS = 1_600L
internal const val DERIVED_SLEEP_END_MS = 4_400L

@Serializable
internal data class CanonicalImageQa(
    val contract: String = "pet-generator.canonical-image-qa.v2",
    @SerialName("hard_pass") val hardPass: Boolean,
    @SerialName("blocking_issues") val blockingIssues: List<String>,
    val width: Int,
    val height: Int,
    @SerialName("transparent_ratio") val transparentRatio: Double,
    @SerialName("subject_width_ratio") val subjectWidthRatio: Double,
    @SerialName("subject_height_ratio") val subjectHeightRatio: Double,
    @SerialName("minimum_margin_ratio") val minimumMarginRatio: Double,
)

@Serializable
internal data class VideoClipQa(
    val contract: String = "pet-generator.video-qa.v1",
    val action: String,
    @SerialName("hard_pass") val hardPass: Boolean,
    @SerialName("blocking_issues") val blockingIssues: List<String>,
    val warnings: List<String>,
    @SerialName("duration_ms") val durationMs: Long,
    val width: Int,
    val height: Int,
    @SerialName("sample_count") val sampleCount: Int,
    @SerialName("minimum_border_key_ratio") val minimumBorderKeyRatio: Double,
    @SerialName("minimum_frame_margin") val minimumFrameMargin: Double,
    @SerialName("endpoint_delta") val endpointDelta: Double,
    @SerialName("matte_hex") val matteHex: String,
    @SerialName("loop_seam") val loopSeam: VideoLoopSeam? = null,
    @SerialName("sleep_loop_seam") val sleepLoopSeam: VideoLoopSeam? = null,
)

@Serializable
internal data class VideoLoopSeam(
    @SerialName("endpoint_delta") val endpointDelta: Double,
    @SerialName("first_step_delta") val firstStepDelta: Double,
    @SerialName("last_step_delta") val lastStepDelta: Double,
    @SerialName("typical_step_delta") val typicalStepDelta: Double,
    @SerialName("peak_edge_step_delta") val peakEdgeStepDelta: Double = 0.0,
    @SerialName("motion_mismatch") val motionMismatch: Double = 0.0,
)

internal fun loopSeamIssue(seam: VideoLoopSeam?): String? {
    if (seam == null || listOf(seam.endpointDelta, seam.firstStepDelta, seam.lastStepDelta,
            seam.typicalStepDelta, seam.peakEdgeStepDelta, seam.motionMismatch).any { !it.isFinite() || it < 0.0 }) {
        return "Не удалось проверить стык анимации"
    }
    val edgeStep = maxOf(seam.firstStepDelta, seam.lastStepDelta, seam.peakEdgeStepDelta)
    if (seam.endpointDelta > 0.035 ||
        (seam.endpointDelta > 0.02 && seam.endpointDelta > edgeStep * 2.0 + 0.005)) {
        return "Начало и конец анимации заметно различаются"
    }
    if (edgeStep > 0.06 && edgeStep > seam.typicalStepDelta * 5.0) {
        return "В начале или конце анимации обнаружен резкий скачок"
    }
    if (seam.motionMismatch > 0.06 && seam.motionMismatch > seam.typicalStepDelta * 3.0) {
        return "Направление движения резко меняется на стыке"
    }
    return null
}

@Serializable
internal data class VideoClipManifest(
    val id: String,
    val file: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("cost_usd") val costUsd: Double? = null,
    val status: String = "approved",
    @SerialName("qa_file") val qaFile: String? = null,
)

@Serializable
internal data class VideoTransitionManifest(
    val from: String,
    val to: String,
    val trigger: String,
    val policy: String,
)

@Serializable
internal data class VideoPackManifest(
    val contract: String = "pet-generator.video-pack.v1",
    val version: Int = VIDEO_PACK_MANIFEST_VERSION,
    @SerialName("mascot_id") val mascotId: String,
    @SerialName("prompt_catalog_version") val promptCatalogVersion: Int = VIDEO_PROMPT_CATALOG_VERSION,
    @SerialName("default_action") val defaultAction: String = "idle",
    val clips: List<VideoClipManifest>,
    val transitions: List<VideoTransitionManifest>,
)

@Serializable
internal data class AnimationPromptRecord(
    val contract: String = "pet-generator.animation-prompt-record.v1",
    val action: String,
    @SerialName("catalog_version") val catalogVersion: Int = VIDEO_PROMPT_CATALOG_VERSION,
    @SerialName("prompt_sha256") val promptSha256: String,
    @SerialName("matte_hex") val matteHex: String,
    val direction: MobileAnimationContract,
    val prompt: String,
)

internal fun promptSha256(prompt: String): String = MessageDigest.getInstance("SHA-256")
    .digest(prompt.toByteArray())
    .joinToString("") { "%02x".format(it) }

private val absentAnimationGeometry = Regex(
    "\\b(floor|wall|glass|mirror|reflection|shadow|window|pane|ledge|barrier)\\b",
    RegexOption.IGNORE_CASE,
)

internal fun validateResolvedVideoPrompt(prompt: String, matteHex: String) {
    require(prompt.length <= 6_000) { "Промпт анимации слишком длинный: ${prompt.length}" }
    require(prompt.contains(matteHex, ignoreCase = true)) { "Промпт потерял цвет технического фона" }
    require(prompt.contains("one complete indivisible body", ignoreCase = true)) {
        "Промпт потерял правило цельного тела"
    }
    require(prompt.contains("camera movement", ignoreCase = true)) { "Промпт потерял фиксацию камеры" }
    require(!absentAnimationGeometry.containsMatchIn(prompt)) {
        "Промпт называет отсутствующую геометрию и может заставить модель её нарисовать"
    }
}

internal fun defaultVideoTransitions(): List<VideoTransitionManifest> = listOf(
    VideoTransitionManifest("idle", "greeting", "app_foreground", "play_once_if_saved_interruptible"),
    VideoTransitionManifest("greeting", "idle", "clip_finished", "crossfade_at_canonical_anchor"),
    VideoTransitionManifest("idle", "joyful", "tap_or_pet", "finish_current_then_crossfade_at_anchor"),
    VideoTransitionManifest("idle", "dancing", "swipe_up", "finish_current_then_crossfade_at_anchor"),
    VideoTransitionManifest("idle", "sleeping", "sleep_gesture", "crossfade_at_canonical_anchor"),
    VideoTransitionManifest("sleeping", "sleeping", "no_interaction", "repeat_closed_cycle"),
    VideoTransitionManifest("sleeping", "joyful", "any_interaction", "crossfade_at_canonical_anchor"),
    VideoTransitionManifest("joyful", "idle", "clip_finished", "crossfade_at_canonical_anchor"),
    VideoTransitionManifest("dancing", "idle", "clip_finished", "crossfade_at_canonical_anchor"),
)

internal fun evaluateVideoQa(
    action: String,
    expectedDurationSeconds: Int,
    durationMs: Long,
    width: Int,
    height: Int,
    sampleCount: Int,
    minimumBorderKeyRatio: Double,
    minimumFrameMargin: Double,
    endpointDelta: Double,
    matteHex: String,
    enforceLoop: Boolean = false,
    loopSeam: VideoLoopSeam? = null,
    sleepLoopSeam: VideoLoopSeam? = null,
): VideoClipQa {
    val issues = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val expectedMs = expectedDurationSeconds * 1_000L
    if (durationMs < expectedMs - 900L || durationMs > expectedMs + 1_500L) {
        issues += "Неверная длительность: %.2f с вместо %d с".format(durationMs / 1_000.0, expectedDurationSeconds)
    }
    if (width < 640 || height < 640) issues += "Слишком низкое разрешение: ${width}×${height}"
    if (sampleCount < 5) issues += "Не удалось прочитать контрольные кадры"
    if (enforceLoop) {
        loopSeamIssue(loopSeam)?.let { issues += it }
        if (HeroLocalStore.normalizeVideoAction(action) == "sleeping") {
            loopSeamIssue(sleepLoopSeam)?.let { issues += "Петля сна: $it" }
        }
    }
    // The paid source is retained even if a new clip fails loop validation.
    // Never silently submit a replacement request after visual QA.
    if (minimumBorderKeyRatio < 0.82) {
        warnings += "Фон меняется или содержит объекты у края кадра; клип сохранён"
    } else if (minimumBorderKeyRatio < 0.92) {
        warnings += "Хромакей у края кадра нестабилен"
    }
    if (minimumFrameMargin < 0.012) {
        warnings += "Обнаружены пиксели у края кадра; клип сохранён"
    } else if (minimumFrameMargin < 0.04) {
        warnings += "У персонажа слишком маленький запас до края"
    }
    // Preserve the older five-sample diagnostic for compatibility with saved QA.
    if (endpointDelta > 0.20) {
        warnings += "Финальная поза сильно отличается от начальной"
    } else if (endpointDelta > 0.08) {
        warnings += "Финал отличается от начала"
    }
    return VideoClipQa(
        action = action,
        hardPass = issues.isEmpty(),
        blockingIssues = issues,
        warnings = warnings,
        durationMs = durationMs,
        width = width,
        height = height,
        sampleCount = sampleCount,
        minimumBorderKeyRatio = minimumBorderKeyRatio,
        minimumFrameMargin = minimumFrameMargin,
        endpointDelta = endpointDelta,
        matteHex = matteHex,
        loopSeam = loopSeam,
        sleepLoopSeam = sleepLoopSeam,
    )
}

internal fun evaluateCanonicalImageQa(
    width: Int,
    height: Int,
    transparentRatio: Double,
    subjectWidthRatio: Double,
    subjectHeightRatio: Double,
    minimumMarginRatio: Double,
): CanonicalImageQa {
    val issues = mutableListOf<String>()
    if (width < 512 || height < 512) issues += "Слишком маленькое изображение"
    if (transparentRatio < 0.12) issues += "Фон изображения не прозрачный"
    if (subjectWidthRatio <= 0.08 || subjectHeightRatio <= 0.25) issues += "Персонаж слишком маленький или неполный"
    // Bounding-box size alone is not evidence of cropped anatomy: tall humanoids
    // naturally occupy more height than round animal mascots. Reject only when
    // opaque pixels actually reach the canvas edge (or virtually fill it).
    if (subjectWidthRatio > 0.97 || subjectHeightRatio > 0.97 || minimumMarginRatio < 0.008) {
        issues += "Персонаж обрезан или расположен слишком близко к краю"
    }
    return CanonicalImageQa(
        hardPass = issues.isEmpty(),
        blockingIssues = issues,
        width = width,
        height = height,
        transparentRatio = transparentRatio,
        subjectWidthRatio = subjectWidthRatio,
        subjectHeightRatio = subjectHeightRatio,
        minimumMarginRatio = minimumMarginRatio,
    )
}
