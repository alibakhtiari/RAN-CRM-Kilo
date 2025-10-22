package com.sharedcrm.data.repo

import android.content.Context
import android.provider.CallLog
import com.sharedcrm.core.PhoneNormalizer
import com.sharedcrm.core.SupabaseConfig
import com.sharedcrm.data.local.AppDatabase
import com.sharedcrm.data.local.entities.CallEntity
import com.sharedcrm.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
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

                // Dedupe: skip if same normalized phone and start time already exists
                val exists = callsDao.existsByPhoneAndStart(normalized, startMillis) > 0
                if (exists) continue

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

        val client = SupabaseClientProvider.get()
        val postgrest = client.postgrest

        pending.forEach { local ->
            try {
                val startIso = Instant.ofEpochMilli(local.startTime).toString()
                val payload = buildMap {
                    put("org_id", SupabaseConfig.orgId)
                    // contact_id may be null; server-side can associate via phone_normalized
                    local.contactServerId?.let { put("contact_id", it) }
                    put("phone_raw", local.phoneRaw)
                    put("phone_normalized", local.phoneNormalized)
                    currentUserId?.let { put("user_id", it) }
                    put("direction", local.direction)
                    put("start_time", startIso)
                    put("duration_seconds", local.durationSeconds)
                    put("version", local.version)
                }

                // Insert into calls table; rely on RLS policies and server schema
                postgrest["calls"].insert(payload)

                // Mark as uploaded locally
                callsDao.markUploaded(local.localId)
            } catch (_: Throwable) {
                // Leave as not uploaded; it will be retried in subsequent sync cycles with backoff
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
        val client = SupabaseClientProvider.get()
        val postgrest = client.postgrest

        val isoSince = sinceEpochMillis?.let { Instant.ofEpochMilli(it).toString() }

        val result = postgrest["calls"].select(columns = "*") {
            filter {
                eq("org_id", SupabaseConfig.orgId)
                if (isoSince != null) {
                    gt("start_time", isoSince)
                }
            }
            order("start_time", ascending = true)
            // Optional: add pagination via range if dataset is large
        }

        val rows = result.decodeList<Map<String, Any?>>()
        if (rows.isEmpty()) {
            return@withContext sinceEpochMillis ?: 0L
        }

        val entities = rows.mapNotNull { row ->
            val id = row["id"] as? String
            val contactId = row["contact_id"] as? String
            val phoneRaw = row["phone_raw"] as? String ?: return@mapNotNull null
            val phoneNorm = row["phone_normalized"] as? String ?: return@mapNotNull null
            val userId = row["user_id"] as? String
            val direction = row["direction"] as? String ?: "incoming"
            val startIso = row["start_time"] as? String ?: return@mapNotNull null
            val startEpoch = runCatching { Instant.parse(startIso).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            val duration = (row["duration_seconds"] as? Number)?.toInt() ?: 0
            val version = (row["version"] as? Number)?.toInt() ?: 1

            CallEntity(
                serverId = id,
                contactServerId = contactId,
                phoneRaw = phoneRaw,
                phoneNormalized = phoneNorm,
                userId = userId,
                direction = direction,
                startTime = startEpoch,
                durationSeconds = duration,
                uploaded = true,
                version = version,
                lastSyncedAt = System.currentTimeMillis()
            )
        }

        if (entities.isNotEmpty()) {
            callsDao.upsertAll(entities)
        }

        val lastIso = rows.lastOrNull()?.get("start_time") as? String
        lastIso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: (sinceEpochMillis ?: 0L)
    }

    /**
     * Utility: convert minutes to millis for scheduling if needed.
     */
    fun minutesToMillis(m: Long): Long = TimeUnit.MINUTES.toMillis(m)
}