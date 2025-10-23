package com.sharedcrm.data.repo

import com.sharedcrm.core.PhoneNormalizer
import com.sharedcrm.core.SupabaseConfig
import com.sharedcrm.data.local.AppDatabase
import com.sharedcrm.data.local.entities.ContactEntity
import com.sharedcrm.data.remote.AuthManager
import com.sharedcrm.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * ContactRepository
 *
 * Responsibilities (per PRD):
 * - Normalize and upsert local contacts (mark dirty)
 * - Push dirty contacts to Supabase (handle server duplicate trigger: older wins)
 * - Pull remote contacts incrementally by updated_at and merge into local cache
 * - After pull, dedupe device contacts by phoneNormalized and created_at (handled by device layer)
 *
 * Note: This is a skeleton. Postgrest calls and error parsing are left as TODO
 * to be implemented with io.github.jan-tennert.supabase client.
 */
class ContactRepository(
    private val db: AppDatabase
) {
    private val contactsDao = db.contactsDao()

    /**
     * Normalize phone and upsert a local contact, marking it dirty for sync.
     * Returns the localId of the row or null if normalization failed.
     */
    suspend fun normalizeAndUpsertLocal(
        name: String,
        phoneRaw: String,
        createdBy: String? = null,
        region: String? = null
    ): Long? = withContext(Dispatchers.IO) {
        val normalized = PhoneNormalizer.normalizeWithFallback(phoneRaw, region ?: SupabaseConfig.defaultRegion)
            ?: return@withContext null

        // If exists, update; else insert new
        val existing = contactsDao.getByNormalizedPhone(normalized)
        val now = System.currentTimeMillis()

        val entity = if (existing != null) {
            existing.copy(
                name = name,
                phoneRaw = phoneRaw,
                updatedBy = createdBy,
                updatedAt = now,
                dirty = true,
                lastModified = now
            )
        } else {
            ContactEntity(
                name = name,
                phoneRaw = phoneRaw,
                phoneNormalized = normalized,
                createdBy = createdBy,
                createdAt = now,
                updatedBy = createdBy,
                updatedAt = now,
                dirty = true,
                lastModified = now,
                orgId = SupabaseConfig.orgId.ifBlank { null }
            )
        }

        contactsDao.upsert(entity)
    }

    /**
     * Push dirty contacts to Supabase.
     *
     * Algorithm (per PRD):
     * - For each dirty contact, try INSERT into contacts table (avoid blind upsert to respect trigger).
     * - If server rejects as duplicate (older exists), mark local as conflict "duplicate" and delete local newer copy.
     * - On success, set serverId/created_at/updated_at and markSynced.
     *
     * TODO: Implement actual Postgrest calls and precise error handling for trigger exception.
     */
    suspend fun pushDirtyContacts(): Unit = withContext(Dispatchers.IO) {
        val dirty = contactsDao.getDirtyContacts()
        if (dirty.isEmpty()) return@withContext

        val currentUserId = AuthManager.currentUserId()
        if (currentUserId.isNullOrBlank()) {
            // Not authenticated; skip pushing
            return@withContext
        }

        val client = SupabaseClientProvider.get()
        val postgrest = client.postgrest

        dirty.forEach { local ->
            try {
                val createdAtIso = local.createdAt?.let { Instant.ofEpochMilli(it).toString() }
                val updatedAtIso = local.updatedAt?.let { Instant.ofEpochMilli(it).toString() }

                val payload = buildMap {
                    put("org_id", SupabaseConfig.orgId)
                    put("name", local.name)
                    put("phone_raw", local.phoneRaw)
                    put("phone_normalized", local.phoneNormalized)
                    put("created_by", currentUserId)
                    if (createdAtIso != null) put("created_at", createdAtIso)
                    if (updatedAtIso != null) put("updated_at", updatedAtIso)
                    put("version", local.version)
                }

                // Insert explicitly to respect trigger (avoid upsert). Request representation back and decode
                val insertedRows = postgrest["contacts"]
                    .insert(payload)
                    .decodeList<Map<String, Any?>>()

                // Prefer representation from insert; fallback to select by unique keys if empty
                val row = insertedRows.firstOrNull() ?: postgrest["contacts"].select() {
                    filter {
                        eq("org_id", SupabaseConfig.orgId)
                        eq("phone_normalized", local.phoneNormalized)
                    }
                }.decodeList<Map<String, Any?>>().firstOrNull()

                val serverId = row?.get("id") as? String
                val serverCreatedAt = row?.get("created_at") as? String
                val serverUpdatedAt = row?.get("updated_at") as? String

                val serverCreatedAtEpoch = serverCreatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                val serverUpdatedAtEpoch = serverUpdatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

                // Update local with serverId and mark as synced, propagating server timestamps when available
                val updated = local.copy(
                    serverId = serverId,
                    createdAt = serverCreatedAtEpoch ?: local.createdAt,
                    updatedAt = serverUpdatedAtEpoch ?: local.updatedAt
                )
                contactsDao.upsert(updated)
                contactsDao.markSynced(local.localId)
            } catch (t: Throwable) {
                val isDuplicateOlderWins = t.message?.contains("Duplicate phone exists and is older or equal", ignoreCase = true) == true
                if (isDuplicateOlderWins) {
                    // Mark conflict and remove the local newer copy per PRD (older wins)
                    contactsDao.setConflict(local.localId, "duplicate")
                    contactsDao.deleteByLocalIds(listOf(local.localId))
                } else {
                    // Leave dirty for retry; could enqueue to SyncQueue with backoff
                }
            }
        }
    }

    /**
     * Pull remote contacts since last sync timestamp and merge into local DB.
     *
     * Returns the latest updated_at (epoch millis) observed, for persisting as new "last_sync".
     *
     * TODO: Implement querying Postgrest with a filter updated_at > since, batch/pagination.
     */
    suspend fun pullContactsSinceLastSync(sinceEpochMillis: Long?): Long = withContext(Dispatchers.IO) {
        val client = SupabaseClientProvider.get()
        val postgrest = client.postgrest

        val isoSince = sinceEpochMillis?.let { Instant.ofEpochMilli(it).toString() }

        // Build query: filter by org_id and updated_at > since (if provided), order by updated_at asc
        val result = postgrest["contacts"].select() {
            filter {
                eq("org_id", SupabaseConfig.orgId)
                if (isoSince != null) {
                    gt("updated_at", isoSince)
                }
            }
            order("updated_at")
            limit(500) // basic pagination cap; can loop with offsets if needed
        }

        // Decode as generic maps to avoid strict models at this stage
        val rows = result.decodeList<Map<String, Any?>>()

        if (rows.isEmpty()) {
            return@withContext sinceEpochMillis ?: 0L
        }

        val entities = rows.mapNotNull { row ->
            val id = row["id"] as? String
            val name = row["name"] as? String ?: return@mapNotNull null
            val phoneRaw = row["phone_raw"] as? String ?: return@mapNotNull null
            val phoneNorm = row["phone_normalized"] as? String ?: return@mapNotNull null
            val createdBy = row["created_by"] as? String
            val updatedBy = row["updated_by"] as? String
            val version = (row["version"] as? Number)?.toInt() ?: 1

            // Parse timestamps if needed; store as epoch millis in local
            val createdAtEpoch = (row["created_at"] as? String)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            val updatedAtEpoch = (row["updated_at"] as? String)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

            ContactEntity(
                serverId = id,
                orgId = SupabaseConfig.orgId.ifBlank { null },
                name = name,
                phoneRaw = phoneRaw,
                phoneNormalized = phoneNorm,
                createdBy = createdBy,
                createdAt = createdAtEpoch,
                updatedBy = updatedBy,
                updatedAt = updatedAtEpoch,
                version = version,
                dirty = false,
                lastModified = updatedAtEpoch ?: System.currentTimeMillis(),
                lastSyncedAt = System.currentTimeMillis(),
                conflict = null
            )
        }

        if (entities.isNotEmpty()) {
            contactsDao.upsertAll(entities)
        }

        // Return latest updated_at epoch among rows
        val latestIso = rows.lastOrNull()?.get("updated_at") as? String
        latestIso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: (sinceEpochMillis ?: 0L)
    }
}
