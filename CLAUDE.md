# Loop Habit Tracker — personal Health Connect fork

Personal fork of Loop Habit Tracker (iSoron/uhabits), branch `health-connect`.
Installed only on my own Pixel; not published anywhere.

## Build & install

```bash
# local.properties must contain: sdk.dir=/Users/vikmishra/Library/Android/sdk
./gradlew :uhabits-android:assembleDebug 2>&1 | tee build.log

# Install into the personal profile (the work profile/MDM blocks normal APK installs)
adb install -r --user 0 uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk
```

- Debug build uses applicationId `org.isoron.uhabits.hc`, label "Habits HC", so it lives
  next to the Play Store Loop. Always install with `-r` to keep data.
- Signed with this Mac's debug keystore (~/.android/debug.keystore). Don't change it,
  or upgrades will fail and require an uninstall (losing data).

## What the fork adds

1. **Health Connect auto-tracking** (numerical habits only)
   - `Habit.autoSource`: EXERCISE (minutes, all activity types), MINDFULNESS (minutes), STEPS.
   - `Habit.autoSourceApp`: optional package filter. Exercise defaults to Garmin Connect
     (`com.garmin.android.apps.connectmobile`) because Strava duplicates the same workouts;
     mindfulness defaults to the most frequent writer (Medito).
   - Session sources: overlapping sessions are merged (interval union) so nothing is counted twice.
   - Steps: Health Connect `aggregateGroupByPeriod` (HC de-duplicates by app priority).
   - Sync never lowers an existing value (so manual totals typed in Loop are kept).
   - Backfill: each habit gets a one-time 365-day sync (marker in pref `hc_backfilled`, keyed by
     habit/source/app/history-access); regular syncs cover 7 days. `READ_HEALTH_DATA_HISTORY` is
     requested once when picking a source; without it HC returns only ~30 days before first grant.
   - `uhabits-android/.../hc/HealthConnectSync.kt` (logic), `HealthSyncWorker.kt` (hourly
     WorkManager job + one-off on app open / habit save), `HealthPermissionsRationaleActivity.kt`.
2. **Goal history**: `Habit.targetHistory` (list of `TargetChange(from, value)`);
   `Habit.targetAt(date)` is used by `EntryList.recomputeCompletion` and `ScoreList.recompute`
   so raising a goal doesn't rewrite past days. Edit screen calls `habit.changeTarget()`.
3. **Per-habit pause**: `Habit.pauses` (list of `PauseRange(start, end?)`, end null = until
   resumed). Paused days with no data become virtual SKIP entries in `Habit.recompute()`
   (not written to the DB). Reminders are suppressed in `ReminderController.onShowReminder`.
   UI: "Pause" row on the edit habit screen.
   Streaks: paused days keep a streak alive but don't add to its length (`Streak.paused`,
   `StreakList.recompute(isPaused)`); manual skips still count as upstream does.
   Indicators: greyed name/ring in the list (`HabitCardState.paused`), "Paused until …" toolbar
   subtitle on the habit screen, "N (Paused)" on the current streak bar (`Streak.pausedNow`).
4. **Habit details zoom**: Settings → Habit details zoom (`pref_ui_zoom`, 85–175%). Applies only
   to `ShowHabitActivity` (the list stays at 100% so a full week fits). `zoom/UiZoom.kt` scales
   the density in `attachBaseContext` and recreates the screen on resume if the value changed.
   Another screen opts in by overriding `attachBaseContext` with `UiZoom.wrap(this, newBase)`.
5. **Google Sans Flex** (Pixel system font; falls back to Roboto elsewhere): `android:fontFamily`
   in the themes in `styles.xml`, `HcToolbar*` styles for toolbar titles, and `fonts/AppFonts.kt`
   for canvas-drawn text (charts, rings, list header/numbers, calendar). List numbers use
   `wdth 85` instead of sans-serif-condensed. Weights: titles and card headings 600, habit names
   and the paused subtitle 500.
6. **Ring view**: toolbar toggle next to "+" (`actionRingView`, pref `pref_ring_view`) swaps the
   week list for `ring/RingViews.kt`: one progress ring per habit for today, 2 x 3 per page in
   list order, swipe/dots between pages (pref `pref_ring_page`). Tap = toggle or number popup via
   `ListHabitsBehavior`; long-press = habit screen. Data: `HabitCardListAdapter.habitsInListOrder()`,
   `HabitCardState.targetValue` / `currentStreak` (`StreakList.current`).
   Tile (`RingTileView`): no card, ring ~34% of screen width, 5.5% line. Inside: habit icon
   (or first letter) and "N days" (orange flame when the current streak equals `bestStreak`,
   2+ days). Below: name (17sp) and `progressText()` (13sp; grey only when paused). Done today =
   solid disc in the habit color, content in white/near-black via `contrastingInk()`, no ring
   arcs drawn (they leave a seam). Tapping the name/progress line opens the habit.
7. **"Habit rings" widget**: `ring/RingsWidgetProvider.kt` + `layout/widget_rings.xml` (6 cells,
   unused ones hidden) + `xml/widget_rings_info.xml` (`previewLayout` = sample habits). First 4
   (2 x 2) or 6 (3 x 2, width >= 250dp) habits in list order via `ListHabitsPresenter`; rings
   are bitmaps from `RingTileView(ringOnly = true)`. Ring tap = `toggleCheckmark` /
   `showNumberPicker`; name tap = `showHabit`. Matches Loop's widgets (grey_850, 18dp corners,
   widget opacity, `WidgetTheme` palette); colors pass through `liftForContrast` (>= 25%,
   >= 40% for hues 220-300, then up to 4.5:1). Refreshed via a line in `WidgetUpdater`;
   registered in the manifest. A widget can only be placed by the user, not via adb.
8. **Habit icons**: `Habit.icon` (symbol name; empty = first letter), "Icon" row on the edit
   screen with a grid picker. `ring/HabitIcons.kt` lists the picker icons; drawables are
   `res/drawable/hc_icon_*.xml`, Material Symbols Rounded (filled, Apache 2.0), converted from
   `https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/<name>/fill1/24px.svg`
   (viewBox 0 -960 960 960 -> `group translateY=960`). To add one: fetch, convert, add to the list.

Core model code: `uhabits-core/.../core/models/HabitAutomation.kt` (AutoSource, TargetChange,
PauseRange, codecs, PauseLogic). Persistence: migrations `uhabits-core/assets/main/migrations/27.sql`
(columns auto_source, auto_source_app, target_history, pauses) and `28.sql` (icon), `HabitRepository`,
`SQLiteHabitList.copyFrom/copyTo`, `LoopDBImporter`. DATABASE_VERSION = 28.

## Status

- Builds and runs on the Pixel. Verified on device: permission flow, Health Connect sync
  (exercise, mindfulness, steps), backfill, pause (dialog, skip days, streaks, indicators),
  zoom, fonts, ring view, rings widget (rendering).
- Not yet tested on device: target history (raising a goal), ring-view and widget taps.
- The phone is the user's daily device and is often locked or in another app. Before any
  `adb shell input`, check `dumpsys activity activities | grep topResumedActivity` shows
  `org.isoron.uhabits.hc`, in the same command as the tap. The list screen may appear as
  `org.isoron.uhabits.MainActivity` (launcher alias) or `...activities.habits.list.ListHabitsActivity`.
- Background commands run one at a time (`Dispatchers.IO.limitedParallelism(1)` in
  `HabitsApplicationComponent`); repositories share cached prepared statements, so don't
  make them concurrent again.
- Activity names in the manifest's `PARENT_ACTIVITY` must be fully qualified, because the
  `.hc` applicationId suffix breaks relative names.

## Conventions

- Kotlin, ktlint style (imports sorted lexicographically, java/kotlin imports last).
- New UI strings go in `uhabits-android/src/main/res/values/strings_hc.xml` (translatable="false").
- Keep changes minimal and isolated so upstream merges stay easy.
