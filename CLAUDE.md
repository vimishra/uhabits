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
   - `uhabits-android/.../hc/HealthConnectSync.kt` (logic), `HealthSyncWorker.kt` (hourly
     WorkManager job + one-off on app open / habit save), `HealthPermissionsRationaleActivity.kt`.
2. **Goal history**: `Habit.targetHistory` (list of `TargetChange(from, value)`);
   `Habit.targetAt(date)` is used by `EntryList.recomputeCompletion` and `ScoreList.recompute`
   so raising a goal doesn't rewrite past days. Edit screen calls `habit.changeTarget()`.
3. **Per-habit pause**: `Habit.pauses` (list of `PauseRange(start, end?)`, end null = until
   resumed). Paused days with no data become virtual SKIP entries in `Habit.recompute()`
   (not written to the DB). Reminders are suppressed in `ReminderController.onShowReminder`.
   UI: "Pause" row on the edit habit screen.

Core model code: `uhabits-core/.../core/models/HabitAutomation.kt` (AutoSource, TargetChange,
PauseRange, codecs, PauseLogic). Persistence: migration `uhabits-core/assets/main/migrations/27.sql`
(columns auto_source, auto_source_app, target_history, pauses), `HabitRepository`,
`SQLiteHabitList.copyFrom/copyTo`, `LoopDBImporter`. DATABASE_VERSION = 27.

## Status

- The code was written without a compiler; the first build on this Mac is the first real compile.
  Fix any compile errors in `build.log` first.
- Not yet tested on device: permission flow, background sync, pause UI, target history.

## Conventions

- Kotlin, ktlint style (imports sorted lexicographically, java/kotlin imports last).
- New UI strings go in `uhabits-android/src/main/res/values/strings_hc.xml` (translatable="false").
- Keep changes minimal and isolated so upstream merges stay easy.
