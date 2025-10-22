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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import com.sharedcrm.data.remote.AuthManager
import com.sharedcrm.data.local.AppDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.sharedcrm.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest

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
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(context) }
    val logsDao = db.syncLogDao()
    val logs by logsDao.observeRecent(100).collectAsState(initial = emptyList())

    // Admin state
    var isAdmin by remember { mutableStateOf<Boolean?>(null) }
    var adminProfiles by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var adminError by remember { mutableStateOf<String?>(null) }

    // Fetch admin status and profiles list (RLS allows SELECT only for admins)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        scope.launch {
            try {
                val client = SupabaseClientProvider.get()
                val postgrest = client.postgrest
                val uid = AuthManager.currentUserId()
                if (!uid.isNullOrBlank()) {
                    // Check current user's role; policy permits only admins to select profiles
                    val meRows = postgrest["profiles"].select(columns = "id, role") {
                        filter { eq("id", uid) }
                        limit(1)
                    }.decodeList<Map<String, Any?>>()
                    val role = meRows.firstOrNull()?.get("role") as? String
                    val admin = role == "admin"
                    isAdmin = admin
                    if (admin) {
                        adminProfiles = postgrest["profiles"].select(
                            columns = "id, email, display_name, role, created_at"
                        ) {
                            order("created_at", ascending = true)
                        }.decodeList()
                    }
                } else {
                    isAdmin = false
                }
            } catch (t: Throwable) {
                // Non-admins will typically hit RLS denial; treat as not admin
                isAdmin = false
                adminError = t.message
            }
        }
    }

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
            Button(onClick = {
                // Cancel the unique periodic work
                SyncWorker.cancelPeriodic(context)
            }) {
                Text("Cancel Periodic")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Sync Log placeholder section
        Text(
            text = "Sync Log",
            style = MaterialTheme.typography.titleMedium
        )
        val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "No recent sync entries.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                logs.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${fmt.format(Date(entry.timestamp))} • ${entry.type}/${entry.operation}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = entry.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.status.lowercase(Locale.ROOT) == "success")
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                    entry.message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Account section
        Text(
            text = "Account",
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                scope.launch {
                    AuthManager.signOut()
                }
            }) {
                Text("Logout")
            }
        )

        // Admin panel
        Text(
            text = "Admin Panel",
            style = MaterialTheme.typography.titleMedium
        )

        when (isAdmin) {
            null -> {
                Text(
                    text = "Checking admin access...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            true -> {
                if (adminProfiles.isEmpty()) {
                    Text(
                        text = "No users found or access restricted.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    // Reuse fmt from Sync Log for dates
                    adminProfiles.forEach { p ->
                        val name = (p["display_name"] as? String)?.ifBlank { p["email"] as? String ?: "Unknown" } ?: "Unknown"
                        val role = p["role"] as? String ?: "user"
                        val createdAt = p["created_at"] as? String
                        val createdAtText = try {
                            val iso = createdAt ?: ""
                            val parsed = java.time.Instant.parse(iso).toEpochMilli()
                            fmt.format(Date(parsed))
                        } catch (_: Throwable) { createdAt ?: "-" }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "$role • $createdAtText",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                adminError?.let {
                    Text(
                        text = "Error: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                Text(
                    text = "For security, create/delete users via Supabase Dashboard or a secure serverless function. Do not embed service_role keys in the app.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            false -> {
                Text(
                    text = "You are not an admin. Admin-only user management is recommended via Supabase Dashboard.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}