/*
 * This file is part of Loop Habit Tracker (personal fork).
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.ring

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.activities.habits.list.views.toShortString
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardState
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior
import org.isoron.uhabits.core.ui.views.Theme
import org.isoron.uhabits.fonts.AppFonts
import org.isoron.uhabits.utils.InterfaceUtils.getFontAwesome
import org.isoron.uhabits.utils.applyBottomInset
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.sp
import org.isoron.uhabits.utils.sres
import kotlin.math.ceil
import kotlin.math.min

private const val PER_PAGE = 6
private const val COLUMNS = 2
private const val PREF_RING_VIEW = "pref_ring_view"
private const val PREF_RING_PAGE = "pref_ring_page"
private const val FA_PAUSE = ""

/**
 * Mixes [color] toward white by at least [minLift], and further (in 5% steps) until it has
 * [minContrast] against the opaque [background], so dark hues stay readable on dark surfaces.
 * Indigo-to-purple hues (220-300 degrees) look darker than their measured contrast, so they get
 * at least [violetLift].
 */
fun liftForContrast(
    color: Int,
    background: Int,
    minLift: Float,
    minContrast: Double,
    violetLift: Float = minLift
): Int {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
    var amount = if (hsl[0] in 220f..300f) maxOf(minLift, violetLift) else minLift
    while (true) {
        val lifted = androidx.core.graphics.ColorUtils.blendARGB(color, android.graphics.Color.WHITE, amount)
        if (amount >= 0.9f ||
            androidx.core.graphics.ColorUtils.calculateContrast(lifted, background) >= minContrast
        ) {
            return lifted
        }
        amount += 0.05f
    }
}

/** Whether the habit list shows rings instead of the week grid. Stored per device. */
object RingMode {
    fun isOn(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_RING_VIEW, false)

    fun set(context: Context, on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_RING_VIEW, on).apply()
    }
}

/**
 * One habit as a progress ring: today's value toward today's goal, the habit name, and the
 * current streak (or "Paused").
 */
@SuppressLint("ViewConstructor")
class RingTileView(context: Context) : View(context) {
    var state: HabitCardState? = null
        set(value) {
            field = value
            contentDescription = value?.name
            invalidate()
        }

    /** Draw only the ring (no name / streak text), sized to the whole view. Used by the widget. */
    var ringOnly = false

    /** Palette to use instead of the app's current light/dark theme. Used by the widget. */
    var themeOverride: Theme? = null

    /** Color for the "/ goal" line, instead of the theme's contrast80. Used by the widget. */
    var goalTextColor: Int? = null

    /** Paused / not-done color and empty-track color, instead of the theme's. Used by the widget. */
    var mutedColor: Int? = null
    var trackColor: Int? = null

    /** Adjusts habit colors (e.g. lifts them for contrast on a dark widget). Identity by default. */
    var colorAdjust: (Int) -> Int = { it }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private val pad = dp(10f)
    private val nameSize = sp(17f)
    private val subSize = sp(13f)
    private val textBlock = nameSize * 1.35f + subSize * 1.5f

    init {
        // No card behind the ring: a borderless ripple shows the touch instead.
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        background = context.getDrawable(tv.resourceId)
    }

    /** Ring diameter and centre: ~80% of the space available, block centred in the cell. */
    private fun ringGeometry(): Triple<Float, Float, Float> {
        val w = width.toFloat()
        val h = height.toFloat()
        if (ringOnly) return Triple(w / 2, h / 2, min(w, h) - dp(2f))
        val diameter = 0.8f * min(w - 2 * pad, h - 2 * pad - textBlock)
        val block = diameter + pad * 0.6f + textBlock
        val top = (h - block) / 2
        return Triple(w / 2, top + diameter / 2, diameter)
    }

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        val w = width.toFloat()

        val muted = mutedColor ?: sres.getColor(R.attr.contrast60)
        val color = when {
            s.paused || s.archived -> muted
            else -> colorAdjust((themeOverride ?: currentTheme()).color(s.color).toInt())
        }

        val (cx, cy, diameter) = ringGeometry()
        if (diameter <= 0) return
        val stroke = diameter * 0.055f
        val r = diameter / 2 - stroke / 2
        rect.set(cx - r, cy - r, cx + r, cy + r)

        ringPaint.strokeWidth = stroke
        ringPaint.color = trackColor ?: sres.getColor(R.attr.contrast20)
        canvas.drawArc(rect, 0f, 360f, false, ringPaint)
        val progress = progressOf(s)
        if (progress > 0f) {
            ringPaint.color = color
            canvas.drawArc(rect, -90f, 360f * progress, false, ringPaint)
        }

        drawCenter(canvas, s, color, muted, cx, cy, diameter)
        if (ringOnly) return

        // Name.
        textPaint.typeface = AppFonts.medium
        textPaint.textSize = nameSize
        textPaint.color = color
        val name = TextUtils.ellipsize(s.name, textPaint, w - 2 * pad, TextUtils.TruncateAt.END)
        val nameY = cy + diameter / 2 + pad * 0.6f + nameSize
        canvas.drawText(name.toString(), cx, nameY, textPaint)

        // Streak / paused line. Grey is reserved for paused habits; streaks use the main text color.
        textPaint.typeface = AppFonts.regular
        textPaint.textSize = subSize
        textPaint.color = if (s.paused) muted else sres.getColor(R.attr.contrast100)
        val sub = if (s.paused) {
            context.getString(R.string.ring_paused)
        } else {
            context.getString(R.string.ring_streak, s.currentStreak)
        }
        canvas.drawText(sub, cx, nameY + subSize * 1.45f, textPaint)
    }

    private fun drawCenter(
        canvas: Canvas,
        s: HabitCardState,
        color: Int,
        muted: Int,
        cx: Float,
        cy: Float,
        diameter: Float
    ) {
        val today = s.checkmarks.firstOrNull() ?: Entry.UNKNOWN
        if (isPausedAndUnlogged(s)) {
            drawIcon(canvas, FA_PAUSE, muted, cx, cy, diameter * 0.26f)
            return
        }
        if (s.isNumerical) {
            val value = (if (today > 0) today else 0) / 1000.0
            textPaint.typeface = AppFonts.bold
            textPaint.textSize = diameter * 0.30f
            textPaint.color = color
            canvas.drawText(value.toShortString(), cx, cy + textPaint.textSize * 0.2f, textPaint)
            textPaint.typeface = AppFonts.regular
            textPaint.textSize = min(diameter * 0.1f, sp(13f))
            textPaint.color = goalTextColor ?: sres.getColor(R.attr.contrast80)
            val goal = "/ ${s.targetValue.toShortString()} ${s.unit}".trim()
            canvas.drawText(goal, cx, cy + diameter * 0.25f, textPaint)
        } else {
            val (icon, iconColor) = when (today) {
                Entry.YES_MANUAL, Entry.YES_AUTO -> context.getString(R.string.fa_check) to color
                Entry.SKIP -> context.getString(R.string.fa_skipped) to color
                else -> context.getString(R.string.fa_times) to muted
            }
            drawIcon(canvas, icon, iconColor, cx, cy, diameter * 0.3f)
        }
    }

    private fun drawIcon(canvas: Canvas, icon: String, color: Int, cx: Float, cy: Float, size: Float) {
        textPaint.typeface = getFontAwesome(context)
        textPaint.textSize = size
        textPaint.color = color
        canvas.drawText(icon, cx, cy + size * 0.35f, textPaint)
    }

    /** Paused today with nothing logged: the day is a virtual skip, shown as a pause. */
    private fun isPausedAndUnlogged(s: HabitCardState): Boolean {
        val today = s.checkmarks.firstOrNull() ?: Entry.UNKNOWN
        return s.paused && (today == Entry.SKIP || today <= 0)
    }

    private fun progressOf(s: HabitCardState): Float {
        if (isPausedAndUnlogged(s)) return 0f
        val today = s.checkmarks.firstOrNull() ?: Entry.UNKNOWN
        if (s.isNumerical) {
            if (today <= 0 || s.targetValue <= 0) return if (s.completed.firstOrNull() == true) 1f else 0f
            return min(1f, (today / 1000.0 / s.targetValue).toFloat())
        }
        return when (today) {
            Entry.YES_MANUAL, Entry.YES_AUTO, Entry.SKIP -> 1f
            else -> 0f
        }
    }

    private var lastTouchY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) lastTouchY = event.y
        return super.onTouchEvent(event)
    }

    /** True when the last tap landed on the name / streak text below the ring. */
    fun lastTouchWasOnText(): Boolean {
        val (_, cy, diameter) = ringGeometry()
        return lastTouchY > cy + diameter / 2 + pad * 0.3f
    }

    /** Ring centre in screen coordinates, for confetti and the number popup. */
    fun ringCenterOnScreen(): Pair<Float, Float> {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val (cx, cy, _) = ringGeometry()
        return (loc[0] + cx) to (loc[1] + cy)
    }
}

/**
 * Habits as rings, six per page (2 x 3), in the same order as the list. Swipe between pages;
 * the last page shown is remembered.
 */
@SuppressLint("ViewConstructor")
class RingPagerView(
    context: Context,
    private val listAdapter: HabitCardListAdapter,
    private val behavior: ListHabitsBehavior,
    private val preferences: Preferences
) : LinearLayout(context), ModelObservable.Listener {

    private var habits: List<HabitCardState> = emptyList()
    private val pager = RecyclerView(context)
    private val dots = LinearLayout(context)
    private val layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
    private var currentPage = 0
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        orientation = VERTICAL
        pager.layoutManager = layoutManager
        pager.adapter = PageAdapter()
        pager.overScrollMode = OVER_SCROLL_NEVER
        PagerSnapHelper().attachToRecyclerView(pager)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val pos = layoutManager.findFirstCompletelyVisibleItemPosition()
                if (pos >= 0 && pos != currentPage) {
                    currentPage = pos
                    prefs.edit().putInt(PREF_RING_PAGE, pos).apply()
                    updateDots()
                }
            }
        })
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        dots.gravity = Gravity.CENTER
        val dotPad = dp(10f).toInt()
        dots.setPadding(0, dotPad / 2, 0, dotPad)
        addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        currentPage = prefs.getInt(PREF_RING_PAGE, 0)
        applyBottomInset()
    }

    private val pageCount: Int get() = maxOf(1, ceil(habits.size / PER_PAGE.toDouble()).toInt())

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        listAdapter.observable.addListener(this)
        onModelChange()
    }

    override fun onDetachedFromWindow() {
        listAdapter.observable.removeListener(this)
        super.onDetachedFromWindow()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onModelChange() {
        habits = listAdapter.habitsInListOrder()
        currentPage = currentPage.coerceIn(0, pageCount - 1)
        pager.adapter?.notifyDataSetChanged()
        pager.post { layoutManager.scrollToPositionWithOffset(currentPage, 0) }
        updateDots()
    }

    private fun updateDots() {
        dots.removeAllViews()
        dots.visibility = if (pageCount > 1) VISIBLE else GONE
        val size = dp(8f).toInt()
        val gap = dp(5f).toInt()
        for (i in 0 until pageCount) {
            val dot = View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(sres.getColor(if (i == currentPage) R.attr.contrast100 else R.attr.contrast40))
                }
                contentDescription = context.getString(R.string.ring_page, i + 1, pageCount)
                setOnClickListener { pager.smoothScrollToPosition(i) }
            }
            dots.addView(dot, LayoutParams(size, size).apply { setMargins(gap, 0, gap, 0) })
        }
    }

    private fun onTap(tile: RingTileView) {
        val s = tile.state ?: return
        // Tapping the name opens the habit, like tapping its name in the week list.
        if (tile.lastTouchWasOnText()) {
            behavior.onClickHabit(s.id)
            return
        }
        val (x, y) = tile.ringCenterOnScreen()
        val today = getToday()
        if (s.isNumerical) {
            behavior.onEdit(s.id, today, x, y)
        } else {
            val value = Entry.nextToggleValue(
                value = s.checkmarks.firstOrNull() ?: Entry.UNKNOWN,
                isSkipEnabled = preferences.isSkipEnabled,
                areQuestionMarksEnabled = preferences.areQuestionMarksEnabled
            )
            tile.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            behavior.onToggle(s.id, today, value, s.notes.firstOrNull() ?: "", x, y)
        }
    }

    private inner class PageHolder(val page: LinearLayout, val tiles: List<RingTileView>) :
        RecyclerView.ViewHolder(page)

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val gap = dp(10f).toInt()
            val tiles = mutableListOf<RingTileView>()
            val page = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(gap, gap, gap, 0)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.MATCH_PARENT
                )
            }
            repeat(PER_PAGE / COLUMNS) {
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                repeat(COLUMNS) {
                    val tile = RingTileView(context).apply {
                        setOnClickListener { onTap(this) }
                        setOnLongClickListener {
                            state?.let { s -> behavior.onClickHabit(s.id) }
                            true
                        }
                    }
                    tiles.add(tile)
                    row.addView(
                        tile,
                        LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                            setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                        }
                    )
                }
                page.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            }
            return PageHolder(page, tiles)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.tiles.forEachIndexed { i, tile ->
                val s = habits.getOrNull(position * PER_PAGE + i)
                tile.state = s
                tile.visibility = if (s == null) INVISIBLE else VISIBLE
            }
        }
    }
}
