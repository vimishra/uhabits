/*
 * This file is part of Loop Habit Tracker (personal fork).
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
@file:OptIn(ExperimentalMindfulnessSessionApi::class)

package org.isoron.uhabits.hc

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.preference.PreferenceManager
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.AutoSource
import org.isoron.uhabits.core.models.Habit
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlin.reflect.KClass
import org.isoron.platform.time.LocalDate as LoopDate

/**
 * Reads exercise, mindfulness and step data from Health Connect and writes daily totals into
 * habits that have an [AutoSource] configured.
 */
object HealthConnectSync {
    private const val TAG = "HealthConnectSync"

    /** Garmin Connect's package name; preferred source for exercise when present. */
    const val GARMIN_PACKAGE = "com.garmin.android.apps.connectmobile"

    val READ_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(MindfulnessSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    val ALL_PERMISSIONS: Set<String> =
        READ_PERMISSIONS + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** Lets Health Connect return data older than 30 days before the first permission grant. */
    const val HISTORY_PERMISSION = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /** How far back the one-time backfill of each auto-tracked habit reaches. */
    const val BACKFILL_DAYS = 365

    private const val PREF_BACKFILLED = "hc_backfilled"
    private const val PREF_HISTORY_ASKED = "hc_history_asked"

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun client(context: Context): HealthConnectClient? =
        if (isAvailable(context)) HealthConnectClient.getOrCreate(context) else null

    suspend fun isHistoryAvailable(context: Context): Boolean {
        val client = client(context) ?: return false
        return try {
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (e: Exception) {
            false
        }
    }

    /**
     * True the first time history access is available but not granted; records that it was
     * asked, so the user is only prompted once.
     */
    suspend fun shouldAskForHistory(context: Context): Boolean {
        val client = client(context) ?: return false
        if (!isHistoryAvailable(context)) return false
        if (HISTORY_PERMISSION in client.permissionController.getGrantedPermissions()) return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_HISTORY_ASKED, false)) return false
        prefs.edit().putBoolean(PREF_HISTORY_ASKED, true).apply()
        return true
    }

    suspend fun missingPermissions(context: Context): Set<String> {
        val client = client(context) ?: return ALL_PERMISSIONS
        val granted = client.permissionController.getGrantedPermissions()
        return ALL_PERMISSIONS - granted
    }

    /**
     * Returns the packages that have written data of the given kind in the last 30 days,
     * most frequent first.
     */
    suspend fun listSourceApps(context: Context, source: AutoSource): List<String> {
        val client = client(context) ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val end = Instant.now()
        val start = LocalDate.now(zone).minusDays(30).atStartOfDay(zone).toInstant()
        return try {
            val records: List<Record> = when (source) {
                AutoSource.EXERCISE -> readAll(client, ExerciseSessionRecord::class, start, end, emptySet())
                AutoSource.MINDFULNESS -> readAll(client, MindfulnessSessionRecord::class, start, end, emptySet())
                AutoSource.STEPS -> readAll(client, StepsRecord::class, start, end, emptySet())
                AutoSource.NONE -> emptyList()
            }
            val counts = HashMap<String, Int>()
            records.forEach {
                val pkg = it.metadata.dataOrigin.packageName
                counts[pkg] = (counts[pkg] ?: 0) + 1
            }
            counts.entries.sortedByDescending { it.value }.map { it.key }
        } catch (e: Exception) {
            Log.w(TAG, "listSourceApps failed", e)
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    fun appLabel(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    /**
     * Syncs every auto-tracked habit for the last [days] days (including today).
     * Returns the number of entries written.
     */
    suspend fun syncAll(context: Context, days: Int = 7): Int {
        val client = client(context) ?: return 0
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read granted permissions", e)
            return 0
        }
        val component = (context.applicationContext as HabitsApplication).component
        val habits = component.habitList.filter { it.autoSource != AutoSource.NONE && !it.isArchived }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val backfilled = prefs.getStringSet(PREF_BACKFILLED, emptySet())!!.toMutableSet()
        val hasHistory = HISTORY_PERMISSION in granted
        var written = 0
        for (habit in habits) {
            val type = recordClass(habit.autoSource) ?: continue
            if (HealthPermission.getReadPermission(type) !in granted) continue
            // Each habit gets one deep backfill per source/app/history-access combination;
            // after that, the regular window is enough.
            val marker = "${habit.id}:${habit.autoSource}:${habit.autoSourceApp}:$hasHistory"
            val needsBackfill = marker !in backfilled
            try {
                val window = if (needsBackfill) maxOf(days, BACKFILL_DAYS) else days
                written += syncHabit(context, client, habit, window)
                if (needsBackfill) {
                    backfilled.removeAll { it.startsWith("${habit.id}:") }
                    backfilled.add(marker)
                    Log.i(TAG, "Backfilled ${habit.name} over $window days")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed for habit ${habit.name}", e)
            }
        }
        prefs.edit().putStringSet(PREF_BACKFILLED, backfilled).apply()
        return written
    }

    private suspend fun syncHabit(
        context: Context,
        client: HealthConnectClient,
        habit: Habit,
        days: Int
    ): Int {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDay = today.minusDays((days - 1).toLong())
        val origins = if (habit.autoSourceApp.isBlank()) {
            emptySet()
        } else {
            setOf(DataOrigin(habit.autoSourceApp))
        }

        val totals: Map<LocalDate, Double> = when (habit.autoSource) {
            AutoSource.EXERCISE -> sessionMinutesByDay(
                client,
                ExerciseSessionRecord::class,
                firstDay,
                today,
                zone,
                origins
            )
            AutoSource.MINDFULNESS -> sessionMinutesByDay(
                client,
                MindfulnessSessionRecord::class,
                firstDay,
                today,
                zone,
                origins
            )
            AutoSource.STEPS -> stepsByDay(client, firstDay, today, origins)
            AutoSource.NONE -> emptyMap()
        }

        val component = (context.applicationContext as HabitsApplication).component
        var written = 0
        var day = firstDay
        while (!day.isAfter(today)) {
            val total = totals[day] ?: 0.0
            val loopDate = LoopDate(day.year, day.monthValue, day.dayOfMonth)
            val existing = habit.originalEntries.get(loopDate)
            val newValue = (total.roundToLong() * 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            // Never lower a value: if the user typed in a larger total by hand (for example,
            // a session from an app that doesn't write to Health Connect), keep it.
            val shouldWrite = total > 0.0 && newValue > existing.value
            if (shouldWrite) {
                component.commandRunner.run(
                    CreateRepetitionCommand(
                        component.habitList,
                        habit,
                        loopDate,
                        newValue,
                        existing.notes
                    )
                )
                written++
            }
            day = day.plusDays(1)
        }
        return written
    }

    /**
     * Total minutes per day for session records. Overlapping sessions (for example, the same
     * workout written by two apps) are merged before summing, so time is never counted twice.
     */
    private suspend fun <T : Record> sessionMinutesByDay(
        client: HealthConnectClient,
        type: KClass<T>,
        firstDay: LocalDate,
        lastDay: LocalDate,
        zone: ZoneId,
        origins: Set<DataOrigin>
    ): Map<LocalDate, Double> {
        val start = firstDay.atStartOfDay(zone).toInstant()
        val end = lastDay.plusDays(1).atStartOfDay(zone).toInstant()
        val intervals = readAll(client, type, start, end, origins).mapNotNull { sessionInterval(it) }

        val result = HashMap<LocalDate, Double>()
        var day = firstDay
        while (!day.isAfter(lastDay)) {
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val clipped = intervals.mapNotNull { (s, e) ->
                val cs = maxOf(s, dayStart)
                val ce = minOf(e, dayEnd)
                if (ce > cs) cs to ce else null
            }
            val millis = mergedLength(clipped)
            if (millis > 0) result[day] = millis / 60_000.0
            day = day.plusDays(1)
        }
        return result
    }

    private fun sessionInterval(record: Record): Pair<Long, Long>? = when (record) {
        is ExerciseSessionRecord -> record.startTime.toEpochMilli() to record.endTime.toEpochMilli()
        is MindfulnessSessionRecord -> record.startTime.toEpochMilli() to record.endTime.toEpochMilli()
        else -> null
    }

    /** Length of the union of the given intervals. */
    fun mergedLength(intervals: List<Pair<Long, Long>>): Long {
        if (intervals.isEmpty()) return 0
        val sorted = intervals.sortedBy { it.first }
        var total = 0L
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for (i in 1 until sorted.size) {
            val (s, e) = sorted[i]
            if (s <= curEnd) {
                if (e > curEnd) curEnd = e
            } else {
                total += curEnd - curStart
                curStart = s
                curEnd = e
            }
        }
        total += curEnd - curStart
        return total
    }

    /**
     * Daily step totals. Without an app filter, Health Connect's aggregate de-duplicates
     * overlapping sources according to the user's app priority list.
     */
    private suspend fun stepsByDay(
        client: HealthConnectClient,
        firstDay: LocalDate,
        lastDay: LocalDate,
        origins: Set<DataOrigin>
    ): Map<LocalDate, Double> {
        val response = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    firstDay.atStartOfDay(),
                    lastDay.plusDays(1).atStartOfDay()
                ),
                timeRangeSlicer = Period.ofDays(1),
                dataOriginFilter = origins
            )
        )
        val result = HashMap<LocalDate, Double>()
        for (group in response) {
            val steps = group.result[StepsRecord.COUNT_TOTAL] ?: continue
            result[group.startTime.toLocalDate()] = steps.toDouble()
        }
        return result
    }

    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        type: KClass<T>,
        start: Instant,
        end: Instant,
        origins: Set<DataOrigin>
    ): List<T> {
        val result = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    dataOriginFilter = origins,
                    pageToken = pageToken
                )
            )
            result.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)
        return result
    }

    private fun recordClass(source: AutoSource): KClass<out Record>? = when (source) {
        AutoSource.EXERCISE -> ExerciseSessionRecord::class
        AutoSource.MINDFULNESS -> MindfulnessSessionRecord::class
        AutoSource.STEPS -> StepsRecord::class
        AutoSource.NONE -> null
    }
}
