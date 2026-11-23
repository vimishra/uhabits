/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.models

import org.isoron.platform.Synchronized
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import kotlin.math.min

class StreakList {
    private val list = ArrayList<Streak>()

    @Synchronized
    fun getBest(limit: Int): List<Streak> {
        list.sortWith { s1: Streak, s2: Streak -> s2.compareLonger(s1) }
        return list.subList(0, min(list.size, limit)).apply {
            sortWith { s1: Streak, s2: Streak -> s2.compareNewer(s1) }
        }.toList()
    }

    /**
     * Length of the streak that is still alive today: one that ends today, or yesterday when
     * today hasn't been completed yet. Zero if there is none.
     */
    @Synchronized
    fun current(today: LocalDate): Int =
        list.firstOrNull { it.end == today || it.end == today.minus(1) }?.length ?: 0

    @Synchronized
    fun recompute(
        computedEntries: EntryList,
        from: LocalDate,
        to: LocalDate,
        isNumerical: Boolean,
        targetValue: Double,
        targetType: NumericalHabitType,
        isPaused: (LocalDate) -> Boolean = { false }
    ) {
        list.clear()
        val today = getToday()
        val entries = computedEntries
            .getByInterval(from, to)
            .filter {
                !it.date.isNewerThan(today) && it.completed
            }

        if (entries.isEmpty()) return

        // Entries are newest first; collect runs of consecutive days.
        var run = mutableListOf(entries[0])
        for (i in 1 until entries.size) {
            val current = entries[i]
            if (current.date == run.last().date.minus(1)) {
                run.add(current)
            } else {
                addRun(run, isPaused, today)
                run = mutableListOf(current)
            }
        }
        addRun(run, isPaused, today)
    }

    /**
     * Adds a streak for [run] (newest first). Skipped days inside a pause keep the streak alive
     * but don't count towards its length; a streak can't begin with them, and a run made only of
     * paused days isn't a streak.
     */
    private fun addRun(run: List<Entry>, isPaused: (LocalDate) -> Boolean, today: LocalDate) {
        fun pausedSkip(e: Entry) = e.value == Entry.SKIP && isPaused(e.date)
        val oldestReal = run.indexOfLast { !pausedSkip(it) }
        if (oldestReal < 0) return
        val kept = run.subList(0, oldestReal + 1)
        val newest = kept.first().date
        list.add(
            Streak(
                start = kept.last().date,
                end = newest,
                paused = kept.count { pausedSkip(it) },
                pausedNow = newest == today && isPaused(today)
            )
        )
    }
}
