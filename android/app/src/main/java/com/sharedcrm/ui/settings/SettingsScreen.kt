package com.sharedcrm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sharedcrm.sync.SyncWorker

/**
 * Settings screen per PRD:
 * - Sync interval (manual, 15m, 30m, 1h, 6h)
 * - Manual sync button
 * - Wi-Fi-only toggle (uses UNMETERED constraint)
 * - Sync log placeholder (to be wired to Room entity later)
 * - Admin panel placeholder note (Dashboard recommended for user management)
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current

    // Sync interval options in minutes
    val intervals = listOf(
        0L to "Manual",
        15L to "15m",
        30L to "30m",
        60L to "1h",
        360L to "6h"
    )

    var selectedInterval by remember { mutableLongStateOf(30L) }
    var wifiOnly by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Sync Interval",
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            intervals.forEach { (minutes, label) ->
                Button(
                    onClick = { selectedInterval = minutes },
                    enabled = selectedInterval != minutes
                ) {
                    Text(label)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = if (wifiOnly) "Wi‑Fi only (Unmetered)" else "Allow Cellular")
        }

        // Manual sync actions
        Text(
            text = "Sync Actions",
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                // Manual one-time sync regardless of interval
                SyncWorker.enqueueOneTime(context = context, requireUnmetered = wifiOnly)
            }) {
                Text("Manual Sync Now")
            }
            Button(onClick = {
                // Schedule periodic sync if interval > 0
                val interval = selectedInterval
                if (interval > 0) {
                    SyncWorker.enqueuePeriodic(
                        context = context,
                        intervalMinutes = interval,
                        requireUnmetered = wifiOnly
                    )
                }
            }, enabled = selectedInterval > 0) {
                Text("Schedule Periodic")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Sync Log placeholder section
        Text(
            text = "Sync Log",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Recent sync attempts will appear here with timestamps, successes, and failures. (To be implemented)",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Admin panel note
        Text(
            text = "Admin Panel",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Admin-only user management is recommended via Supabase Dashboard. In-app admin operations should avoid using service_role keys.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}