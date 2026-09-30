package com.generativemascot.app.state

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

object HealthSignals {
    val permissions = setOf(HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class))
    fun requiredPermissions(config: StateConfig): Set<String> = buildSet {
        if (!config.healthTriggersEnabled) return@buildSet
        if (config.stepTriggersEnabled || config.activityGoalTriggerEnabled || config.personalMilestoneTriggerEnabled)
            add(HealthPermission.getReadPermission(StepsRecord::class))
        if (config.sleepTriggersEnabled) add(HealthPermission.getReadPermission(SleepSessionRecord::class))
        if (config.exerciseTriggersEnabled) add(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
    }
    fun available(context: Context): Boolean = runCatching {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }.getOrDefault(false)
    fun backgroundAvailable(context: Context): Boolean = available(context) && runCatching {
        HealthConnectClient.getOrCreate(context).features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)
    suspend fun backgroundGranted(context: Context): Boolean = backgroundAvailable(context) && runCatching {
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in HealthConnectClient.getOrCreate(context)
            .permissionController.getGrantedPermissions()
    }.getOrDefault(false)

    suspend fun read(context: Context, config: StateConfig, now: Long): HealthSnapshot {
        val empty = HealthSnapshot(activityGoalValue = config.activityGoalValue)
        if (!config.healthTriggersEnabled || !available(context)) return empty
        try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            val midnight = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            suspend fun steps(start: Long, end: Long): Long? = if (end <= start) null else client.aggregate(AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))
            ))[StepsRecord.COUNT_TOTAL]
            var snapshot = empty
            if (HealthPermission.getReadPermission(StepsRecord::class) in granted &&
                (config.stepTriggersEnabled || config.activityGoalTriggerEnabled || config.personalMilestoneTriggerEnabled)) {
                try {
                val total = steps(midnight, now)
                val records = mutableListOf<StepsRecord>()
                var token: String? = null
                var pages = 0
                do {
                    val response = client.readRecords(ReadRecordsRequest(StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(now - 2 * HOUR), Instant.ofEpochMilli(now)),
                        pageSize = 1000, pageToken = token))
                    records += response.records
                    token = response.pageToken?.takeIf(String::isNotBlank)
                    pages++
                } while (token != null && pages < 20)
                // Sparse/absent samples are not proof of two hours of inactivity.
                val covered = token == null && stepWindowHasCoverage(records.map { it.startTime.toEpochMilli() to it.endTime.toEpochMilli() }, now)
                val maximum = if (config.personalMilestoneTriggerEnabled) (1..7).mapNotNull { day ->
                    val start = today.minusDays(day.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val end = today.minusDays(day.toLong() - 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    steps(start, end)
                }.maxOrNull() else null
                snapshot = snapshot.copy(stepsToday = total, stepsLast15m = steps(now - 15 * MINUTE, now),
                    stepsLast30m = steps(now - 30 * MINUTE, now), stepsLast2h = steps(now - 2 * HOUR, now),
                    stepWindowCovered = covered, activityGoalReachedToday = total != null && total >= config.activityGoalValue,
                    sevenDayMaximum = maximum)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A steps-provider error must not discard sleep/exercise data. */ }
            }
            if (config.sleepTriggersEnabled && HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
                try {
                val sessions = mutableListOf<SleepSessionRecord>()
                var token: String? = null
                var pages = 0
                do {
                    val result = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(now - 48 * HOUR), Instant.ofEpochMilli(now)),
                        pageToken = token))
                    sessions += result.records; token = result.pageToken?.takeIf(String::isNotBlank); pages++
                } while (token != null && sessions.size < 10000 && pages < 20)
                val ended = sessions.filter { it.endTime.toEpochMilli() <= now }.maxByOrNull { it.endTime }
                snapshot = snapshot.copy(activeSleepSession = sessions.any { it.startTime.toEpochMilli() <= now && now < it.endTime.toEpochMilli() },
                    lastSleepEndAt = ended?.endTime?.toEpochMilli(), sleepSessionId = ended?.metadata?.id?.takeIf(String::isNotBlank))
                // Health Connect stores interval records, not a guaranteed live "asleep now" signal.
                // Only an explicitly supplied interval containing now is active; never infer
                // sleep from yesterday's session or from missing data.
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Partial access remains useful; missing sleep is unknown, not asleep. */ }
            }
            if (config.exerciseTriggersEnabled && HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
                try {
                val sessions = mutableListOf<ExerciseSessionRecord>()
                var token: String? = null
                var pages = 0
                do {
                    val result = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(now - 6 * HOUR), Instant.ofEpochMilli(now)),
                        pageToken = token))
                    sessions += result.records; token = result.pageToken?.takeIf(String::isNotBlank); pages++
                } while (token != null && sessions.size < 10000 && pages < 20)
                snapshot = snapshot.copy(activeExerciseSession = sessions.any { it.startTime.toEpochMilli() <= now && now < it.endTime.toEpochMilli() })
                sessions.filter { it.endTime.toEpochMilli() <= now }.maxByOrNull { it.endTime }?.let {
                    snapshot = snapshot.copy(lastExerciseEndAt = it.endTime.toEpochMilli(),
                        lastExerciseDurationMinutes = java.time.Duration.between(it.startTime, it.endTime).toMinutes(),
                        exerciseSessionId = it.metadata.id.takeIf(String::isNotBlank)
                            ?: "${it.startTime.toEpochMilli()}:${it.endTime.toEpochMilli()}")
                }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep successfully read steps/sleep when exercise access is revoked. */ }
            }
            return snapshot.copy(healthDataUpdatedAt = now)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return empty } // Revoked permissions/provider errors never block the mascot.
    }
}

internal fun stepWindowHasCoverage(intervals: List<Pair<Long, Long>>, now: Long): Boolean {
    val start = now - 2 * HOUR
    var coveredUntil = start
    for ((from, to) in intervals.sortedBy { it.first }) {
        if (to < start || from > now || to <= from) continue
        if (from - coveredUntil > 15 * MINUTE) return false
        coveredUntil = maxOf(coveredUntil, to.coerceAtMost(now))
    }
    return coveredUntil >= now - 15 * MINUTE
}
