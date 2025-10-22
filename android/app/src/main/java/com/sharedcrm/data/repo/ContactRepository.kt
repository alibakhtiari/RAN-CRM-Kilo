package com.sharedcrm.data.repo

import com.sharedcrm.core.PhoneNormalizer
import com.sharedcrm.core.SupabaseConfig
import com.sharedcrm.data.local.AppDatabase
import com.sharedcrm.data.local.entities.ContactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        // TODO: Obtain Supabase client and current user id if needed for created_by fields
        // val client = SupabaseClientProvider.get()
        // val postgrest = client.postgrest

        dirty.forEach { local ->
            try {
                // Example pseudo-logic:
                // val payload = mapOf(
                //   "org_id" to SupabaseConfig.orgId,
                //   "name" to local.name,
                //   "phone_raw" to local.phoneRaw,
                //   "phone_normalized" to local.phoneNormalized,
                //   "created_by" to currentUserId
                // )
                //
                // val inserted = postgrest["contacts"].insert(payload) { /* return=representation */ }
                //
                // After success:
                // val serverId = inserted.id as String
                // contactsDao.upsert(local.copy(serverId = serverId, createdAt = serverCreatedAt, updatedAt = serverUpdatedAt))
                // contactsDao.markSynced(local.localId)

                // Placeholder until implemented
                contactsDao.markSynced(local.localId)
            } catch (t: Throwable) {
                // Detect duplicate-trigger error message:
                // The DB trigger raises: "Duplicate phone exists and is older or equal; insert rejected"
                val isDuplicateOlderWins = t.message?.contains("Duplicate phone exists and is older or equal", ignoreCase = true) == true
                if (isDuplicateOlderWins) {
                    // Per PRD: client should treat as duplicate and remove local newer copy (or mark conflict)
                    contactsDao.setConflict(local.localId, "duplicate")
                    // Optionally delete local contact:
                    // contactsDao.delete(local)
                } else {
                    // Leave dirty; a later retry/backoff will handle it
                    // Optionally write to SyncLog (to be added)
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
        // TODO: val client = SupabaseClientProvider.get()
        // TODO: query: /contacts?org_id=eq.<org>&updated_at=gt.<since> order=updated_at.asc limit=...
        // Map rows to ContactEntity (ensuring normalized phone), upsertAll, return max updated_at

        // Placeholder no-op behavior
        val now = System.currentTimeMillis()
        now
    }
}