package com.sharedcrm.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * PrivacyConsentScreen
 *
 * Purpose:
 * - Display a clear consent screen about data usage and permissions (Contacts, Call Log)
 * - Allow the user to enable/disable Call Log syncing
 * - Gate the rest of the app until the user accepts
 *
 * Integration:
 * - Show this before login/home if consent hasn't been given yet
 * - Persist the result (consent + call-sync toggle) in SharedPreferences from the caller
 */
@Composable
fun PrivacyConsentScreen(
    initialCallSyncEnabled: Boolean,
    onAccept: (callSyncEnabled: Boolean) -> Unit
) {
    var callSyncEnabled by remember { mutableStateOf(initialCallSyncEnabled) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Privacy & Permissions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "This app syncs your device Contacts and, optionally, Call Logs with your organization’s secure Supabase database. " +
                   "Data is transferred over HTTPS and access is controlled by your account permissions.",
            style = MaterialTheme.typography.bodyMedium
        )

        Divider()

        Text(
            text = "What we access",
            style = MaterialTheme.typography.titleMedium
        )
        Bullet("Contacts (read/write) to synchronize shared contacts across your team.")
        Bullet("Call Logs (read-only, optional) to display call history per contact and across users.")

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Your choices",
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Enable Call Log Sync",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "If enabled, the app reads your device’s call history and uploads new calls to the server. " +
                           "You can change this later in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = callSyncEnabled, onCheckedChange = { callSyncEnabled = it })
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Permissions we will request",
            style = MaterialTheme.typography.titleMedium
        )
        Bullet("READ_CONTACTS, WRITE_CONTACTS — to sync and manage contacts.")
        Bullet("READ_CALL_LOG — to sync call history (only if Call Log Sync is enabled).")

        Text(
            text = "You can revoke permissions at any time from Android Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onAccept(callSyncEnabled) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("I Agree")
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "•", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}