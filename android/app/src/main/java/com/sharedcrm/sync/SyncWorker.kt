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
import com.sharedcrm.data.local.AppDatabase
import com.sharedcrm.data.repo.CallRepository
import com.sharedcrm.data.repo.ContactRepository
import com.sharedcrm.device.DeviceContacts
import com.sharedcrm.data.remote.AuthManager
import com.sharedcrm.data.local.entities.SyncLogEntity
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
            val context = applicationContext

            // Init repositories
            val db = AppDatabase.get(context)
            val logDao = db.syncLogDao()
            val contactRepo = ContactRepository(db)
            val callRepo = CallRepository(db)

            runCatching {
                logDao.insert(SyncLogEntity(type = "system", operation = "start", status = "success", message = "Sync started"))
            }

            // Incremental sync checkpoints
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val lastContactsSync = prefs.getLong(CONTACTS_LAST_SYNC_KEY, 0L)
            val lastCallsSync = prefs.getLong(CALLS_LAST_SYNC_KEY, 0L)

            // 1) Contacts: push local dirty (respect server trigger: "older wins")
            kotlin.runCatching { contactRepo.pushDirtyContacts() }
                .onSuccess {
                    runCatching { logDao.insert(SyncLogEntity(type = "contact", operation = "push", status = "success")) }
                }
                .onFailure { err ->
                    runCatching { logDao.insert(SyncLogEntity(type = "contact", operation = "push", status = "failure", message = err.message)) }
                }

            // 2) Contacts: pull remote changes since last checkpoint and merge
            val latestContactsTs = kotlin.runCatching {
                contactRepo.pullContactsSinceLastSync(if (lastContactsSync == 0L) null else lastContactsSync)
            }.onSuccess { ts ->
                runCatching { logDao.insert(SyncLogEntity(type = "contact", operation = "pull", status = "success", message = "latest=$ts")) }
            }.onFailure { err ->
                runCatching { logDao.insert(SyncLogEntity(type = "contact", operation = "pull", status = "failure", message = err.message)) }
            }.getOrElse { lastContactsSync }

            // Apply pulled contacts to device and run dedupe (keep older by created_at)
            kotlin.runCatching { com.sharedcrm.device.DeviceContacts.syncLocalCacheToDevice(context, db) }
                .onSuccess { runCatching { logDao.insert(SyncLogEntity(type = "device", operation = "apply_contacts", status = "success")) } }
                .onFailure { err -> runCatching { logDao.insert(SyncLogEntity(type = "device", operation = "apply_contacts", status = "failure", message = err.message)) } }

            // 3) Calls: read device call log and insert locally, then push new to server
            kotlin.runCatching { callRepo.readDeviceCallLog(context) }
                .onSuccess { runCatching { logDao.insert(SyncLogEntity(type = "call", operation = "read_device_log", status = "success")) } }
                .onFailure { err -> runCatching { logDao.insert(SyncLogEntity(type = "call", operation = "read_device_log", status = "failure", message = err.message)) } }

            kotlin.runCatching { callRepo.pushNewCalls(currentUserId = com.sharedcrm.data.remote.AuthManager.currentUserId()) }
                .onSuccess { runCatching { logDao.insert(SyncLogEntity(type = "call", operation = "push", status = "success")) } }
                .onFailure { err -> runCatching { logDao.insert(SyncLogEntity(type = "call", operation = "push", status = "failure", message = err.message)) } }

            // 4) Calls: pull remote calls since last checkpoint for UI cache
            val latestCallsTs = kotlin.runCatching {
                callRepo.pullCallsSinceLastSync(if (lastCallsSync == 0L) null else lastCallsSync)
            }.onSuccess { ts ->
                runCatching { logDao.insert(SyncLogEntity(type = "call", operation = "pull", status = "success", message = "latest=$ts")) }
            }.onFailure { err ->
                runCatching { logDao.insert(SyncLogEntity(type = "call", operation = "pull", status = "failure", message = err.message)) }
            }.getOrElse { lastCallsSync }

            // Persist updated checkpoints (use max to avoid regressions)
            val newContactsTs = kotlin.math.max(lastContactsSync, latestContactsTs)
            val newCallsTs = kotlin.math.max(lastCallsSync, latestCallsTs)
            prefs.edit()
                .putLong(CONTACTS_LAST_SYNC_KEY, newContactsTs)
                .putLong(CALLS_LAST_SYNC_KEY, newCallsTs)
                .apply()

            Log.i(tag, "Sync finished successfully: contactsSince=$newContactsTs callsSince=$newCallsTs")
            runCatching {
                logDao.insert(SyncLogEntity(type = "system", operation = "finish", status = "success", message = "contactsSince=$newContactsTs callsSince=$newCallsTs"))
            }
            return Result.success()
        } catch (t: Throwable) {
            Log.e(tag, "Sync failed: ${t.message}", t)
            // Best-effort logging of failure
            runCatching {
                val db = AppDatabase.get(applicationContext)
                db.syncLogDao().insert(SyncLogEntity(type = "system", operation = "error", status = "failure", message = t.message))
            }
            // TODO: Implement exponential backoff and error categorization
            return Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "SharedContactCRM_Sync"

        // SharedPreferences for incremental sync checkpoints
        private const val PREF_NAME = "shared_contact_crm_sync_prefs"
        private const val CONTACTS_LAST_SYNC_KEY = "contacts_last_sync"
        private const val CALLS_LAST_SYNC_KEY = "calls_last_sync"

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

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}