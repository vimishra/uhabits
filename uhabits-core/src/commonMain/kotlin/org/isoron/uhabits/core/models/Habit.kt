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

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class Habit(
    var category: String = "",
    var color: PaletteColor = PaletteColor(8),
    var description: String = "",
    var frequency: Frequency = Frequency.DAILY,
    var id: Long? = null,
    var isArchived: Boolean = false,
    var name: String = "",
    var position: Int = 0,
    var question: String = "",
    var reminder: Reminder? = null,
    var targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
    var targetValue: Double = 0.0,
    var type: HabitType = HabitType.YES_NO,
    var unit: String = "",
    var uuid: String? = null,
    var autoSource: AutoSource = AutoSource.NONE,
    var autoSourceApp: String = "",
    var targetHistory: List<TargetChange> = emptyList(),
    var pauses: List<PauseRange> = emptyList(),
    /** Fork: icon name from the app's icon set; empty = show the name's first letter. */
    var icon: String = "",
    val computedEntries: EntryList,
    val originalEntries: EntryList,
    val scores: ScoreList,
    val streaks: StreakList
) {
    init {
        if (uuid == null) this.uuid = Uuid.random().toHexString()
    }

    var observable = ModelObservable()

    val isNumerical: Boolean
        get() = type == HabitType.NUMERICAL

    val uriString: String
        get() = "content://org.isoron.uhabits/habit/$id"

    fun hasReminder(): Boolean = reminder != null

    fun isCompletedOn(date: LocalDate): Boolean {
        return computedEntries.get(date).completed
    }

    fun isCompletedToday(): Boolean {
        return isCompletedOn(getToday())
    }

    fun isEnteredToday(): Boolean {
        val today = getToday()
        val value = computedEntries.get(today).value
        return value != Entry.UNKNOWN
    }

    /**
     * Returns the target that was in effect on the given date. Days before the first
     * recorded change use the earliest recorded value; with no history, the current target.
     */
    fun targetAt(date: LocalDate): Double {
        if (targetHistory.isEmpty()) return targetValue
        var result = targetHistory.first().value
        for (change in targetHistory) {
            if (change.from <= date) result = change.value else break
        }
        return result
    }

    /**
     * Records that, starting on [from], the target becomes [newValue]. The previous target is
     * preserved for all earlier days.
     */
    fun changeTarget(newValue: Double, from: LocalDate = getToday()) {
        val history = targetHistory.toMutableList()
        if (history.isEmpty()) {
            if (newValue == targetValue) return
            history.add(TargetChange(LocalDate(-40000), targetValue))
        }
        history.removeAll { it.from >= from }
        if (history.lastOrNull()?.value != newValue) {
            history.add(TargetChange(from, newValue))
        }
        targetHistory = history
        targetValue = newValue
    }

    fun isPausedOn(date: LocalDate): Boolean = pauses.any { it.contains(date) }

    fun isPausedToday(): Boolean = isPausedOn(getToday())

    /** Pauses the habit starting on [start] until [end] (inclusive), or until resumed. */
    fun pause(start: LocalDate = getToday(), end: LocalDate? = null) {
        pauses = PauseLogic.pause(pauses, start, end)
    }

    /** Ends any pause covering [date] (and any scheduled later); active again from [date] on. */
    fun resume(date: LocalDate = getToday()) {
        pauses = PauseLogic.resume(pauses, date)
    }

    /**
     * Original entries plus virtual SKIP entries on paused days that have no data. Days where
     * the user did record something are left untouched.
     */
    private fun entriesWithPauses(): EntryList {
        if (pauses.isEmpty()) return originalEntries
        val result = EntryList()
        originalEntries.getKnown().forEach { result.add(it) }
        val today = getToday()
        for (p in pauses) {
            val end = p.end?.let { if (it > today) today else it } ?: today
            var d = p.start
            while (d <= end) {
                val existing = result.get(d)
                if (existing.value == Entry.UNKNOWN) {
                    result.add(Entry(d, Entry.SKIP, existing.notes))
                }
                d = d.plus(1)
            }
        }
        return result
    }

    fun recompute() {
        computedEntries.recomputeFrom(
            originalEntries = entriesWithPauses(),
            frequency = frequency,
            isNumerical = isNumerical
        )

        computedEntries.recomputeCompletion(
            frequency = frequency,
            isNumerical = isNumerical,
            targetType = targetType,
            targetValue = targetValue,
            targetAt = ::targetAt
        )

        val today = getToday()
        val to = today.plus(30)
        val entries = computedEntries.getKnown()
        var from = entries.lastOrNull()?.date ?: today
        if (from.isNewerThan(to)) from = to

        scores.recompute(
            frequency = frequency,
            isNumerical = isNumerical,
            numericalHabitType = targetType,
            targetValue = targetValue,
            targetAt = ::targetAt,
            computedEntries = computedEntries,
            from = from,
            to = to
        )

        streaks.recompute(
            computedEntries,
            from,
            to,
            isNumerical,
            targetValue,
            targetType,
            isPaused = { date -> pauses.any { it.contains(date) } }
        )
    }

    fun copyFrom(other: Habit) {
        this.category = other.category
        this.color = other.color
        this.description = other.description
        this.frequency = other.frequency
        // this.id should not be copied
        this.isArchived = other.isArchived
        this.name = other.name
        this.position = other.position
        this.question = other.question
        this.reminder = other.reminder
        this.targetType = other.targetType
        this.targetValue = other.targetValue
        this.type = other.type
        this.unit = other.unit
        this.uuid = other.uuid
        this.autoSource = other.autoSource
        this.autoSourceApp = other.autoSourceApp
        this.targetHistory = other.targetHistory
        this.pauses = other.pauses
        this.icon = other.icon
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Habit) return false

        if (category != other.category) return false
        if (color != other.color) return false
        if (description != other.description) return false
        if (frequency != other.frequency) return false
        if (id != other.id) return false
        if (isArchived != other.isArchived) return false
        if (name != other.name) return false
        if (position != other.position) return false
        if (question != other.question) return false
        if (reminder != other.reminder) return false
        if (targetType != other.targetType) return false
        if (targetValue != other.targetValue) return false
        if (type != other.type) return false
        if (unit != other.unit) return false
        if (uuid != other.uuid) return false
        if (autoSource != other.autoSource) return false
        if (autoSourceApp != other.autoSourceApp) return false
        if (targetHistory != other.targetHistory) return false
        if (pauses != other.pauses) return false
        if (icon != other.icon) return false

        return true
    }

    override fun hashCode(): Int {
        var result = category.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + frequency.hashCode()
        result = 31 * result + (id?.hashCode() ?: 0)
        result = 31 * result + isArchived.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + position
        result = 31 * result + question.hashCode()
        result = 31 * result + (reminder?.hashCode() ?: 0)
        result = 31 * result + targetType.value
        result = 31 * result + targetValue.hashCode()
        result = 31 * result + type.value
        result = 31 * result + unit.hashCode()
        result = 31 * result + (uuid?.hashCode() ?: 0)
        result = 31 * result + autoSource.hashCode()
        result = 31 * result + autoSourceApp.hashCode()
        result = 31 * result + targetHistory.hashCode()
        result = 31 * result + pauses.hashCode()
        result = 31 * result + icon.hashCode()
        return result
    }
}
