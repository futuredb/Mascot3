package com.generativemascot.app.state

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.random.Random

internal const val MINUTE = 60_000L
internal const val HOUR = 60 * MINUTE

@Serializable
enum class PetState(val action: String) {
    IDLE("idle"), REST("resting"), SLEEP("sleeping"), THINK("thinking"), WINDOW("at_glass"),
    OBSERVE("watching"), JOY("joyful"), SAD("sad"), ANGRY("angry"), REFUSE("refusal"),
    SCARED("frightened"), CURIOUS("curious"), TENDER("tender"), STRETCH("stretching"),
    GREET("greeting"), SIGNATURE("signature_move"), DANCE("dancing");
}

@Serializable
data class StateConfig(
    val version: Int = 3,
    val baseMinMinutes: Int = 30,
    val baseMaxMinutes: Int = 90,
    val reactionMinMinutes: Int = 10,
    val reactionMaxMinutes: Int = 20,
    val cooldownMinutes: Map<PetState, Int> = mapOf(PetState.THINK to 120, PetState.WINDOW to 360,
        PetState.JOY to 30, PetState.SAD to 120, PetState.ANGRY to 360, PetState.REFUSE to 360,
        PetState.SCARED to 720, PetState.CURIOUS to 240, PetState.TENDER to 120,
        PetState.GREET to 30, PetState.SIGNATURE to 300, PetState.DANCE to 120),
    val weekdayMorning: Map<PetState, Int> = mapOf(PetState.STRETCH to 35, PetState.DANCE to 30, PetState.JOY to 20, PetState.IDLE to 15),
    val weekdayDay: Map<PetState, Int> = mapOf(PetState.THINK to 50, PetState.OBSERVE to 30, PetState.IDLE to 20),
    val weekdayEvening: Map<PetState, Int> = mapOf(PetState.REST to 50, PetState.TENDER to 25, PetState.IDLE to 25),
    val weekendMorning: Map<PetState, Int> = mapOf(PetState.STRETCH to 30, PetState.DANCE to 30, PetState.JOY to 20, PetState.IDLE to 20),
    val weekendDay: Map<PetState, Int> = mapOf(PetState.IDLE to 35, PetState.OBSERVE to 25, PetState.TENDER to 20, PetState.REST to 20),
    val weekendEvening: Map<PetState, Int> = mapOf(PetState.REST to 50, PetState.TENDER to 30, PetState.IDLE to 20),
    val repeatPenalty: Double = 0.35,
    val rareFrequency: Double = 0.08,
    val cloudThinkThreshold: Int = 70,
    val cloudWindowThreshold: Int = 95,
    val cloudWindowHours: Int = 6,
    val weatherMaxGapMinutes: Int = 45,
    val windGustThreshold: Double = 65.0,
    val temperatureDeltaThreshold: Double = 5.0,
    val networkDebounceSeconds: Int = 45,
    val chargingDebounceMinutes: Int = 5,
    val greetingReturnMinutes: Int = 30,
    val refreshMinutes: Int = 30,
    val weatherPollMinutes: Int = 15,
    val healthPollMinutes: Int = 5,
    val temperatureDeltaWindowMinutes: Int = 180,
    val healthSnapshotMaxAgeMinutes: Int = 15,
    val wakeRecentMinutes: Int = 30,
    val exerciseMinimumMinutes: Int = 20,
    val exerciseRecentMinutes: Int = 60,
    val lowActivityCooldownMinutes: Int = 240,
    val activityReturnRecentMinutes: Int = 120,
    val durationMinutes: Map<PetState, List<Int>> = emptyMap(),
    val healthTriggersEnabled: Boolean = true,
    val healthBackgroundReadEnabled: Boolean = false,
    val sleepTriggersEnabled: Boolean = true,
    val exerciseTriggersEnabled: Boolean = true,
    val stepTriggersEnabled: Boolean = true,
    val activityGoalTriggerEnabled: Boolean = true,
    val personalMilestoneTriggerEnabled: Boolean = false,
    val activityGoalValue: Long = 8_000,
    val activeSteps30m: Long = 1_200,
    val lowSteps2h: Long = 100,
    val returningSteps15m: Long = 500,
) {
    fun validate(): StateConfig {
        require(version == 3)
        require(baseMinMinutes in 30..180 && baseMaxMinutes in baseMinMinutes..360)
        require(reactionMinMinutes in 1..120 && reactionMaxMinutes in reactionMinMinutes..180)
        require(cooldownMinutes.values.all { it in 0..10080 })
        require(listOf(weekdayMorning, weekdayDay, weekdayEvening, weekendMorning, weekendDay, weekendEvening)
            .all { it.isNotEmpty() && it.values.all { weight -> weight in 0..10000 } && it.values.sum() > 0 })
        require(repeatPenalty in 0.0..1.0 && rareFrequency in 0.0..1.0)
        require(cloudThinkThreshold in 0..100 && cloudWindowThreshold in cloudThinkThreshold..100)
        require(cloudWindowHours in 1..24 && weatherMaxGapMinutes in 15..120)
        require(windGustThreshold in 1.0..300.0 && temperatureDeltaThreshold in 0.5..40.0)
        require(networkDebounceSeconds in 30..60 && chargingDebounceMinutes in 1..60)
        require(greetingReturnMinutes in 1..1440 && refreshMinutes in 15..120)
        require(weatherPollMinutes in 5..120 && healthPollMinutes in 1..120)
        require(temperatureDeltaWindowMinutes in 30..1440 && healthSnapshotMaxAgeMinutes in 1..120)
        require(wakeRecentMinutes in 1..120 && exerciseMinimumMinutes in 1..360 && exerciseRecentMinutes in 1..360)
        require(lowActivityCooldownMinutes in 30..1440 && activityReturnRecentMinutes in 1..360)
        require(durationMinutes.values.all { it.size == 2 && it[0] in 1..360 && it[1] in it[0]..720 })
        require(activityGoalValue in 1..100000 && activeSteps30m > 0 && lowSteps2h >= 0 && returningSteps15m > 0)
        return this
    }
}

@Serializable
data class WeatherObservation(val at: Long, val temperature: Double, val cloudCover: Int,
    val condition: String, val precipitation: Double = 0.0, val windSpeed: Double = 0.0,
    val windGust: Double = 0.0, val fog: Boolean = false, val thunderstorm: Boolean = false)

@Serializable
data class HealthSnapshot(
    val stepsToday: Long? = null, val stepsLast15m: Long? = null, val stepsLast30m: Long? = null,
    val stepsLast2h: Long? = null, val stepWindowCovered: Boolean = false,
    val activeSleepSession: Boolean = false, val lastSleepEndAt: Long? = null,
    val sleepSessionId: String? = null,
    val activeExerciseSession: Boolean = false, val lastExerciseEndAt: Long? = null,
    val lastExerciseDurationMinutes: Long? = null, val exerciseSessionId: String? = null,
    val activityGoalValue: Long = 8000, val activityGoalReachedToday: Boolean = false,
    val sevenDayMaximum: Long? = null, val healthDataUpdatedAt: Long = 0,
)

@Serializable
data class StateRecord(
    val currentStateId: PetState = PetState.IDLE,
    val stateStartedAt: Long = 0, val stateExpiresAt: Long = 0, val triggerReason: String = "fallback",
    val priority: Int = 15, val revision: Long = 0, val heroId: String? = null,
    val lastShownAt: Map<PetState, Long> = emptyMap(), val lastTriggerAt: Map<PetState, Long> = emptyMap(),
    val manualSleep: Boolean = false, val previousNetworkType: String? = null,
    val networkChangedAt: Long? = null, val networkChangeFrom: String? = null,
    val isCharging: Boolean = false, val currentChargingSession: Long? = null,
    val chargingStateShown: Boolean = false, val weatherHistory: List<WeatherObservation> = emptyList(),
    val healthSnapshot: HealthSnapshot? = null, val lastHealthEventAt: Map<String, Long> = emptyMap(),
    val lastLowActivityAt: Long? = null, val lastAppLeftAt: Long? = null,
    val previousBaseState: PetState? = null, val consecutiveBaseCount: Int = 0,
    val lastRenderedAction: String? = null,
)

data class StateSignals(val networkType: String? = null, val charging: Boolean? = null,
    val weather: WeatherObservation? = null, val health: HealthSnapshot? = null)
enum class StateEvent { AUTO, TAP, HOLD, JOY, ENTER, CREATED, CLIP_FINISHED }

/** Pure resolver: no network, model calls, Android globals or UI-specific selection. */
class StateResolver(private val random: Random = Random.Default) {
    private data class Candidate(val state: PetState, val reason: String, val priority: Int,
        val minMinutes: Int = 10, val maxMinutes: Int = 20, val healthKey: String? = null)

    fun resolve(old: StateRecord, now: Long, config: StateConfig = StateConfig(),
        signals: StateSignals = StateSignals(), event: StateEvent = StateEvent.AUTO,
        available: Set<String> = emptySet(), durations: Map<String, Long> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault()): StateRecord {
        var r = capture(old, now, signals, config)
        val time = Instant.ofEpochMilli(now).atZone(zone)
        val night = time.hour >= 23 || time.hour < 7
        val active = r.stateStartedAt <= now && now < r.stateExpiresAt
        fun allowed(s: PetState) = r.lastTriggerAt[s]?.let { now - it >= (config.cooldownMinutes[s] ?: 0) * MINUTE } ?: true
        fun healthAllowed(key: String) = key !in r.lastHealthEventAt
        if (event == StateEvent.TAP) r = r.copy(manualSleep = false)
        if (event == StateEvent.HOLD || r.manualSleep) {
            if (r.manualSleep && active && event != StateEvent.HOLD) return r
            return choose(r.copy(manualSleep = true), Candidate(PetState.SLEEP, "manual_sleep", 1), now, Long.MAX_VALUE, false)
        }
        if (event == StateEvent.JOY) return choose(r, Candidate(PetState.JOY, "user_swipe", 2), now,
            now + (config.durationMinutes[PetState.JOY]?.let { minutes(it[0], it[1]) }
                ?: minutes(config.reactionMinMinutes, config.reactionMaxMinutes)), false)
        if (active && r.priority == 2 && event != StateEvent.TAP) return r
        if (event == StateEvent.CLIP_FINISHED && r.currentStateId in setOf(PetState.GREET, PetState.SIGNATURE)) {
            r = r.copy(stateExpiresAt = now)
        }
        val candidates = mutableListOf<Candidate>()
        val weather = r.weatherHistory.lastOrNull()?.takeIf { now - it.at in 0..config.weatherMaxGapMinutes * MINUTE }
        if (weather != null) {
            val earlier = r.weatherHistory.minByOrNull { abs(it.at - (now - config.temperatureDeltaWindowMinutes * MINUTE)) }
                ?.takeIf { abs(it.at - (now - config.temperatureDeltaWindowMinutes * MINUTE)) <= config.weatherMaxGapMinutes * MINUTE }
            if (weather.thunderstorm || weather.windGust >= config.windGustThreshold ||
                (earlier != null && abs(weather.temperature - earlier.temperature) >= config.temperatureDeltaThreshold)) {
                if (allowed(PetState.SCARED)) candidates += Candidate(PetState.SCARED, "weather_strong", 3)
            }
        }
        val h = r.healthSnapshot?.takeIf { config.healthTriggersEnabled && now - it.healthDataUpdatedAt in 0..config.healthSnapshotMaxAgeMinutes * MINUTE }
        if (night || (config.sleepTriggersEnabled && h?.activeSleepSession == true)) {
            candidates += Candidate(PetState.SLEEP, if (night) "night" else "health_sleep", 4)
        }
        if (weather != null && weather.precipitation > 0 && !weather.thunderstorm && allowed(PetState.SAD)) {
            candidates += Candidate(PetState.SAD, "weather_rain", 5, 20, 40)
        }
        if (h != null) {
            val goalKey = "goal:${time.toLocalDate()}"
            if (config.activityGoalTriggerEnabled && h.activityGoalReachedToday && healthAllowed(goalKey))
                candidates += Candidate(PetState.JOY, "health_goal", 6, 10, 20, goalKey)
            val end = h.lastExerciseEndAt
            val workoutKey = "exercise:${h.exerciseSessionId ?: end}"
            if (config.exerciseTriggersEnabled && end != null && now - end in 0..config.exerciseRecentMinutes * MINUTE &&
                (h.lastExerciseDurationMinutes ?: 0) >= config.exerciseMinimumMinutes && healthAllowed(workoutKey))
                candidates += Candidate(PetState.REST, "health_exercise_end", 6, 20, 40, workoutKey)
            val wake = h.lastSleepEndAt
            val wakeKey = "wake:${h.sleepSessionId ?: wake}"
            if (config.sleepTriggersEnabled && wake != null && now - wake in 0..config.wakeRecentMinutes * MINUTE && healthAllowed(wakeKey))
                candidates += Candidate(PetState.STRETCH, "health_wake", 6, 10, 20, wakeKey)
            if (config.stepTriggersEnabled && !night) {
                if (h.stepWindowCovered && h.stepsLast2h != null && h.stepsLast2h < config.lowSteps2h && time.hour in 9..20) {
                    r = r.copy(lastLowActivityAt = now)
                    val lastLow = r.lastHealthEventAt["low_activity"]
                    if (lastLow == null || now - lastLow >= config.lowActivityCooldownMinutes * MINUTE)
                        candidates += Candidate(PetState.WINDOW, "health_low_activity", 8, 20, 40, "low_activity")
                }
                val lowAt = r.lastLowActivityAt
                val returned = "return_activity:$lowAt"
                if (lowAt != null && now - lowAt in 0..config.activityReturnRecentMinutes * MINUTE && (h.stepsLast15m ?: -1) >= config.returningSteps15m &&
                    allowed(PetState.CURIOUS) && healthAllowed(returned))
                    candidates += Candidate(PetState.CURIOUS, "health_activity_return", 7, 10, 20, returned)
            }
        }
        if (r.networkChangedAt != null && now - r.networkChangedAt!! >= config.networkDebounceSeconds * 1000L && allowed(PetState.CURIOUS))
            candidates += Candidate(PetState.CURIOUS, "network_changed", 7)
        if (weather != null && allowed(PetState.WINDOW)) {
            val since = now - config.cloudWindowHours * HOUR
            val history = r.weatherHistory.filter { it.at >= since - config.weatherMaxGapMinutes * MINUTE }
            if (history.size >= 2 && history.first().at <= since &&
                history.all { it.cloudCover >= config.cloudWindowThreshold } &&
                history.zipWithNext().all { (a, b) -> b.at - a.at <= config.weatherMaxGapMinutes * MINUTE })
                candidates += Candidate(PetState.WINDOW, "weather_long_cloud", 8, 20, 40)
        }
        if (r.isCharging && r.currentChargingSession != null && !r.chargingStateShown &&
            now - r.currentChargingSession!! >= config.chargingDebounceMinutes * MINUTE)
            candidates += Candidate(PetState.STRETCH, "charging", 9)
        if (weather != null && (weather.cloudCover >= config.cloudThinkThreshold || weather.fog) && allowed(PetState.THINK))
            candidates += Candidate(PetState.THINK, "weather_cloud", 10)
        if (!night && "greeting" in available && allowed(PetState.GREET) &&
            (event == StateEvent.CREATED || event == StateEvent.ENTER &&
                (r.lastAppLeftAt == null || now - r.lastAppLeftAt!! >= config.greetingReturnMinutes * MINUTE)))
            candidates += Candidate(PetState.GREET, "app_entry", 11)
        if (h != null && !night) {
            if (config.exerciseTriggersEnabled && h.activeExerciseSession ||
                config.stepTriggersEnabled && (h.stepsLast30m ?: -1) >= config.activeSteps30m) {
                if (allowed(PetState.DANCE)) candidates += Candidate(PetState.DANCE, "health_active", 12, 20, 40)
            }
            val milestone = "milestone:${time.toLocalDate()}"
            if (config.personalMilestoneTriggerEnabled && h.stepsToday != null && h.sevenDayMaximum != null &&
                h.stepsToday > h.sevenDayMaximum && healthAllowed(milestone) && allowed(PetState.SIGNATURE))
                candidates += Candidate(PetState.SIGNATURE, "health_milestone", 12, 0, 0, milestone)
        }
        val candidate = candidates.minByOrNull { it.priority }
        val stillActive = r.stateStartedAt <= now && now < r.stateExpiresAt
        if (candidate != null && (event == StateEvent.TAP || !stillActive || candidate.priority < r.priority)) {
            val expires = when (candidate.state) {
                PetState.SLEEP -> if (night) time.toLocalDate().plusDays(if (time.hour >= 23) 1 else 0)
                    .atTime(7, 0).atZone(zone).toInstant().toEpochMilli() else now + 30 * MINUTE
                PetState.GREET, PetState.SIGNATURE -> now + (durations[candidate.state.action] ?: 6000L).coerceIn(1000, 120000)
                else -> config.durationMinutes[candidate.state]?.let { now + minutes(it[0], it[1]) }
                    ?: (now + minutes(candidate.minMinutes, candidate.maxMinutes))
            }
            return choose(r, candidate, now, expires, false)
        }
        if (stillActive && event != StateEvent.TAP) return r
        if (night) return choose(r, Candidate(PetState.SLEEP, "night", 4), now,
            time.toLocalDate().plusDays(if (time.hour >= 23) 1 else 0).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(), false)
        val weekend = time.dayOfWeek.value >= 6
        val weights = when {
            weekend && time.hour < 11 -> config.weekendMorning
            weekend && time.hour < 18 -> config.weekendDay
            weekend -> config.weekendEvening
            time.hour < 10 -> config.weekdayMorning
            time.hour < 18 -> config.weekdayDay
            else -> config.weekdayEvening
        }
        // Base THINK is not suppressed by the separate weather cooldown.
        val pool = weights.filter { (s, weight) -> weight > 0 && s != PetState.SLEEP &&
            (s !in setOf(PetState.JOY, PetState.TENDER, PetState.DANCE) || allowed(s)) }
            .filterKeys { available.isEmpty() || it.action in available }
        val rares = listOf(PetState.ANGRY, PetState.REFUSE, PetState.SIGNATURE, PetState.DANCE) +
            if (weekend || time.hour >= 18) listOf(PetState.TENDER) else emptyList()
        val rarePool = rares
            .filter { allowed(it) && it.action in available && (event != StateEvent.TAP || it != r.currentStateId) }
        if (rarePool.isNotEmpty() && random.nextDouble() < config.rareFrequency) {
            val state = rarePool.random(random)
            val range = config.durationMinutes[state] ?: when (state) {
                PetState.DANCE, PetState.TENDER -> listOf(20, 40)
                else -> listOf(config.reactionMinMinutes, config.reactionMaxMinutes)
            }
            return choose(r, Candidate(state, "rare_rotation", 14), now,
                now + if (state == PetState.SIGNATURE) (durations[state.action] ?: 6000L) else minutes(range[0], range[1]), false)
        }
        val tapPool = if (event == StateEvent.TAP && pool.size > 1) pool.filterKeys { it != r.currentStateId } else pool
        val adjusted = tapPool.mapValues { (s, weight) -> weight.toDouble() *
            if (r.previousBaseState == s && r.consecutiveBaseCount >= 2 && tapPool.size > 1) config.repeatPenalty else 1.0 }
        val state = weighted(adjusted) ?: PetState.IDLE
        val baseRange = config.durationMinutes[state] ?: when (state) {
            PetState.JOY -> listOf(30, 60)
            PetState.DANCE -> listOf(30, 40)
            PetState.TENDER -> listOf(30, 40)
            else -> listOf(config.baseMinMinutes, config.baseMaxMinutes)
        }
        return choose(r, Candidate(state, if (state == PetState.IDLE && pool.isEmpty()) "fallback" else "time_weighted", 13),
            now, now + minutes(baseRange[0].coerceAtLeast(config.baseMinMinutes), baseRange[1].coerceAtLeast(config.baseMinMinutes)), true)
    }

    private fun capture(old: StateRecord, now: Long, signals: StateSignals, config: StateConfig): StateRecord {
        var r = old.copy(lastTriggerAt = old.lastTriggerAt.filterValues { it <= now },
            stateExpiresAt = if (old.stateStartedAt > now) 0 else old.stateExpiresAt,
            currentChargingSession = old.currentChargingSession?.coerceAtMost(now),
            lastAppLeftAt = old.lastAppLeftAt?.takeIf { it <= now })
        signals.weather?.takeIf { it.at <= now && r.weatherHistory.lastOrNull()?.at != it.at }?.let {
            r = r.copy(weatherHistory = (r.weatherHistory + it).filter { w -> now - w.at in 0..26 * HOUR }.takeLast(160))
        }
        signals.health?.let { r = r.copy(healthSnapshot = it) }
        signals.networkType?.let { type ->
            val previous = r.previousNetworkType
            if (type != previous) {
                val relevant = previous != null && ((previous == "WIFI" && type == "CELLULAR") ||
                    (previous == "CELLULAR" && type == "WIFI") || (previous != "NONE" && type == "NONE"))
                val reverted = type == r.networkChangeFrom
                r = r.copy(previousNetworkType = type,
                    networkChangedAt = if (reverted || !relevant) null else now,
                    networkChangeFrom = if (reverted || !relevant) null else previous)
            }
        }
        signals.charging?.let { charging ->
            if (charging != r.isCharging) r = r.copy(isCharging = charging,
                currentChargingSession = if (charging) now else null, chargingStateShown = false)
        }
        return r
    }

    private fun choose(r: StateRecord, c: Candidate, now: Long, expires: Long, base: Boolean): StateRecord = r.copy(
        currentStateId = c.state, triggerReason = c.reason, priority = c.priority,
        stateStartedAt = now, stateExpiresAt = expires, revision = r.revision + 1,
        lastShownAt = r.lastShownAt + (c.state to now), lastTriggerAt = r.lastTriggerAt + (c.state to now),
        chargingStateShown = r.chargingStateShown || c.reason == "charging",
        networkChangedAt = if (c.reason == "network_changed") null else r.networkChangedAt,
        networkChangeFrom = if (c.reason == "network_changed") null else r.networkChangeFrom,
        lastHealthEventAt = if (c.healthKey != null) (r.lastHealthEventAt + (c.healthKey to now))
            .filterValues { now - it <= 8 * 24 * HOUR } else r.lastHealthEventAt,
        previousBaseState = if (base) c.state else r.previousBaseState,
        consecutiveBaseCount = if (base) if (r.previousBaseState == c.state) r.consecutiveBaseCount + 1 else 1 else r.consecutiveBaseCount,
    )

    private fun minutes(min: Int, max: Int): Long = random.nextInt(min, max + 1) * MINUTE
    private fun weighted(weights: Map<PetState, Double>): PetState? {
        val sum = weights.values.sum()
        if (sum <= 0) return null
        var roll = random.nextDouble() * sum
        for ((state, weight) in weights) { roll -= weight; if (roll < 0) return state }
        return weights.keys.lastOrNull()
    }
}
