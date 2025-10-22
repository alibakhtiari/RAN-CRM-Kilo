package com.sharedcrm.data.repo

import android.content.Context
import android.provider.CallLog
import com.sharedcrm.core.PhoneNormalizer
import com.sharedcrm.core.SupabaseConfig
import com.sharedcrm.data.local.AppDatabase
import com.sharedcrm.data.local.entities.CallEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * CallRepository
 *
 * Responsibilities (per PRD):
 * - Read device CallLog and normalize phone numbers (E.164)
 * - Dedupe locally by timestamp/phone before upload
 * - Upload new calls to Supabase `calls` table
 * - Pull remote calls since last sync for UI cache (Contact detail)
 *
 * Note: This is a skeleton. Supabase client calls and exact filters are TODOs.
 */
class CallRepository(
    private val db: AppDatabase
) {
    private val callsDao = db.callsDao()

    /**
     * Read device CallLog and insert into local DB if new.
     * Only keeps incoming/outgoing records with valid normalized phone.
     */
    suspend fun readDeviceCallLog(context: Context, region: String? = null): Unit = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        @Suppress("MissingPermission")
        resolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            CallLog.Calls.DATE + " DESC"
        )?.use { cursor ->
            val idxNumber = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val idxType = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val idxDate = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val idxDuration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)

            while (cursor.moveToNext()) {
                val raw = cursor.getString(idxNumber) ?: continue
                val type = cursor.getInt(idxType)
                val startMillis = cursor.getLong(idxDate)
                val durationSec = cursor.getInt(idxDuration)

                val normalized = PhoneNormalizer.normalizeWithFallback(raw, region ?: SupabaseConfig.defaultRegion)
                    ?: continue

                val direction = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "incoming" // treat missed as incoming
                    CallLog.Calls.REJECTED_TYPE -> "incoming" // rejected considered incoming
                    else -> "incoming"
                }

                // Simple dedupe heuristic: if same normalized phone & timestamp exists uploaded/pending, skip
                val existingForPhone = callsDao.observeByPhone(normalized)
                // Flow not suitable in repository sync; rely on DB query methods (not defined for single fetch)
                // For now we upsert; future dedupe could add a unique index on (phoneNormalized, startTime)

                val entity = CallEntity(
                    phoneRaw = raw,
                    phoneNormalized = normalized,
                    direction = direction,
                    startTime = startMillis,
                    durationSeconds = durationSec,
                    uploaded = false
                )
                callsDao.upsert(entity)
            }
        }
    }

    /**
     * Push new, not uploaded calls to Supabase.
     *
     * TODO: Implement actual Postgrest insert batches; markUploaded on success.
     */
    suspend fun pushNewCalls(currentUserId: String?): Unit = withContext(Dispatchers.IO) {
        val pending = callsDao.getPendingUploads()
        if (pending.isEmpty()) return@withContext

        // TODO: val client = SupabaseClientProvider.get(); val postgrest = client.postgrest
        pending.forEach { local ->
            try {
                // Example pseudo-logic:
                // val payload = mapOf(
                //   "org_id" to SupabaseConfig.orgId,
                //   "contact_id" to local.contactServerId, // may be null; server can match by normalized phone
                //   "phone_raw" to local.phoneRaw,
                //   "phone_normalized" to local.phoneNormalized,
                //   "user_id" to currentUserId,
                //   "direction" to local.direction,
                //   "start_time" to Instant.ofEpochMilli(local.startTime).toString(),
                //   "duration_seconds" to local.durationSeconds
                // )
                // postgrest["calls"].insert(payload) { /* return representation */ }
                // callsDao.markUploaded(local.localId)

                // Placeholder: mark uploaded
                callsDao.markUploaded(local.localId)
            } catch (_: Throwable) {
                // Leave as not uploaded; retry in next cycle with backoff
            }
        }
    }

    /**
     * Pull remote calls since last sync and merge into local DB.
     *
     * Returns the latest remote updated_at/start_time observed.
     *
     * TODO: Implement querying with filters (org_id and since), pagination.
     */
    suspend fun pullCallsSinceLastSync(sinceEpochMillis: Long?): Long = withContext(Dispatchers.IO) {
        // TODO: val client = SupabaseClientProvider.get()
        // TODO: query: /calls?org_id=eq.<org>&start_time=gt.<since> order=start_time.asc limit=...
        val now = System.currentTimeMillis()
        now
    }

    /**
     * Utility: convert minutes to millis for scheduling if needed.
     */
    fun minutesToMillis(m: Long): Long = TimeUnit.MINUTES.toMillis(m)
}