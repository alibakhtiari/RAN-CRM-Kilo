package com.sharedcrm.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local call cache entity for offline-first processing.
 *
 * One-way upload from device to Supabase. We keep enough metadata to
 * dedupe and to present call history per contact.
 */
@Entity(
    tableName = "calls",
    indices = [
        Index(value = ["serverId"], unique = true),
        Index(value = ["contactServerId"]),
        Index(value = ["phoneNormalized"]),
        Index(value = ["startTime"])
    ]
)
data class CallEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0L,

    // Server-side UUID for calls.id
    val serverId: String? = null,

    // Optional foreign-key by server contact id if resolved, else null (match by normalized phone)
    val contactServerId: String? = null,

    // Original and normalized phone
    val phoneRaw: String,
    val phoneNormalized: String,

    // Who made/received (uploader) - profiles.id (uuid) on server; locally we may not know yet
    val userId: String? = null,

    // "incoming" or "outgoing"
    val direction: String,

    // Epoch millis for start time (maps to timestamptz on server)
    val startTime: Long,

    // Duration seconds
    val durationSeconds: Int = 0,

    // Upload state to Supabase
    val uploaded: Boolean = false,

    // Versioning & sync
    val version: Int = 1,
    val lastSyncedAt: Long? = null
)