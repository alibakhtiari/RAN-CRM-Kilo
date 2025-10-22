package com.sharedcrm.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sharedcrm.core.PhoneNormalizer
import com.sharedcrm.data.local.AppDatabase
import com.sharedcrm.data.local.entities.CallEntity
import com.sharedcrm.data.local.entities.ContactEntity
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Contact detail screen per PRD:
 * - Displays contact info
 * - Shows synchronized Call History: list of calls with date/time, uploader (user_id), and direction icon (in/out)
 *
 * Navigation wiring: call this composable with the selected ContactEntity.
 * Current ContactsScreen does not yet navigate; integration will be added later.
 */
@Composable
fun ContactDetailScreen(contact: ContactEntity) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val callsDao = db.callsDao()

    // Observe calls by normalized phone for cross-user visibility
    // NOTE: Alternatively, when serverId is available, use observeByContact(contact.serverId!!)
    val callsFlow = remember {
        callsDao.observeByPhone(contact.phoneNormalized)
            .map { it.sortedByDescending { c -> c.startTime } }
    }
    val calls = callsFlow.collectAsState(initial = emptyList())

    val phoneDisplay = PhoneNormalizer.formatForDisplay(
        contact.phoneRaw.ifBlank { contact.phoneNormalized },
        international = false
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Contact header
        Text(
            text = contact.name,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = phoneDisplay,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Call history title
        Text(
            text = "Call History",
            style = MaterialTheme.typography.titleMedium
        )

        if (calls.value.isEmpty()) {
            Text(
                text = "No calls available for this contact yet.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            calls.value.forEach { call ->
                CallRow(call)
            }
        }
    }
}

@Composable
private fun CallRow(call: CallEntity) {
    val directionEmoji = when (call.direction.lowercase(Locale.ROOT)) {
        "incoming" -> "⬅️"
        "outgoing" -> "➡️"
        else -> "❔"
    }
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(call.startTime))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Direction + timestamp
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$directionEmoji $ts",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Duration: ${call.durationSeconds}s",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Uploader (user) info
        Column(
            horizontalAlignment = Alignment.End
        ) {
            val uploader = call.userId ?: "Unknown"
            Text(
                text = "By: $uploader",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}