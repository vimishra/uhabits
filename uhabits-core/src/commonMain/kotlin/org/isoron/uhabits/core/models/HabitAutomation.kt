/*
 * This file is part of Loop Habit Tracker (personal fork).
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.core.models

import org.isoron.platform.time.LocalDate

/**
 * Where a numerical habit gets its values from automatically.
 */
enum class AutoSource(val key: String) {
    NONE(""),

    /** Total minutes of exercise sessions (all activity types, overlaps merged). */
    EXERCISE("exercise"),

    /** Total minutes of mindfulness sessions. */
    MINDFULNESS("mindfulness"),

    /** Total step count for the day (Health Connect de-duplicated aggregate). */
    STEPS("steps");

    companion object {
        fun fromKey(key: String?): AutoSource =
            entries.firstOrNull { it.key == key } ?: NONE
    }
}

/** From [from] onwards (inclusive), the habit's target was [value]. */
data class TargetChange(val from: LocalDate, val value: Double)

/** The habit is paused from [start] to [end] (inclusive). A null [end] means "until resumed". */
data class PauseRange(val start: LocalDate, val end: LocalDate?) {
    fun contains(date: LocalDate): Boolean =
        date >= start && (end == null || date <= end)
}

object HabitAutomationCodec {
    fun encodeTargets(list: List<TargetChange>): String =
        list.sortedBy { it.from }.joinToString(";") { "${it.from.daysSince2000}:${it.value}" }

    fun decodeTargets(text: String?): List<TargetChange> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(";").mapNotNull { part ->
            val pieces = part.split(":")
            if (pieces.size != 2) return@mapNotNull null
            val day = pieces[0].trim().toIntOrNull() ?: return@mapNotNull null
            val value = pieces[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            TargetChange(LocalDate(day), value)
        }.sortedBy { it.from }
    }

    fun encodePauses(list: List<PauseRange>): String =
        list.sortedBy { it.start }.joinToString(";") {
            "${it.start.daysSince2000}:${it.end?.daysSince2000 ?: ""}"
        }

    fun decodePauses(text: String?): List<PauseRange> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(";").mapNotNull { part ->
            val pieces = part.split(":")
            if (pieces.size != 2) return@mapNotNull null
            val start = pieces[0].trim().toIntOrNull() ?: return@mapNotNull null
            val endText = pieces[1].trim()
            val end = if (endText.isEmpty()) null else (endText.toIntOrNull() ?: return@mapNotNull null)
            PauseRange(LocalDate(start), end?.let { LocalDate(it) })
        }.sortedBy { it.start }
    }
}

object PauseLogic {
    /** Adds a pause from [start] to [end] (inclusive, null = until resumed), replacing overlaps. */
    fun pause(pauses: List<PauseRange>, start: LocalDate, end: LocalDate?): List<PauseRange> {
        val others = pauses.filter { p ->
            val pEnd = p.end
            val overlaps = (end == null || p.start <= end) && (pEnd == null || pEnd >= start)
            !overlaps
        }
        return (others + PauseRange(start, end)).sortedBy { it.start }
    }

    /** Ends pauses covering [date] or starting later; the habit is active from [date] on. */
    fun resume(pauses: List<PauseRange>, date: LocalDate): List<PauseRange> =
        pauses.mapNotNull { p ->
            when {
                p.start >= date -> null
                p.contains(date) -> PauseRange(p.start, date.minus(1))
                else -> p
            }
        }

    /** The pause in effect on [date], or the next one scheduled after it. */
    fun currentOrNext(pauses: List<PauseRange>, date: LocalDate): PauseRange? =
        pauses.firstOrNull { it.contains(date) } ?: pauses.filter { it.start > date }.minByOrNull { it.start }
}
