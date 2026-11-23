/*
 * This file is part of Loop Habit Tracker (personal fork).
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.zoom

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.preference.PreferenceManager
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Zoom for the habit details screen. Scales the screen density for that activity, so text, icons
 * and custom-drawn charts all grow together, without touching the system "Display size" setting.
 * The habit list is deliberately left alone so it keeps showing a full week.
 *
 * An activity opts in by calling [wrap] from attachBaseContext. [install] registers a lifecycle
 * hook that recreates an activity when it resumes with a zoom that no longer matches the
 * preference.
 */
object UiZoom {
    const val PREF_KEY = "pref_ui_zoom"
    private const val DEFAULT_PERCENT = 100

    /** Zoom each live activity was created with. */
    private val applied = WeakHashMap<Context, Int>()

    fun percent(context: Context): Int {
        val raw = try {
            PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, null)
        } catch (e: Exception) {
            null
        }
        return (raw?.toIntOrNull() ?: DEFAULT_PERCENT).coerceIn(50, 200)
    }

    /** Returns [base] with its density scaled by the current zoom; records it for [activity]. */
    fun wrap(activity: Activity, base: Context): Context {
        val percent = percent(base)
        applied[activity] = percent
        if (percent == DEFAULT_PERCENT) return base
        val config = Configuration(base.resources.configuration)
        config.densityDpi = (config.densityDpi * percent / 100f).roundToInt()
        return base.createConfigurationContext(config)
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val created = applied[activity] ?: return
                if (created != percent(activity)) activity.recreate()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                applied.remove(activity)
            }
        })
    }
}
