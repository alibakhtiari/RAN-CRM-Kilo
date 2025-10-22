package com.sharedcrm.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver schedules periodic background sync after device boot.
 * Requires RECEIVE_BOOT_COMPLETED permission and a manifest receiver entry.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                // Default interval can be adjusted later from Settings (e.g., 15m, 30m, 1h, 6h)
                SyncWorker.enqueuePeriodic(
                    context = context,
                    intervalMinutes = 30L,
                    requireUnmetered = false
                )
                Log.i("BootReceiver", "Periodic sync enqueued after boot")
            } catch (t: Throwable) {
                Log.e("BootReceiver", "Failed to enqueue periodic sync: ${t.message}", t)
            }
        }
    }
}