package com.sharedcrm.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local contact cache entity for offline-first operation.
 *
 * Notes:
 * - Uses a local auto-generated primary key (localId) to support records before server ID exists.
 * - serverId mirrors Supabase contacts.id (uuid) when available.
 * - phoneNormalized is unique locally to avoid duplicates on device; server-side uniqueness is enforced by DB.
 * - Timestamps stored as epoch millis for simplicity; map to timestamptz on server.
 */
@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["phoneNormalized"], unique = true),
        Index(value = ["serverId"], unique = true)
    ]
)
data class ContactEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0L,

    // Server-side UUID for contacts.id
    val serverId: String? = null,

    // Organization ID if needed locally (single-org setup can store it once or per entity)
    val orgId: String? = null,

    // Display name
    val name: String,

    // Raw phone string as entered/displayed
    val phoneRaw: String,

    // E.164 normalized phone (e.g., +989123456789) used for dedupe and server sync
    val phoneNormalized: String,

    // Ownership metadata
    val createdBy: String? = null, // profiles.id (uuid)
    val createdAt: Long? = null,   // epoch millis
    val updatedBy: String? = null,
    val updatedAt: Long? = null,   // epoch millis

    // Versioning
    val version: Int = 1,

    // Sync metadata
    val dirty: Boolean = false,        // local changes pending upload
    val lastModified: Long = System.currentTimeMillis(),
    val lastSyncedAt: Long? = null,    // last time synced with server

    // Conflict marker (e.g., "duplicate")
    val conflict: String? = null
)