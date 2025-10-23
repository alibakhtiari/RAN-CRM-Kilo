package com.sharedcrm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sharedcrm.sync.SyncWorker
import com.sharedcrm.data.remote.AuthManager
import com.sharedcrm.data.local.AppDatabase
import com.sharedcrm.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen per PRD:
 * - Sync interval selection (manual, 15m, 30m, 1h, 6h)
 * - Sync log (recent entries with timestamps, operation, status, message)
 * - If admin role: user management (list profiles, add user via secure function or dashboard note)
 * - Logout button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLogout: (() -> Unit)? = null) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val scope = rememberCoroutineScope()

    // SharedPrefs for sync interval
    val prefs = remember { context.getSharedPreferences("shared_contact_crm_sync_prefs", android.content.Context.MODE_PRIVATE) }
    var currentInterval by remember { mutableStateOf(prefs.getString("sync_interval", "15") ?: "15") }

    val intervals = listOf(
        "manual" to "Manual Only",
        "15" to "15 minutes",
        "30" to "30 minutes",
        "60" to "1 hour",
        "360" to "6 hours"
    )

    // Sync log from DB
    val syncLogFlow = db.syncLogDao().observeRecent(50)
    val syncLog by syncLogFlow.collectAsState(initial = emptyList())

    // Admin check: simplistic role check (in production, read from profiles table with RLS)
    var isAdmin by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf<List<ProfileEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        scope.launch {
            // Check if user is admin (fetch own profile)
            val client = SupabaseClientProvider.get()
            val userId = AuthManager.currentUserId()
            if (userId != null) {
                try {
                    val profileRow = client.postgrest["profiles"]
                        .select(columns = "*")
                        .filter { eq("id", userId) }
                        .decodeSingleOrNull<Map<String, Any?>>()

                    isAdmin = profileRow?.get("role") == "admin"

                    if (isAdmin) {
                        // Load all profiles if admin
                        val serverProfiles = client.postgrest["profiles"]
                            .select(columns = "*")
                            .decodeList<Map<String, Any?>>()

                        // Note: In production, profiles table may be restricted; this assumes admin access
                        val profilesList = serverProfiles.mapNotNull { row ->
                            val id = row["id"] as? String ?: return@mapNotNull null
                            val email = row["email"] as? String ?: return@mapNotNull null
                            val displayName = row["display_name"] as? String ?: return@mapNotNull null
                            val role = row["role"] as? String ?: "user"
                            ProfileEntity(id = id, email = email, displayName = displayName, role = role)
                        }
                        profiles = profilesList
                    }
                } catch (e: Exception) {
                    // Ignore errors for demo; in production add proper error handling
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall
        )

        // Sync interval
        Column {
            Text(
                text = "Sync Interval",
                style = MaterialTheme.typography.titleMedium
            )
            var expanded by remember { mutableStateOf(false) }
            val currentLabel = intervals.firstOrNull { it.first == currentInterval }?.second ?: "15 minutes"

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    label = { Text("Select sync interval") }
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }) {
                    intervals.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                currentInterval = key
                                expanded = false
                                prefs.edit().putString("sync_interval", key).apply()

                                // Reschedule WorkManager based on interval
                                SyncWorker.cancelPeriodic(context)
                                if (key != "manual") {
                                    val minutes = when (key) {
                                        "15" -> 15L
                                        "30" -> 30L
                                        "60" -> 60L
                                        "360" -> 360L
                                        else -> 15L
                                    }
                                    SyncWorker.enqueuePeriodic(context, minutes)
                                }
                            }
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { SyncWorker.enqueueOneTime(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sync Now")
            }
        }

        Divider()

        // Sync log
        Column {
            Text(
                text = "Sync Log",
                style = MaterialTheme.typography.titleMedium
            )

            if (syncLog.isEmpty()) {
                Text(
                    text = "No sync activity yet.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                syncLog.forEach { entry ->
                    val tsFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    val timestamp = tsFormat.format(Date(entry.timestamp))
                    val statusIcon = when (entry.status.lowercase()) {
                        "success" -> "✅"
                        "failure" -> "❌"
                        else -> "⏳"
                    }

                    Text(
                        text = "$statusIcon $timestamp [${entry.type}:${entry.operation}] ${entry.message ?: ""}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (isAdmin) {
            Divider()

            // Admin panel
            Column {
                Text(
                    text = "Admin Panel",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "Users:",
                    style = MaterialTheme.typography.bodyMedium
                )

                profiles.forEach { profile ->
                    Text(
                        text = "${profile.displayName} (${profile.email}) - ${profile.role}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedButton(
                    onClick = {
                        // TODO: Implement secure user creation (server function or dashboard)
                        // For now, show message to create via Supabase Dashboard
                        // This cannot be done securely in-app without service key
                        android.widget.Toast.makeText(context, "Create users via Supabase Dashboard or secure server function.", android.widget.Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add User (Dashboard Required)")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Logout
        Button(
            onClick = {
                scope.launch {
                    AuthManager.signOut()
                    onLogout?.invoke()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Logout")
        }
    }
}

// Data class for profiles (since entities are for local, this is temporary view model)
data class ProfileEntity(
    val id: String,
    val email: String,
    val displayName: String,
    val role: String
)
