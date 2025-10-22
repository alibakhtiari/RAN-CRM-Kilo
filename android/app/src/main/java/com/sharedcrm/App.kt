package com.sharedcrm

import android.app.Application
import android.util.Log
import com.sharedcrm.sync.SyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Default: enqueue periodic sync (can be made configurable from Settings later)
        try {
            SyncWorker.enqueuePeriodic(
                context = this,
                intervalMinutes = 30L,
                requireUnmetered = false
            )
            // Also trigger an immediate one-time sync when app starts
            SyncWorker.enqueueOneTime(context = this, requireUnmetered = false)
            Log.i("App", "Sync scheduled (periodic + one-time)")
        } catch (t: Throwable) {
            Log.e("App", "Failed to schedule sync: ${t.message}", t)
        }
    }
}