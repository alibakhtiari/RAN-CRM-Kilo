package com.sharedcrm.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * SyncWorker coordinates:
 * - Push dirty contacts (handle duplicate rejection -> keep older, delete local newer)
 * - Pull contacts since last sync, write to device contacts, dedupe by normalized phone and created_at
 * - Read new CallLog entries and upload
 * - Pull calls since last sync to update local cache (for Contact Detail screen)
 * - Write sync results to a local SyncLog
 *
 * This is a skeleton; repositories and device providers will be wired in later.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tag = "SyncWorker"
        Log.i(tag, "Sync started")

        try {
            // TODO: Inject repositories via DI (e.g., Hilt) or service locator
            // val contactRepo = ...
            // val callRepo = ...
            // val deviceContacts = ...
            // val callLogReader = ...

            // TODO: Push dirty contacts (respect server trigger: older wins)
            // contactRepo.pushDirtyContacts()

            // TODO: Pull remote contacts since last sync and merge
            // contactRepo.pullContactsSinceLastSync()
            // deviceContacts.applyServerContactsAndDedupe()

            // TODO: Upload new call logs
            // callRepo.pushNewCalls()

            // TODO: Pull remote calls since last sync for UI cache
            // callRepo.pullCallsSinceLastSync()

            // Placeholder delay to simulate work
            delay(250)

            Log.i(tag, "Sync finished successfully")
            return Result.success()
        } catch (t: Throwable) {
            Log.e(tag, "Sync failed: ${t.message}", t)
            // TODO: Implement exponential backoff and error categorization
            return Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "SharedContactCRM_Sync"

        fun periodicRequest(
            intervalMinutes: Long = 15L,
            requireUnmetered: Boolean = false
        ): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            return PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()
        }

        fun enqueuePeriodic(
            context: Context,
            intervalMinutes: Long = 15L,
            requireUnmetered: Boolean = false
        ) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest(intervalMinutes, requireUnmetered)
            )
        }

        fun enqueueOneTime(context: Context, requireUnmetered: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}