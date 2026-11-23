/*
 * This file is part of Loop Habit Tracker (personal fork).
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.hc

import android.content.Context
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.isoron.uhabits.R
import org.isoron.uhabits.zoom.UiZoom

/**
 * Shown by Health Connect when the user asks why this app wants health data.
 */
class HealthPermissionsRationaleActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiZoom.wrap(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setText(R.string.hc_rationale)
            setPadding(padding, padding, padding, padding)
            textSize = 16f
        }
        setContentView(ScrollView(this).apply { addView(text) })
        title = getString(R.string.hc_rationale_title)
    }
}
