package com.sharedcrm.device

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.sharedcrm.data.local.AppDatabase
import com.sharedcrm.data.local.entities.ContactEntity
import com.sharedcrm.core.PhoneNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DeviceContacts provides utilities to sync local Room cache into the device Contacts provider
 * and dedupe duplicates by normalized phone.
 *
 * Notes:
 * - Requires READ_CONTACTS and WRITE_CONTACTS permissions.
 * - Actual provider write operations are implemented with ContentProviderOperations.
 * - Dedupe logic uses normalized phone; when multiple device contacts share the same normalized phone,
 *   keep the one that matches server's created_at (older wins) based on local cache metadata.
 *
 * This is a skeleton aligned with PRD. Detailed reads/writes and edge cases will be implemented incrementally.
 */
object DeviceContacts {

    /**
     * Sync local Room contact cache to device contacts:
     * - For each cached contact, ensure a single device contact exists with primary phone.
     * - If not present, insert a device contact.
     * - If present but name/number differ, update.
     *
     * After syncing, run a dedupe pass to enforce "one device contact per normalized phone".
     */
    suspend fun syncLocalCacheToDevice(context: Context, db: AppDatabase): Unit = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val contacts = db.contactsDao().getAll()

        contacts.forEach { contact ->
            try {
                upsertDeviceContact(resolver, contact)
            } catch (t: Throwable) {
                Log.e("DeviceContacts", "Failed to upsert device contact: ${contact.phoneNormalized}: ${t.message}", t)
            }
        }

        try {
            dedupeDeviceByNormalizedPhone(context, db, contacts)
        } catch (t: Throwable) {
            Log.e("DeviceContacts", "Dedupe failed: ${t.message}", t)
        }
    }

    /**
     * Upsert a contact into the device contacts provider.
     *
     * Skeleton implementation:
     * - Attempts to find existing raw contact by phone number.
     * - If not found, inserts a new raw contact with name + phone.
     * - If found, updates name if different.
     *
     * TODO: Implement robust matching using ContactsContract.PhoneLookup and RawContacts.
     */
    private fun upsertDeviceContact(resolver: ContentResolver, contact: ContactEntity) {
        val ops = ArrayList<ContentProviderOperation>()

        val targetNumber = if (contact.phoneNormalized.isNotBlank()) contact.phoneNormalized else contact.phoneRaw
        val existingId: Long? = findContactIdByPhone(resolver, targetNumber)

        if (existingId == null) {
            // Insert new contact
            val rawContactInsertIndex = ops.size
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            // Display name
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.name)
                    .build()
            )
            // Phone number (use normalized or raw fallback)
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, targetNumber)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
        } else {
            // Update display name if different
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                        arrayOf(existingId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.name)
                    .build()
            )
            // Ensure phone number exists/updated (optional further enhancement)
            // Could update Phone.NUMBER similarly if needed
        }

        if (ops.isNotEmpty()) {
            try {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                Log.i("DeviceContacts", "Applied ${ops.size} ops for ${contact.phoneNormalized}")
            } catch (t: Throwable) {
                Log.e("DeviceContacts", "applyBatch failed: ${t.message}", t)
            }
        }
    }

    /**
     * Dedupe device contacts by normalized phone:
     * - Build a map normalized_phone -> list of device contact IDs
     * - Keep the one whose created_at equals server's created_at (older wins), delete others
     *
     * TODO:
     * - Implement reading device contacts and constructing normalized-phone grouping
     * - Compare with local cache createdAt to decide which device contact to keep
     * - Delete newer duplicates via ContactsContract.RawContacts and Data deletes
     */
    private fun dedupeDeviceByNormalizedPhone(context: Context, db: AppDatabase, cache: List<ContactEntity>) {
        Log.i("DeviceContacts", "Starting dedupe pass by normalized phone (count=${cache.size})")
        val resolver = context.contentResolver

        // Build normalized groups from device contacts
        val devicePhones = readAllDevicePhones(resolver)
        val groups = mutableMapOf<String, MutableList<Long>>() // normalized -> list of CONTACT_IDs

        devicePhones.forEach { (contactId, rawPhone) ->
            val normalized = PhoneNormalizer.normalizeWithFallback(rawPhone, com.sharedcrm.core.SupabaseConfig.defaultRegion)
            if (normalized != null) {
                groups.getOrPut(normalized) { mutableListOf() }.add(contactId)
            }
        }

        // Cache map for server-created ordering
        val cacheByNormalized = cache.associateBy { it.phoneNormalized }

        var duplicatesFound = 0
        groups.forEach { (normalized, ids) ->
            if (ids.size > 1) {
                duplicatesFound++
                val cacheEntry = cacheByNormalized[normalized]
                val keepId = ids.first() // default keep the first; refine with cache createdAt if available

                // Prefer keeping the device contact corresponding to the older server created_at, if known
                // Without direct mapping, try to find the canonical contact via PhoneLookup using normalized phone
                val canonicalKeepId = findContactIdByPhone(resolver, normalized) ?: keepId
                Log.w("DeviceContacts", "Duplicate normalized phone $normalized found in device: ids=$ids; keep=$canonicalKeepId, serverCreatedAt=${cacheEntry?.createdAt}")

                // Delete other device contacts for this normalized phone (requires WRITE_CONTACTS)
                val ops = arrayListOf<ContentProviderOperation>()
                ids.filter { it != canonicalKeepId }.forEach { cid ->
                    ops.add(
                        ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                            .withSelection("${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(cid.toString()))
                            .build()
                    )
                }

                if (ops.isNotEmpty()) {
                    try {
                        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                        Log.i("DeviceContacts", "Deleted ${ops.size} duplicate raw contacts for normalized=$normalized, kept contactId=$canonicalKeepId")
                    } catch (t: Throwable) {
                        Log.e("DeviceContacts", "Failed deleting duplicates for normalized=$normalized: ${t.message}", t)
                    }
                }
            }
        }

        Log.i("DeviceContacts", "Dedupe completed. duplicateGroups=$duplicatesFound")
    }

    /**
     * Lookup a device contact ID by phone using PhoneLookup.
     */
    private fun findContactIdByPhone(resolver: ContentResolver, phone: String): Long? {
        val sanitized = contactPhoneDigits(phone)
        val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(sanitized))
        @Suppress("MissingPermission")
        resolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup._ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    /**
     * Read all phone numbers from device contacts (CONTACT_ID, NUMBER).
     */
    private fun readAllDevicePhones(resolver: ContentResolver): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        @Suppress("MissingPermission")
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
            null
        )?.use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val idxNum = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idxId)
                val num = cursor.getString(idxNum) ?: continue
                result.add(id to num)
            }
        }
        return result
    }

    private fun contactPhoneDigits(s: String): String = s.filter { it.isDigit() || it == '+' }

}