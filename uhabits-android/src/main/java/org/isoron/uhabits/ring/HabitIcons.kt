/*
 * This file is part of Loop Habit Tracker (personal fork).
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.ring

import org.isoron.uhabits.R

/**
 * The icons a habit can use (Material Symbols Rounded, filled; Apache License 2.0). The stored
 * value in Habit.icon is the symbol name; an empty or unknown name falls back to the first letter
 * of the habit's name.
 */
object HabitIcons {
    /** Picker order: mind and health, fitness, learning and creativity, home and life, limits. */
    val all: List<Pair<String, Int>> = listOf(
        "self_improvement" to R.drawable.hc_icon_self_improvement,
        "spa" to R.drawable.hc_icon_spa,
        "air" to R.drawable.hc_icon_air,
        "psychology" to R.drawable.hc_icon_psychology,
        "mood" to R.drawable.hc_icon_mood,
        "favorite" to R.drawable.hc_icon_favorite,
        "monitor_heart" to R.drawable.hc_icon_monitor_heart,
        "medication" to R.drawable.hc_icon_medication,
        "water_drop" to R.drawable.hc_icon_water_drop,
        "local_drink" to R.drawable.hc_icon_local_drink,
        "nutrition" to R.drawable.hc_icon_nutrition,
        "restaurant" to R.drawable.hc_icon_restaurant,
        "bedtime" to R.drawable.hc_icon_bedtime,
        "alarm" to R.drawable.hc_icon_alarm,
        "sunny" to R.drawable.hc_icon_sunny,

        "fitness_center" to R.drawable.hc_icon_fitness_center,
        "directions_walk" to R.drawable.hc_icon_directions_walk,
        "directions_run" to R.drawable.hc_icon_directions_run,
        "directions_bike" to R.drawable.hc_icon_directions_bike,
        "hiking" to R.drawable.hc_icon_hiking,
        "pool" to R.drawable.hc_icon_pool,
        "sports_gymnastics" to R.drawable.hc_icon_sports_gymnastics,
        "sports_martial_arts" to R.drawable.hc_icon_sports_martial_arts,
        "sports_tennis" to R.drawable.hc_icon_sports_tennis,

        "menu_book" to R.drawable.hc_icon_menu_book,
        "auto_stories" to R.drawable.hc_icon_auto_stories,
        "edit_note" to R.drawable.hc_icon_edit_note,
        "edit" to R.drawable.hc_icon_edit,
        "school" to R.drawable.hc_icon_school,
        "language" to R.drawable.hc_icon_language,
        "translate" to R.drawable.hc_icon_translate,
        "code" to R.drawable.hc_icon_code,
        "lightbulb" to R.drawable.hc_icon_lightbulb,
        "piano" to R.drawable.hc_icon_piano,
        "music_note" to R.drawable.hc_icon_music_note,
        "headphones" to R.drawable.hc_icon_headphones,
        "brush" to R.drawable.hc_icon_brush,
        "palette" to R.drawable.hc_icon_palette,
        "photo_camera" to R.drawable.hc_icon_photo_camera,

        "task_alt" to R.drawable.hc_icon_task_alt,
        "checklist" to R.drawable.hc_icon_checklist,
        "schedule" to R.drawable.hc_icon_schedule,
        "timer" to R.drawable.hc_icon_timer,
        "work" to R.drawable.hc_icon_work,
        "laptop" to R.drawable.hc_icon_laptop,
        "savings" to R.drawable.hc_icon_savings,
        "home" to R.drawable.hc_icon_home,
        "cleaning_services" to R.drawable.hc_icon_cleaning_services,
        "local_laundry_service" to R.drawable.hc_icon_local_laundry_service,
        "shopping_cart" to R.drawable.hc_icon_shopping_cart,
        "call" to R.drawable.hc_icon_call,
        "mail" to R.drawable.hc_icon_mail,
        "family_restroom" to R.drawable.hc_icon_family_restroom,
        "groups" to R.drawable.hc_icon_groups,
        "volunteer_activism" to R.drawable.hc_icon_volunteer_activism,
        "pets" to R.drawable.hc_icon_pets,
        "park" to R.drawable.hc_icon_park,
        "eco" to R.drawable.hc_icon_eco,
        "coffee" to R.drawable.hc_icon_coffee,

        "smoke_free" to R.drawable.hc_icon_smoke_free,
        "no_drinks" to R.drawable.hc_icon_no_drinks,
        "do_not_disturb_on" to R.drawable.hc_icon_do_not_disturb_on,
        "star" to R.drawable.hc_icon_star,
        "flag" to R.drawable.hc_icon_flag,
        "rocket_launch" to R.drawable.hc_icon_rocket_launch
    )

    private val byName = all.toMap()

    /** Drawable for [name], or null when the habit has no (known) icon. */
    fun drawableFor(name: String): Int? = byName[name]
}
