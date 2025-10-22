package com.sharedcrm.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sync queue holds pending operations to push to Supabase when online.
 *
 * operation: "insert" | "update" | "delete"
 * entityType: "contact" | "call"
 * payload: JSON string representing the entity delta to push (Postgrest payload)
 * attempts: retry count for exponential backoff
 * lastAttemptAt: epoch millis of last attempt
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["entityType"]),
        Index(value = ["operation"])
    ]
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val entityType: String,  // "contact" | "call"
    val operation: String,   // "insert" | "update" | "delete"

    val payload: String,     // JSON payload to send to Supabase

    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,

    val createdAt: Long = System.currentTimeMillis()
)