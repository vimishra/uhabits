/*
 * This file is part of Loop Habit Tracker (personal fork).
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.fonts

import android.graphics.Paint
import android.graphics.Typeface

/**
 * Typefaces for text the app draws itself (charts, rings, list buttons). Views that use XML get
 * the same family from the theme's android:fontFamily.
 *
 * Google Sans Flex is the Pixel system font; on devices without it, Typeface.create falls back to
 * the default sans-serif.
 */
object AppFonts {
    const val FAMILY = "google-sans-flex"

    val regular: Typeface by lazy { Typeface.create(FAMILY, Typeface.NORMAL) }
    val bold: Typeface by lazy { Typeface.create(FAMILY, Typeface.BOLD) }
    val medium: Typeface by lazy { Typeface.create(regular, 500, false) }

    /** Narrow variants for tight spaces, replacing sans-serif-condensed. */
    val condensed: Typeface by lazy { varied(regular, "'wght' 400, 'wdth' 85") }
    val condensedBold: Typeface by lazy { varied(bold, "'wght' 700, 'wdth' 85") }

    private fun varied(base: Typeface, settings: String): Typeface =
        Paint().apply {
            typeface = base
            fontVariationSettings = settings
        }.typeface ?: base
}
