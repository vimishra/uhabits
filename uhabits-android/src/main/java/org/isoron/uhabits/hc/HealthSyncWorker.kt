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
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Background job that copies Health Connect totals into auto-tracked habits.
 */
class HealthSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val days = inputData.getInt(KEY_DAYS, DEFAULT_DAYS)
        return try {
            val written = HealthConnectSync.syncAll(applicationContext, days)
            Log.i(TAG, "Synced $written entries from Health Connect")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HealthSyncWorker"
        private const val KEY_DAYS = "days"
        private const val DEFAULT_DAYS = 7
        private const val PERIODIC_NAME = "health-connect-sync"
        private const val ONE_TIME_NAME = "health-connect-sync-now"

        /** Runs every hour while the app is installed. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_DAYS to DEFAULT_DAYS))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Runs once, as soon as possible. */
        fun syncNow(context: Context, days: Int = DEFAULT_DAYS) {
            val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setInputData(workDataOf(KEY_DAYS to days))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
