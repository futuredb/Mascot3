package com.generativemascot.app.state

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class StateResolverTest {
    private val now = Instant.parse("2026-09-21T12:00:00Z").toEpochMilli()
    private val all = PetState.entries.map { it.action }.toSet()
    private val config = StateConfig(rareFrequency = 0.0)
    private fun resolve(record: StateRecord = StateRecord(), at: Long = now,
        signals: StateSignals = StateSignals(), event: StateEvent = StateEvent.AUTO,
        cfg: StateConfig = config, available: Set<String> = all) = StateResolver(Random(42))
        .resolve(record, at, cfg, signals, event, available, mapOf("greeting" to 7000, "signature_move" to 8000), ZoneId.of("UTC"))
    private fun weather(at: Long = now, cloud: Int = 0, rain: Double = 0.0, storm: Boolean = false,
        gust: Double = 0.0, temp: Double = 20.0, fog: Boolean = false) =
        WeatherObservation(at, temp, cloud, "test", rain, windGust = gust, fog = fog, thunderstorm = storm)
    private fun health(at: Long = now) = HealthSnapshot(healthDataUpdatedAt = at)

    @Test fun allSeventeenIdsHaveUniqueExistingActions() {
        assertEquals(17, all.size)
        assertEquals(com.generativemascot.app.data.HeroLocalStore.LIBRARY_VIDEO_ACTIONS.toSet(), all)
    }
    @Test fun noExternalDataWorksWithTimeOnly() {
        val r = resolve()
        assertTrue(r.currentStateId in setOf(PetState.IDLE, PetState.THINK, PetState.OBSERVE))
        assertEquals("time_weighted", r.triggerReason)
        assertTrue(r.stateExpiresAt - now in 30 * MINUTE..90 * MINUTE)
    }
    @Test fun baseDoesNotChangeBeforeExpiry() {
        val r = resolve()
        assertEquals(r.revision, resolve(r, at = now + MINUTE).revision)
        assertEquals(r.currentStateId, resolve(r, at = now + MINUTE).currentStateId)
        assertTrue(resolve(r, at = r.stateExpiresAt).revision > r.revision)
    }
    @Test fun morningEveningWeekendRespectTheirCandidatePools() {
        for ((text, pool) in listOf("2026-09-21T08:00:00Z" to config.weekdayMorning,
            "2026-09-21T20:00:00Z" to config.weekdayEvening,
            "2026-09-20T12:00:00Z" to config.weekendDay)) {
            assertTrue(resolve(at = Instant.parse(text).toEpochMilli()).currentStateId in pool.keys)
        }
    }
    @Test fun nightIsSleepAndExpiresAtLocalSeven() {
        val time = Instant.parse("2026-09-21T23:30:00Z").toEpochMilli()
        val r = resolve(at = time, event = StateEvent.ENTER)
        assertEquals(PetState.SLEEP, r.currentStateId)
        assertEquals(Instant.parse("2026-09-22T07:00:00Z").toEpochMilli(), r.stateExpiresAt)
    }
    @Test fun manualSleepSurvivesWeatherHealthAndEntryUntilNormalTap() {
        val sleep = resolve(event = StateEvent.HOLD)
        val r = resolve(sleep, at = now + MINUTE, signals = StateSignals(weather = weather(at = now + MINUTE, storm = true),
            health = health(now + MINUTE).copy(activityGoalReachedToday = true)), event = StateEvent.ENTER)
        assertTrue(r.manualSleep)
        assertEquals(PetState.SLEEP, r.currentStateId)
        assertEquals(Long.MAX_VALUE, r.stateExpiresAt)
        assertFalse(resolve(r, at = now + 2 * MINUTE, event = StateEvent.TAP).manualSleep)
    }
    @Test fun upwardSwipeDoesNotUnlockManualSleep() {
        val r = resolve(resolve(event = StateEvent.HOLD), event = StateEvent.JOY)
        assertEquals(PetState.SLEEP, r.currentStateId)
        assertTrue(r.manualSleep)
    }
    @Test fun joySwipeHasPriorityOverStrongWeatherAndNight() {
        val time = Instant.parse("2026-09-21T23:30:00Z").toEpochMilli()
        val r = resolve(at = time, signals = StateSignals(weather = weather(at = time, storm = true)), event = StateEvent.JOY)
        assertEquals(PetState.JOY, r.currentStateId)
        assertEquals(2, r.priority)
        assertEquals(r.revision, resolve(r, at = time + MINUTE, signals = StateSignals(weather = weather(at = time + MINUTE, storm = true))).revision)
    }
    @Test fun rainCloudFogAndStormHaveCorrectStates() {
        assertEquals(PetState.SAD, resolve(signals = StateSignals(weather = weather(rain = 1.0))).currentStateId)
        assertEquals(PetState.THINK, resolve(signals = StateSignals(weather = weather(cloud = 70))).currentStateId)
        assertEquals(PetState.THINK, resolve(signals = StateSignals(weather = weather(fog = true))).currentStateId)
        assertEquals(PetState.SCARED, resolve(signals = StateSignals(weather = weather(storm = true, rain = 1.0))).currentStateId)
        assertEquals(PetState.SCARED, resolve(signals = StateSignals(weather = weather(gust = 70.0))).currentStateId)
    }
    @Test fun temperatureDeltaNeedsAnActualThreeHourObservation() {
        val history = StateRecord(weatherHistory = listOf(weather(now - 3 * HOUR, temp = 10.0)))
        assertEquals(PetState.SCARED, resolve(history, signals = StateSignals(weather = weather(temp = 15.0))).currentStateId)
        assertNotEquals(PetState.SCARED, resolve(signals = StateSignals(weather = weather(temp = 15.0))).currentStateId)
    }
    @Test fun cloudWindowNeedsSixHoursOfContinuousHistoryNotOneCloudySample() {
        val history = (0..12).map { weather(now - 6 * HOUR + it * 30 * MINUTE, cloud = 99) }
        assertEquals(PetState.WINDOW, resolve(StateRecord(weatherHistory = history)).currentStateId)
        assertNotEquals(PetState.WINDOW, resolve(signals = StateSignals(weather = weather(cloud = 99))).currentStateId)
        assertNotEquals(PetState.WINDOW, resolve(StateRecord(weatherHistory = listOf(history.first(), history.last()))).currentStateId)
    }
    @Test fun staleWeatherIsIgnored() {
        assertNotEquals(PetState.SCARED, resolve(StateRecord(weatherHistory = listOf(weather(now - 2 * HOUR, storm = true)))).currentStateId)
    }
    @Test fun strongWeatherInterruptsBaseButCooldownPreventsRepeatedFear() {
        val r = resolve(resolve(), signals = StateSignals(weather = weather(storm = true)))
        assertEquals(PetState.SCARED, r.currentStateId)
        assertNotEquals(PetState.SCARED, resolve(r, at = r.stateExpiresAt,
            signals = StateSignals(weather = weather(at = r.stateExpiresAt, storm = true))).currentStateId)
    }
    @Test fun chargingStartsOneSessionAndShowsStretchOnlyAfterFiveMinutes() {
        val start = resolve(signals = StateSignals(charging = true))
        assertFalse(start.chargingStateShown)
        val r = resolve(start, at = now + 5 * MINUTE, signals = StateSignals(charging = true))
        assertEquals(PetState.STRETCH, r.currentStateId)
        assertTrue(r.chargingStateShown)
        assertNotEquals("charging", resolve(r, at = r.stateExpiresAt, signals = StateSignals(charging = true)).triggerReason)
        val unplugged = resolve(r, at = now + 30 * MINUTE, signals = StateSignals(charging = false))
        assertNull(unplugged.currentChargingSession)
        assertFalse(unplugged.chargingStateShown)
    }
    @Test fun networkChangeIsDebouncedAndThenConsumed() {
        val first = resolve(signals = StateSignals(networkType = "WIFI"))
        assertNull(first.networkChangedAt)
        val changed = resolve(first, at = now + 1000, signals = StateSignals(networkType = "CELLULAR"))
        assertNotEquals(PetState.CURIOUS, changed.currentStateId)
        val r = resolve(changed, at = now + 46000, signals = StateSignals(networkType = "CELLULAR"))
        assertEquals(PetState.CURIOUS, r.currentStateId)
        assertNull(r.networkChangedAt)
    }
    @Test fun transientNetworkFlapDoesNotTriggerCuriosity() {
        val first = resolve(signals = StateSignals(networkType = "WIFI"))
        val changed = resolve(first, at = now + 1000, signals = StateSignals(networkType = "CELLULAR"))
        val back = resolve(changed, at = now + 2000, signals = StateSignals(networkType = "WIFI"))
        assertNull(back.networkChangedAt)
        assertNotEquals(PetState.CURIOUS, resolve(back, at = now + MINUTE).currentStateId)
    }
    @Test fun offlineTransitionIsARelevantNetworkChange() {
        val first = resolve(signals = StateSignals(networkType = "WIFI"))
        val offline = resolve(first, signals = StateSignals(networkType = "NONE"))
        assertEquals(PetState.CURIOUS, resolve(offline, at = now + MINUTE).currentStateId)
    }
    @Test fun greetingRequiresThirtyMinuteReturnAndDoesNotInterruptNight() {
        assertEquals(PetState.GREET, resolve(event = StateEvent.CREATED).currentStateId)
        assertEquals(now + 7000, resolve(event = StateEvent.CREATED).stateExpiresAt)
        assertNotEquals(PetState.GREET, resolve(StateRecord(lastAppLeftAt = now - MINUTE), event = StateEvent.ENTER).currentStateId)
        assertEquals(PetState.GREET, resolve(StateRecord(lastAppLeftAt = now - 30 * MINUTE), event = StateEvent.ENTER).currentStateId)
    }
    @Test fun greetingCompletionReResolvesAndCooldownPreventsImmediateGreetingAgain() {
        val r = resolve(event = StateEvent.CREATED)
        assertNotEquals(PetState.GREET, resolve(r, at = now + 7000, event = StateEvent.CLIP_FINISHED).currentStateId)
        assertNotEquals(PetState.GREET, resolve(r, at = now + MINUTE, event = StateEvent.CREATED).currentStateId)
    }
    @Test fun gestureTapSelectsNextBaseButNeverDancesAtNight() {
        val r = resolve()
        assertNotEquals(r.currentStateId, resolve(r, event = StateEvent.TAP).currentStateId)
        assertEquals(PetState.SLEEP, resolve(at = Instant.parse("2026-09-21T23:00:00Z").toEpochMilli(), event = StateEvent.TAP).currentStateId)
    }
    @Test fun missingAnimationsDoNotStartGenerationAndRenderingUsesStableFallback() {
        val r = resolve(available = setOf("greeting"))
        assertEquals(PetState.IDLE, r.currentStateId)
        assertEquals("greeting", renderedAction(r, setOf("greeting")))
        assertEquals("idle", renderedAction(r.copy(currentStateId = PetState.SAD), setOf("idle", "greeting")))
        assertEquals("thinking", renderedAction(r.copy(currentStateId = PetState.SAD, lastRenderedAction = "thinking"), setOf("thinking", "greeting")))
        assertEquals(PetState.IDLE, r.currentStateId)
    }
    @Test fun goalRewardAndWorkoutRestAreIdempotentAcrossRestarts() {
        val signal = health().copy(activityGoalReachedToday = true, lastExerciseEndAt = now - MINUTE,
            lastExerciseDurationMinutes = 30, exerciseSessionId = "workout1")
        val reward = resolve(signals = StateSignals(health = signal))
        assertEquals("health_goal", reward.triggerReason)
        val rest = resolve(reward, at = reward.stateExpiresAt,
            signals = StateSignals(health = signal.copy(healthDataUpdatedAt = reward.stateExpiresAt)))
        assertEquals("health_exercise_end", rest.triggerReason)
        val later = resolve(rest, at = rest.stateExpiresAt,
            signals = StateSignals(health = signal.copy(healthDataUpdatedAt = rest.stateExpiresAt)))
        assertFalse(later.triggerReason in setOf("health_goal", "health_exercise_end"))
    }
    @Test fun wakingIsOncePerActualSleepEnd() {
        val signal = health().copy(lastSleepEndAt = now - MINUTE)
        val r = resolve(signals = StateSignals(health = signal))
        assertEquals("health_wake", r.triggerReason)
        assertNotEquals("health_wake", resolve(r, at = r.stateExpiresAt,
            signals = StateSignals(health = signal.copy(healthDataUpdatedAt = r.stateExpiresAt))).triggerReason)
    }
    @Test fun highActivityUsesDanceOnlyWhenDataFreshAndDaytime() {
        assertEquals("health_active", resolve(signals = StateSignals(health = health().copy(stepsLast30m = 1200))).triggerReason)
        assertNotEquals("health_active", resolve(signals = StateSignals(health = health(now - HOUR).copy(stepsLast30m = 1200))).triggerReason)
        assertNotEquals("health_active", resolve(signals = StateSignals(health = health().copy(stepsLast30m = 1200)),
            cfg = config.copy(healthTriggersEnabled = false)).triggerReason)
    }
    @Test fun unknownOrSparseStepDataNeverMeansLowActivity() {
        assertNotEquals("health_low_activity", resolve(signals = StateSignals(health = health())).triggerReason)
        assertNotEquals("health_low_activity", resolve(signals = StateSignals(health = health().copy(stepsLast2h = 0))).triggerReason)
        assertEquals("health_low_activity", resolve(signals = StateSignals(health = health().copy(stepsLast2h = 0, stepWindowCovered = true))).triggerReason)
    }
    @Test fun returningActivityUsesCuriousOnceAfterKnownLowPeriod() {
        val low = resolve(signals = StateSignals(health = health().copy(stepsLast2h = 0, stepWindowCovered = true)))
        val later = low.stateExpiresAt
        val signal = health(later).copy(stepsLast15m = 500)
        val returned = resolve(low, at = later, signals = StateSignals(health = signal))
        assertEquals("health_activity_return", returned.triggerReason)
        assertNotEquals("health_activity_return", resolve(returned, at = returned.stateExpiresAt,
            signals = StateSignals(health = signal.copy(healthDataUpdatedAt = returned.stateExpiresAt))).triggerReason)
    }
    @Test fun explicitActiveSleepAndExerciseSignalsRespectPriorities() {
        assertEquals(PetState.SLEEP, resolve(signals = StateSignals(health = health().copy(activeSleepSession = true))).currentStateId)
        assertEquals(PetState.DANCE, resolve(signals = StateSignals(health = health().copy(activeExerciseSession = true))).currentStateId)
        assertEquals(PetState.SCARED, resolve(signals = StateSignals(weather = weather(storm = true), health = health().copy(activeExerciseSession = true))).currentStateId)
    }
    @Test fun optionalMilestoneIsOffByDefaultAndOncePerDayWhenEnabled() {
        val signal = health().copy(stepsToday = 9000, sevenDayMaximum = 8000)
        assertNotEquals("health_milestone", resolve(signals = StateSignals(health = signal)).triggerReason)
        val cfg = config.copy(personalMilestoneTriggerEnabled = true)
        val r = resolve(signals = StateSignals(health = signal), cfg = cfg)
        assertEquals("health_milestone", r.triggerReason)
        assertNotEquals("health_milestone", resolve(r, at = r.stateExpiresAt, cfg = cfg,
            signals = StateSignals(health = signal.copy(healthDataUpdatedAt = r.stateExpiresAt))).triggerReason)
    }
    @Test fun configurableDurationsAndThresholdsActuallyChangeSelection() {
        val r = resolve(signals = StateSignals(weather = weather(rain = 1.0)), cfg = config.copy(durationMinutes = mapOf(PetState.SAD to listOf(25, 25))))
        assertEquals(25 * MINUTE, r.stateExpiresAt - now)
        assertNotEquals(PetState.SCARED, resolve(signals = StateSignals(weather = weather(gust = 70.0)), cfg = config.copy(windGustThreshold = 90.0)).currentStateId)
    }
    @Test fun configuredHealthRecencyAndUserReactionDurationAreApplied() {
        val h = health().copy(lastExerciseEndAt = now - 70 * MINUTE, lastExerciseDurationMinutes = 15)
        assertNotEquals("health_exercise_end", resolve(signals = StateSignals(health = h)).triggerReason)
        assertEquals("health_exercise_end", resolve(signals = StateSignals(health = h),
            cfg = config.copy(exerciseMinimumMinutes = 10, exerciseRecentMinutes = 90)).triggerReason)
        val joy = resolve(event = StateEvent.JOY, cfg = config.copy(durationMinutes = mapOf(PetState.JOY to listOf(12, 12))))
        assertEquals(12 * MINUTE, joy.stateExpiresAt - now)
    }
    @Test fun rareRotationHasItsOwnCooldownAndSignatureEndsAtActualClipDuration() {
        val cfg = config.copy(rareFrequency = 1.0)
        val r = resolve(cfg = cfg, available = setOf("idle", "signature_move"))
        assertEquals(PetState.SIGNATURE, r.currentStateId)
        assertEquals(8000L, r.stateExpiresAt - now)
        assertEquals(PetState.IDLE, resolve(r, at = r.stateExpiresAt, cfg = cfg, available = setOf("idle", "signature_move")).currentStateId)
    }
    @Test fun invalidConfigurationIsRejected() {
        config.validate()
        for (invalid in listOf(config.copy(networkDebounceSeconds = 1), config.copy(weekdayDay = emptyMap()),
            config.copy(activityGoalValue = 0), config.copy(repeatPenalty = -1.0), config.copy(baseMaxMinutes = 1))) {
            try { invalid.validate(); fail("Must reject invalid config") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun correctedSleepEndDoesNotRewardTheSameSessionTwice() {
        val h = health().copy(lastSleepEndAt = now - MINUTE, sleepSessionId = "sleep1")
        val wake = resolve(signals = StateSignals(health = h))
        assertEquals("health_wake", wake.triggerReason)
        assertNotEquals("health_wake", resolve(wake, at = wake.stateExpiresAt,
            signals = StateSignals(health = h.copy(lastSleepEndAt = now, healthDataUpdatedAt = wake.stateExpiresAt))).triggerReason)
    }
    @Test fun repeatedBaseStateGetsItsWeightReduced() {
        fun count(repeats: Int): Int = (0..999).count { seed ->
            val r = StateRecord(previousBaseState = PetState.THINK, consecutiveBaseCount = repeats)
            StateResolver(Random(seed)).resolve(r, now, config, available = all, zone = ZoneId.of("UTC")).currentStateId == PetState.THINK
        }
        assertTrue(count(2) < count(1) * 0.7)
    }
    @Test fun danceCanBeRareInDaytimeWithoutHealthOrMorningWindow() {
        val r = resolve(cfg = config.copy(rareFrequency = 1.0), available = setOf("idle", "dancing"))
        assertEquals(PetState.DANCE, r.currentStateId)
        assertEquals("rare_rotation", r.triggerReason)
    }
    @Test fun allRareOnlyActionsAreReachableWithoutInventingExternalTriggers() {
        for (s in listOf(PetState.ANGRY, PetState.REFUSE, PetState.SIGNATURE)) {
            assertEquals(s, resolve(cfg = config.copy(rareFrequency = 1.0), available = setOf("idle", s.action)).currentStateId)
        }
    }
    @Test fun clockRollbackDoesNotLeaveFutureCooldownOrChargingTimes() {
        val r = resolve(StateRecord(stateStartedAt = now + HOUR, stateExpiresAt = now + 2 * HOUR,
            lastTriggerAt = mapOf(PetState.GREET to now + HOUR), lastAppLeftAt = now + HOUR), event = StateEvent.ENTER)
        assertEquals(PetState.GREET, r.currentStateId)
        assertEquals(now, r.stateStartedAt)
    }
    @Test fun stepCoverageNeedsContinuousSamplesNotTwoEndpoints() {
        assertFalse(stepWindowHasCoverage(emptyList(), now))
        assertFalse(stepWindowHasCoverage(listOf(now - 2 * HOUR to now - 110 * MINUTE, now - 10 * MINUTE to now), now))
        assertTrue(stepWindowHasCoverage((0..7).map { now - 2 * HOUR + it * 15 * MINUTE to now - 2 * HOUR + (it + 1) * 15 * MINUTE }, now))
        assertTrue(stepWindowHasCoverage(listOf(now - 3 * HOUR to now), now))
    }
}
