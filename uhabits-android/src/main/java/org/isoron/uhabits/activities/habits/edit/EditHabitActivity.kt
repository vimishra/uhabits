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

package org.isoron.uhabits.activities.habits.edit

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.health.connect.client.PermissionController
import com.android.datetimepicker.time.RadialPickerLayout
import com.android.datetimepicker.time.TimePickerDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialogFactory
import org.isoron.uhabits.activities.common.dialogs.FrequencyPickerDialog
import org.isoron.uhabits.activities.common.dialogs.WeekdayPickerDialog
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.models.AutoSource
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitAutomationCodec
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.PauseLogic
import org.isoron.uhabits.core.models.PauseRange
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.databinding.ActivityEditHabitBinding
import org.isoron.uhabits.hc.HealthConnectSync
import org.isoron.uhabits.hc.HealthSyncWorker
import org.isoron.uhabits.utils.applyBottomInset
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.applyToolbarInsets
import org.isoron.uhabits.utils.dismissCurrentAndShow
import org.isoron.uhabits.utils.formatTime
import org.isoron.uhabits.utils.toFormattedString
import org.isoron.uhabits.zoom.UiZoom
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun formatFrequency(freqNum: Int, freqDen: Int, resources: Resources) = when {
    freqNum == 1 && (freqDen == 30 || freqDen == 31) -> resources.getString(R.string.every_month)
    freqDen == 30 || freqDen == 31 -> resources.getString(R.string.x_times_per_month, freqNum)
    freqNum == 1 && freqDen == 1 -> resources.getString(R.string.every_day)
    freqNum == 1 && freqDen == 7 -> resources.getString(R.string.every_week)
    freqNum == 1 && freqDen > 1 -> resources.getString(R.string.every_x_days, freqDen)
    freqDen == 7 -> resources.getString(R.string.x_times_per_week, freqNum)
    else -> resources.getString(R.string.x_times_per_y_days, freqNum, freqDen)
}

class EditHabitActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiZoom.wrap(this, newBase))
    }


    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private lateinit var binding: ActivityEditHabitBinding
    private lateinit var commandRunner: CommandRunner

    var habitId = -1L
    lateinit var habitType: HabitType
    var unit = ""
    var color = PaletteColor(11)
    var androidColor = 0
    var freqNum = 1
    var freqDen = 1
    var reminderHour = -1
    var reminderMin = -1
    var reminderDays: WeekdayList = WeekdayList.EVERY_DAY
    var targetType = NumericalHabitType.AT_LEAST
    var autoSource = AutoSource.NONE
    var autoSourceApp = ""
    var pauses: List<PauseRange> = emptyList()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (!granted.containsAll(HealthConnectSync.READ_PERMISSIONS)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.hc_permission_denied)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        val component = (application as HabitsApplication).component
        themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()

        binding = ActivityEditHabitBinding.inflate(layoutInflater)
        binding.root.applyRootViewInsets()
        binding.root.applyBottomInset()
        binding.toolbar.applyToolbarInsets()
        setContentView(binding.root)

        if (intent.hasExtra("habitId")) {
            binding.toolbar.title = getString(R.string.edit_habit)
            habitId = intent.getLongExtra("habitId", -1)
            val habit = component.habitList.getById(habitId)!!
            habitType = habit.type
            color = habit.color
            freqNum = habit.frequency.numerator
            freqDen = habit.frequency.denominator
            targetType = habit.targetType
            habit.reminder?.let {
                reminderHour = it.hour
                reminderMin = it.minute
                reminderDays = it.days
            }
            binding.nameInput.setText(habit.name)
            binding.questionInput.setText(habit.question)
            binding.notesInput.setText(habit.description)
            binding.categoryInput.setText(habit.category)
            binding.unitInput.setText(habit.unit)
            binding.targetInput.setText(habit.targetValue.toString())
            autoSource = habit.autoSource
            autoSourceApp = habit.autoSourceApp
            pauses = habit.pauses
        } else {
            habitType = HabitType.fromInt(intent.getIntExtra("habitType", HabitType.YES_NO.value))
        }

        if (state != null) {
            habitId = state.getLong("habitId")
            habitType = HabitType.fromInt(state.getInt("habitType"))
            color = PaletteColor(state.getInt("paletteColor"))
            freqNum = state.getInt("freqNum")
            freqDen = state.getInt("freqDen")
            reminderHour = state.getInt("reminderHour")
            reminderMin = state.getInt("reminderMin")
            reminderDays = WeekdayList(state.getInt("reminderDays"))
            autoSource = AutoSource.fromKey(state.getString("autoSource"))
            autoSourceApp = state.getString("autoSourceApp") ?: ""
            pauses = HabitAutomationCodec.decodePauses(state.getString("pauses"))
        }

        updateColors()

        when (habitType) {
            HabitType.YES_NO -> {
                binding.unitOuterBox.visibility = View.GONE
                binding.targetOuterBox.visibility = View.GONE
                binding.targetTypeOuterBox.visibility = View.GONE
                binding.autoTrackOuterBox.visibility = View.GONE
            }
            HabitType.NUMERICAL -> {
                binding.nameInput.hint = getString(R.string.measurable_short_example)
                binding.questionInput.hint = getString(R.string.measurable_question_example)
                binding.frequencyOuterBox.visibility = View.GONE
            }
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.elevation = 10.0f

        val colorPickerDialogFactory = ColorPickerDialogFactory(this)
        binding.colorButton.setOnClickListener {
            val picker = colorPickerDialogFactory.create(color, themeSwitcher.currentTheme)
            picker.setListener { paletteColor ->
                this.color = paletteColor
                updateColors()
            }
            picker.dismissCurrentAndShow(supportFragmentManager, "colorPicker")
        }

        populateFrequency()
        binding.booleanFrequencyPicker.setOnClickListener {
            val picker = FrequencyPickerDialog(freqNum, freqDen)
            picker.onFrequencyPicked = { num, den ->
                freqNum = num
                freqDen = den
                populateFrequency()
            }
            picker.dismissCurrentAndShow(supportFragmentManager, "frequencyPicker")
        }

        populateTargetType()
        binding.targetTypePicker.setOnClickListener {
            val builder = AlertDialog.Builder(this)
            val arrayAdapter = ArrayAdapter<String>(this, android.R.layout.select_dialog_item)
            arrayAdapter.add(getString(R.string.target_type_at_least))
            arrayAdapter.add(getString(R.string.target_type_at_most))
            builder.setAdapter(arrayAdapter) { dialog, which ->
                targetType = when (which) {
                    0 -> NumericalHabitType.AT_LEAST
                    else -> NumericalHabitType.AT_MOST
                }
                populateTargetType()
                dialog.dismiss()
            }
            val dialog = builder.create()
            dialog.dismissCurrentAndShow()
        }

        binding.numericalFrequencyPicker.setOnClickListener {
            val builder = AlertDialog.Builder(this)
            val arrayAdapter = ArrayAdapter<String>(this, android.R.layout.select_dialog_item)
            arrayAdapter.add(getString(R.string.every_day))
            arrayAdapter.add(getString(R.string.every_week))
            arrayAdapter.add(getString(R.string.every_month))
            builder.setAdapter(arrayAdapter) { dialog, which ->
                freqDen = when (which) {
                    1 -> 7
                    2 -> 30
                    else -> 1
                }
                populateFrequency()
                dialog.dismiss()
            }
            builder.show()
        }

        populateAutoSource()
        binding.autoSourcePicker.setOnClickListener { showAutoSourcePicker() }
        binding.autoSourceAppPicker.setOnClickListener { showAutoSourceAppPicker() }

        populatePause()
        binding.pausePicker.setOnClickListener { showPausePicker() }

        populateReminder()
        binding.reminderTimePicker.setOnClickListener {
            val currentHour = if (reminderHour >= 0) reminderHour else 8
            val currentMin = if (reminderMin >= 0) reminderMin else 0
            val is24HourMode = DateFormat.is24HourFormat(this)
            val dialog = TimePickerDialog.newInstance(
                object : TimePickerDialog.OnTimeSetListener {
                    override fun onTimeSet(view: RadialPickerLayout?, hourOfDay: Int, minute: Int) {
                        reminderHour = hourOfDay
                        reminderMin = minute
                        populateReminder()
                    }

                    override fun onTimeCleared(view: RadialPickerLayout?) {
                        reminderHour = -1
                        reminderMin = -1
                        reminderDays = WeekdayList.EVERY_DAY
                        populateReminder()
                    }
                },
                currentHour,
                currentMin,
                is24HourMode,
                androidColor
            )
            dialog.dismissCurrentAndShow(supportFragmentManager, "timePicker")
        }

        binding.reminderDatePicker.setOnClickListener {
            val dialog = WeekdayPickerDialog()

            dialog.setListener { days: WeekdayList ->
                reminderDays = days
                if (reminderDays.isEmpty) reminderDays = WeekdayList.EVERY_DAY
                populateReminder()
            }
            dialog.setSelectedDays(reminderDays)
            dialog.dismissCurrentAndShow(supportFragmentManager, "dayPicker")
        }

        binding.buttonSave.setOnClickListener {
            if (validate()) save()
        }

        for (fragment in supportFragmentManager.fragments) {
            (fragment as DialogFragment).dismiss()
        }
    }

    private fun save() {
        val component = (application as HabitsApplication).component
        val habit = component.modelFactory.buildHabit()

        var original: Habit? = null
        if (habitId >= 0) {
            original = component.habitList.getById(habitId)!!
            habit.copyFrom(original)
        }

        habit.name = binding.nameInput.text.trim().toString()
        habit.question = binding.questionInput.text.trim().toString()
        habit.description = binding.notesInput.text.trim().toString()
        habit.category = binding.categoryInput.text.trim().toString()
        habit.color = color
        if (reminderHour >= 0) {
            habit.reminder = Reminder(reminderHour, reminderMin, reminderDays)
        } else {
            habit.reminder = null
        }

        habit.frequency = Frequency(freqNum, freqDen)
        if (habitType == HabitType.NUMERICAL) {
            val newTarget = binding.targetInput.text.toString().toDouble()
            if (original != null) {
                // Keep earlier days judged against the goal that was in effect back then.
                habit.changeTarget(newTarget, getToday())
            } else {
                habit.targetValue = newTarget
            }
            habit.targetType = targetType
            habit.unit = binding.unitInput.text.trim().toString()
            habit.autoSource = autoSource
            habit.autoSourceApp = if (autoSource == AutoSource.NONE) "" else autoSourceApp
        } else {
            habit.autoSource = AutoSource.NONE
            habit.autoSourceApp = ""
        }
        habit.pauses = pauses
        habit.type = habitType

        val command = if (habitId >= 0) {
            EditHabitCommand(
                component.habitList,
                habitId,
                habit
            )
        } else {
            CreateHabitCommand(
                component.modelFactory,
                component.habitList,
                habit
            )
        }
        component.commandRunner.run(command)
        if (habit.autoSource != AutoSource.NONE) {
            HealthSyncWorker.syncNow(applicationContext, days = 30)
        }
        finish()
    }

    private fun validate(): Boolean {
        var isValid = true
        if (binding.nameInput.text.isEmpty()) {
            binding.nameInput.error = getFormattedValidationError(R.string.validation_cannot_be_blank)
            isValid = false
        }
        if (habitType == HabitType.NUMERICAL) {
            if (binding.targetInput.text.isEmpty()) {
                binding.targetInput.error = getString(R.string.validation_cannot_be_blank)
                isValid = false
            }
        }
        return isValid
    }

    private fun populateReminder() {
        if (reminderHour < 0) {
            binding.reminderTimePicker.text = getString(R.string.reminder_off)
            binding.reminderDatePicker.visibility = View.GONE
            binding.reminderDivider.visibility = View.GONE
        } else {
            val time = formatTime(this, reminderHour, reminderMin)
            binding.reminderTimePicker.text = time
            binding.reminderDatePicker.visibility = View.VISIBLE
            binding.reminderDivider.visibility = View.VISIBLE
            binding.reminderDatePicker.text = reminderDays.toFormattedString(this)
        }
    }

    @SuppressLint("StringFormatMatches")
    private fun populateFrequency() {
        binding.booleanFrequencyPicker.text = formatFrequency(freqNum, freqDen, resources)
        binding.numericalFrequencyPicker.text = when (freqDen) {
            1 -> getString(R.string.every_day)
            7 -> getString(R.string.every_week)
            30 -> getString(R.string.every_month)
            else -> "$freqNum/$freqDen"
        }
    }

    private fun populateTargetType() {
        binding.targetTypePicker.text = when (targetType) {
            NumericalHabitType.AT_MOST -> getString(R.string.target_type_at_most)
            else -> getString(R.string.target_type_at_least)
        }
    }

    private fun updateColors() {
        androidColor = themeSwitcher.currentTheme.color(color).toInt()
        binding.colorButton.backgroundTintList = ColorStateList.valueOf(androidColor)
        if (!themeSwitcher.isNightMode) {
            window.statusBarColor = androidColor
            binding.toolbar.setBackgroundColor(androidColor)
        }
    }

    private fun getFormattedValidationError(@StringRes resId: Int): Spanned {
        val html = "<font color=#FFFFFF>${getString(resId)}</font>"
        return Html.fromHtml(html)
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        with(state) {
            putLong("habitId", habitId)
            putInt("habitType", habitType.value)
            putInt("paletteColor", color.paletteIndex)
            putInt("androidColor", androidColor)
            putInt("freqNum", freqNum)
            putInt("freqDen", freqDen)
            putInt("reminderHour", reminderHour)
            putInt("reminderMin", reminderMin)
            putInt("reminderDays", reminderDays.toInteger())
            putString("autoSource", autoSource.key)
            putString("autoSourceApp", autoSourceApp)
            putString("pauses", HabitAutomationCodec.encodePauses(pauses))
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    // ---- Auto-tracking (Health Connect) ----

    private fun sourceLabel(source: AutoSource): String = getString(
        when (source) {
            AutoSource.NONE -> R.string.hc_source_none
            AutoSource.EXERCISE -> R.string.hc_source_exercise
            AutoSource.MINDFULNESS -> R.string.hc_source_mindfulness
            AutoSource.STEPS -> R.string.hc_source_steps
        }
    )

    private fun anyAppLabel(source: AutoSource): String = getString(
        if (source == AutoSource.STEPS) R.string.hc_source_app_any else R.string.hc_source_app_any_sessions
    )

    private fun populateAutoSource() {
        binding.autoSourcePicker.text = sourceLabel(autoSource)
        val showApp = autoSource != AutoSource.NONE
        binding.autoSourceDivider.visibility = if (showApp) View.VISIBLE else View.GONE
        binding.autoSourceAppPicker.visibility = if (showApp) View.VISIBLE else View.GONE
        binding.autoSourceAppPicker.text = if (autoSourceApp.isBlank()) {
            anyAppLabel(autoSource)
        } else {
            getString(R.string.hc_source_app_format, HealthConnectSync.appLabel(this, autoSourceApp))
        }
    }

    private fun showAutoSourcePicker() {
        val options = AutoSource.entries.toList()
        AlertDialog.Builder(this)
            .setItems(options.map { sourceLabel(it) }.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                onAutoSourcePicked(options[which])
            }
            .show()
    }

    private fun onAutoSourcePicked(source: AutoSource) {
        if (source != AutoSource.NONE && !HealthConnectSync.isAvailable(this)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.hc_unavailable)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val changed = source != autoSource
        autoSource = source
        if (changed) autoSourceApp = ""
        if (binding.unitInput.text.isBlank()) {
            when (source) {
                AutoSource.EXERCISE, AutoSource.MINDFULNESS ->
                    binding.unitInput.setText(R.string.hc_unit_minutes)
                AutoSource.STEPS -> binding.unitInput.setText(R.string.hc_unit_steps)
                AutoSource.NONE -> Unit
            }
        }
        populateAutoSource()
        if (source == AutoSource.NONE) return

        uiScope.launch {
            val missing = HealthConnectSync.missingPermissions(this@EditHabitActivity)
            if (missing.isNotEmpty()) {
                requestHealthPermissions.launch(HealthConnectSync.ALL_PERMISSIONS)
                return@launch
            }
            if (HealthConnectSync.shouldAskForHistory(this@EditHabitActivity)) {
                // Optional: lets the backfill reach further than 30 days before the first grant.
                requestHealthPermissions.launch(setOf(HealthConnectSync.HISTORY_PERMISSION))
            }
            if (changed) pickDefaultSourceApp(source)
        }
    }

    /** Exercise defaults to Garmin Connect; mindfulness to the app that logs it most. */
    private suspend fun pickDefaultSourceApp(source: AutoSource) {
        val apps = HealthConnectSync.listSourceApps(this, source)
        autoSourceApp = when (source) {
            AutoSource.EXERCISE ->
                if (HealthConnectSync.GARMIN_PACKAGE in apps) HealthConnectSync.GARMIN_PACKAGE else ""
            AutoSource.MINDFULNESS -> apps.firstOrNull() ?: ""
            else -> ""
        }
        populateAutoSource()
    }

    private fun showAutoSourceAppPicker() {
        val source = autoSource
        uiScope.launch {
            val missing = HealthConnectSync.missingPermissions(this@EditHabitActivity)
            if (missing.any { it in HealthConnectSync.READ_PERMISSIONS }) {
                requestHealthPermissions.launch(HealthConnectSync.ALL_PERMISSIONS)
                return@launch
            }
            val apps = HealthConnectSync.listSourceApps(this@EditHabitActivity, source)
            val packages = listOf("") + apps
            val labels = listOf(anyAppLabel(source)) +
                apps.map { HealthConnectSync.appLabel(this@EditHabitActivity, it) }
            val builder = AlertDialog.Builder(this@EditHabitActivity)
                .setTitle(R.string.hc_pick_source_app)
                .setItems(labels.toTypedArray()) { dialog, which ->
                    autoSourceApp = packages[which]
                    populateAutoSource()
                    dialog.dismiss()
                }
            if (apps.isEmpty()) builder.setMessage(R.string.hc_no_apps_found)
            builder.show()
        }
    }

    // ---- Pause ----

    private fun formatDate(date: LocalDate): String =
        java.time.LocalDate.of(date.year, date.month, date.day)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    private fun populatePause() {
        val today = getToday()
        val pause = PauseLogic.currentOrNext(pauses, today)
        val end = pause?.end
        binding.pausePicker.text = when {
            pause == null -> getString(R.string.pause_active)
            pause.start > today -> getString(
                R.string.pause_starts_on,
                formatDate(pause.start),
                if (end == null) "…" else formatDate(end)
            )
            end == null -> getString(R.string.pause_until_resumed)
            else -> getString(R.string.pause_until_date, formatDate(end))
        }
    }

    private fun showPausePicker() {
        val today = getToday()
        val isPaused = PauseLogic.currentOrNext(pauses, today) != null
        val labels = mutableListOf(
            getString(R.string.pause_option_today),
            getString(R.string.pause_option_3_days),
            getString(R.string.pause_option_week),
            getString(R.string.pause_option_until_resumed),
            getString(R.string.pause_option_pick_date)
        )
        if (isPaused) labels.add(getString(R.string.pause_option_resume))
        AlertDialog.Builder(this)
            .setTitle(R.string.pause)
            .setMessage(R.string.pause_hint)
            .setItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> setPause(today, today)
                    1 -> setPause(today, today.plus(2))
                    2 -> setPause(today, today.plus(6))
                    3 -> setPause(today, null)
                    4 -> pickPauseEndDate(today)
                    5 -> {
                        pauses = PauseLogic.resume(pauses, today)
                        populatePause()
                    }
                }
            }
            .show()
    }

    private fun setPause(start: LocalDate, end: LocalDate?) {
        pauses = PauseLogic.pause(pauses, start, end)
        populatePause()
    }

    private fun pickPauseEndDate(today: LocalDate) {
        val initial = today.plus(1)
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val end = LocalDate(year, month + 1, day)
                if (end >= today) setPause(today, end)
            },
            initial.year,
            initial.month - 1,
            initial.day
        ).apply {
            datePicker.minDate = java.time.LocalDate.of(today.year, today.month, today.day)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    }
}
