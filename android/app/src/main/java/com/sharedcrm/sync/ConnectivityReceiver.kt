package com.sharedcrm.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

/**
 * ConnectivityReceiver triggers a one-time sync when connectivity is available.
 * Declared in AndroidManifest and aligned with PRD: schedule sync on connectivity change.
 */
class ConnectivityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (isConnected(context)) {
                // Enqueue a one-time sync when network becomes available
                SyncWorker.enqueueOneTime(context = context, requireUnmetered = false)
                Log.i("ConnectivityReceiver", "Connectivity available, one-time sync enqueued")
            } else {
                Log.i("ConnectivityReceiver", "Connectivity lost or unavailable")
            }
        } catch (t: Throwable) {
            Log.e("ConnectivityReceiver", "Error handling connectivity change: ${t.message}", t)
        }
    }

    private fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            // Require internet capability
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } else {
            // minSdk is 24 (>= M), but keep fallback for completeness
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnectedOrConnecting == true
        }
    }
}