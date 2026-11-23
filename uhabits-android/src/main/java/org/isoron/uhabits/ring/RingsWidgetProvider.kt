/*
 * This file is part of Loop Habit Tracker (personal fork).
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.ring

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.RemoteViews
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardState
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsPresenter
import org.isoron.uhabits.core.ui.views.WidgetTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Home-screen widget showing today's ring for the first 4 (2 x 2) or 6 (3 x 2) habits, in the
 * same order as the app. Tapping a ring logs it (toggle, or the number popup for measurable
 * habits); tapping the name opens the habit.
 */
class RingsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runInBackground { ids.forEach { update(context, manager, it) } }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle
    ) {
        runInBackground { update(context, manager, id) }
    }

    private fun runInBackground(block: () -> Unit) {
        Thread {
            Looper.prepare()
            block()
        }.start()
    }

    companion object {
        private const val TAG = "RingsWidget"

        /**
         * Habit colors are lifted at least 25% toward white (40% for indigo-purple hues), and more
         * where needed to reach 4.5:1 contrast against the card (grey_850), so every habit reads
         * well on the dark widget.
         */
        private const val MIN_LIFT = 0.25f
        private const val VIOLET_LIFT = 0.40f
        private const val MIN_CONTRAST = 4.5

        private val CELLS = listOf(
            Cell(R.id.ringCell0, R.id.ringImage0, R.id.ringLabel0, R.id.ringName0, R.id.ringStreak0),
            Cell(R.id.ringCell1, R.id.ringImage1, R.id.ringLabel1, R.id.ringName1, R.id.ringStreak1),
            Cell(R.id.ringCell2, R.id.ringImage2, R.id.ringLabel2, R.id.ringName2, R.id.ringStreak2),
            Cell(R.id.ringCell3, R.id.ringImage3, R.id.ringLabel3, R.id.ringName3, R.id.ringStreak3),
            Cell(R.id.ringCell4, R.id.ringImage4, R.id.ringLabel4, R.id.ringName4, R.id.ringStreak4),
            Cell(R.id.ringCell5, R.id.ringImage5, R.id.ringLabel5, R.id.ringName5, R.id.ringStreak5)
        )

        private data class Cell(
            val cell: Int,
            val image: Int,
            val label: Int,
            val name: Int,
            val streak: Int
        )

        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            try {
                manager.updateAppWidget(id, build(context, manager.getAppWidgetOptions(id)))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to update rings widget $id", e)
            }
        }

        private fun build(context: Context, options: Bundle): RemoteViews {
            val component = (context.applicationContext as HabitsApplication).component
            val prefs = component.preferences
            val intents = component.pendingIntentFactory
            val habitList = component.habitList

            // Portrait size in dp, the same convention as the other widgets.
            val widthDp = max(110, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
            val heightDp = max(110, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
            val cols = if (widthDp >= 250) 3 else 2
            val rows = if (heightDp >= 170) 2 else 1

            // Same look as Loop's other widgets: WidgetTheme palette, grey card, white text.
            val theme = WidgetTheme()
            val items: List<HabitCardState> = ListHabitsPresenter.buildState(
                allHabits = habitList,
                filter = HabitMatcher(isArchivedAllowed = false),
                primaryOrder = prefs.defaultPrimaryOrder,
                secondaryOrder = prefs.defaultSecondaryOrder,
                checkmarkCount = 1,
                theme = theme
            ).items.take(cols * rows)

            val views = RemoteViews(context.packageName, R.layout.widget_rings)
            views.setInt(R.id.ringsBackground, "setImageAlpha", prefs.widgetOpacity)
            views.setViewVisibility(R.id.ringRow1, if (rows > 1) View.VISIBLE else View.GONE)

            // Ring bitmap size: fits the cell, leaving room for the two text lines.
            val density = context.resources.displayMetrics.density
            val cellW = (widthDp - 28) / cols.toFloat()
            val cellH = (heightDp - 28) / rows.toFloat() - 34f
            val ringPx = (max(36f, min(cellW - 8f, cellH)) * density).roundToInt()

            val themed = ContextThemeWrapper(context, R.style.WidgetTheme)
            val card = themed.getColor(R.color.grey_850)
            val lift = { c: Int -> liftForContrast(c, card, MIN_LIFT, MIN_CONTRAST, VIOLET_LIFT) }
            // Grey means paused, as in the app; white for everything else.
            val muted = themed.getColor(R.color.grey_500)
            val soft = themed.getColor(R.color.white_aa)
            val text = themed.getColor(R.color.white)
            val today = getToday()

            CELLS.forEachIndexed { i, cell ->
                val col = i % 3
                val row = i / 3
                if (col >= cols || row >= rows) {
                    views.setViewVisibility(cell.cell, View.GONE)
                    return@forEachIndexed
                }
                val s = items.getOrNull(i)
                val habit = s?.let { habitList.getById(it.id) }
                if (s == null || habit == null) {
                    views.setViewVisibility(cell.cell, View.INVISIBLE)
                    return@forEachIndexed
                }
                views.setViewVisibility(cell.cell, View.VISIBLE)
                views.setImageViewBitmap(cell.image, renderRing(themed, s, ringPx, soft, muted, lift))
                views.setContentDescription(cell.image, s.name)

                val color = if (s.paused || s.archived) muted else lift(theme.color(s.color).toInt())
                views.setTextViewText(cell.name, s.name)
                views.setTextColor(cell.name, color)
                views.setTextViewText(
                    cell.streak,
                    if (s.paused) {
                        context.getString(R.string.ring_paused)
                    } else {
                        context.getString(R.string.ring_streak, s.currentStreak)
                    }
                )
                views.setTextColor(cell.streak, if (s.paused) muted else text)

                val logIntent = if (habit.isNumerical) {
                    intents.showNumberPicker(habit, today)
                } else {
                    intents.toggleCheckmark(habit, null)
                }
                if (logIntent != null) views.setOnClickPendingIntent(cell.image, logIntent)
                views.setOnClickPendingIntent(cell.label, intents.showHabit(habit))
            }
            return views
        }

        private fun renderRing(
            context: Context,
            state: HabitCardState,
            size: Int,
            goalColor: Int,
            muted: Int,
            lift: (Int) -> Int
        ): Bitmap {
            val view = RingTileView(context).apply {
                ringOnly = true
                themeOverride = WidgetTheme()
                goalTextColor = goalColor
                mutedColor = muted
                trackColor = 0x26FFFFFF // white at 15%: visible on the grey card
                colorAdjust = lift
                this.state = state
            }
            val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            view.measure(spec, spec)
            view.layout(0, 0, size, size)
            view.background = null
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            return bitmap
        }
    }
}
