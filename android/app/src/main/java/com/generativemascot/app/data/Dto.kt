package com.generativemascot.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CityDto(
    val id: String,
    val name: String,
    val country: String = "",
    val timezone: String,
    val lat: Double,
    val lon: Double,
)

@Serializable
data class AssetRefDto(
    val url: String,
    val sha256: String,
    @SerialName("mime_type") val mimeType: String? = null,
    val format: String? = null,
)

@Serializable
data class FrameSequenceDto(
    val frames: List<AssetRefDto> = emptyList(),
    val fps: Int = 6,
)

@Serializable
data class StateAssetsDto(
    val still: AssetRefDto? = null,
    val animation: AssetRefDto? = null,
    val sequence: FrameSequenceDto? = null,
    val status: String = "pending",
)

@Serializable
data class SpriteGeometryDto(
    @SerialName("cell_width") val cellWidth: Int = 192,
    @SerialName("cell_height") val cellHeight: Int = 208,
    @SerialName("frames_per_row") val framesPerRow: Int = 8,
    val rows: Int = 0,
    @SerialName("atlas_width") val atlasWidth: Int = 0,
    @SerialName("atlas_height") val atlasHeight: Int = 0,
    val fps: Int = 6,
)

@Serializable
data class SpriteRowDto(
    val row: Int,
    val family: String,
    val states: List<String> = emptyList(),
    @SerialName("preview_url") val previewUrl: String? = null,
)

@Serializable
data class SpritePackDto(
    val version: Int = 1,
    val format: String = "mascot-sprite-atlas",
    val source: String = "",
    @SerialName("production_eligible") val productionEligible: Boolean = false,
    val approval: String = "awaiting_visual_review",
    val geometry: SpriteGeometryDto = SpriteGeometryDto(),
    @SerialName("state_to_family") val stateToFamily: Map<String, String> = emptyMap(),
    val rows: List<SpriteRowDto> = emptyList(),
    val atlas: AssetRefDto? = null,
)

@Serializable
data class PuppetPackDto(
    val version: Int = 1,
    val format: String = "layered-2d-puppet",
    @SerialName("rig_preset") val rigPreset: String = "friendly_blob_v1",
    val parts: Map<String, AssetRefDto> = emptyMap(),
    val actions: List<String> = emptyList(),
)

@Serializable
data class ManifestDto(
    @SerialName("mascot_id") val mascotId: String,
    val version: Int,
    val base: AssetRefDto? = null,
    @SerialName("base_sequence") val baseSequence: FrameSequenceDto? = null,
    val states: Map<String, StateAssetsDto> = emptyMap(),
    @SerialName("sprite_pack") val spritePack: SpritePackDto? = null,
    @SerialName("puppet_pack") val puppetPack: PuppetPackDto? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class MascotDto(
    val id: String,
    val name: String? = null,
    val status: String,
    @SerialName("reroll_used") val rerollUsed: Int = 0,
    @SerialName("reroll_limit") val rerollLimit: Int = 1,
    @SerialName("prompt_version") val promptVersion: String = "",
    val stages: Map<String, String> = emptyMap(),
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("preview_animation_url") val previewAnimationUrl: String? = null,
)

@Serializable
data class GenerationPackDto(
    @SerialName("mascot_id") val mascotId: String,
    val kind: String,
    val pack: Int,
    val requested: List<String> = emptyList(),
    val ready: List<String> = emptyList(),
    val queued: List<String> = emptyList(),
    val blocked: List<String> = emptyList(),
)

@Serializable
data class ContextDto(
    @SerialName("state_key") val stateKey: String,
    @SerialName("reason_code") val reasonCode: String,
    @SerialName("reason_text") val reasonText: String,
    @SerialName("city_name") val cityName: String? = null,
    @SerialName("observed_at") val observedAt: String? = null,
    @SerialName("weather_class") val weatherClass: String? = null,
    val temperature: Double? = null,
    @SerialName("still_url") val stillUrl: String? = null,
    @SerialName("animation_url") val animationUrl: String? = null,
    @SerialName("animation_frames") val animationFrames: List<String> = emptyList(),
    @SerialName("animation_fps") val animationFps: Int = 6,
    @SerialName("playback_mode") val playbackMode: String = "idle_pulse",
    @SerialName("motion_ms") val motionMs: Int = 7000,
    @SerialName("pause_ms") val pauseMs: Int = 50000,
)

@Serializable
data class NameBody(val name: String?)

@Serializable
data class CityBody(
    @SerialName("city_id") val cityId: String? = null,
    val name: String,
    val country: String = "",
    val timezone: String,
    val lat: Double,
    val lon: Double,
)

@Serializable
data class WidgetBody(
    @SerialName("mascot_id") val mascotId: String,
    @SerialName("app_widget_id") val appWidgetId: String,
    @SerialName("size_class") val sizeClass: String = "small",
)
