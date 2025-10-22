package com.sharedcrm.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SyncLogEntity captures sync attempts/results for display in Settings.
 * Fields:
 * - type: "contact" | "call" | "system"
 * - operation: "push" | "pull" | "schedule" | "retry" | ...
 * - status: "success" | "failure"
 * - message: optional details or error snippet
 * - timestamp: epoch millis
 * - count: affected items count (optional)
 * - durationMs: duration of the operation (optional)
 */
@Entity(
    tableName = "sync_log",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["type"]),
        Index(value = ["status"])
    ]
)
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val type: String,
    val operation: String,
    val status: String,
    val message: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val count: Int? = null,
    val durationMs: Long? = null
)