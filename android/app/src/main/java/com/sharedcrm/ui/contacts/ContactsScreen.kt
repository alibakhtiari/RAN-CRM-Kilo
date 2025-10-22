package com.sharedcrm.ui.contacts

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sharedcrm.core.PhoneNormalizer
import com.sharedcrm.data.local.AppDatabase
import com.sharedcrm.data.local.entities.ContactEntity
import com.sharedcrm.sync.SyncWorker
import kotlinx.coroutines.flow.map

/**
 * Contacts screen per PRD:
 * - Lists shared contacts (from local Room cache; synced with server)
 * - Each row: Name, Primary phone (formatted), actions: Call, SMS, WhatsApp
 * - 3-dot menu -> Edit (stub; owner/admin check to be implemented with auth)
 * - Tap -> Contact detail (to be implemented separately)
 * - Toolbar: search + refresh button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(onOpenContact: (ContactEntity) -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val dao = db.contactsDao()

    var query by remember { mutableStateOf(TextFieldValue("")) }

    // Observe contacts and apply simple client-side filtering by name/phone
    val contactsFlow = remember {
        dao.observeAll().map { list ->
            val q = query.text.trim().lowercase()
            if (q.isBlank()) list
            else list.filter {
                it.name.lowercase().contains(q) ||
                it.phoneRaw.lowercase().contains(q) ||
                it.phoneNormalized.lowercase().contains(q)
            }
        }
    }
    val contacts by contactsFlow.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                actions = {
                    Button(onClick = {
                        // Trigger immediate sync (pull/push)
                        SyncWorker.enqueueOneTime(context = context, requireUnmetered = false)
                    }) {
                        Text("Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Search field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    decorationBox = { inner ->
                        Column {
                            Text(
                                text = "Search by name/phone",
                                style = MaterialTheme.typography.labelSmall
                            )
                            inner()
                        }
                    }
                )
                Button(onClick = {
                    query = TextFieldValue("")
                }) {
                    Text("Clear")
                }
            }

            Divider()

            // Contact list
            contacts.forEach { contact ->
                ContactRow(contact = contact, onOpen = onOpenContact)
                Divider()
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ContactRow(contact: ContactEntity, onOpen: (ContactEntity) -> Unit) {
    val context = LocalContext.current
    val phoneDisplay = PhoneNormalizer.formatForDisplay(
        contact.phoneRaw.ifBlank { contact.phoneNormalized },
        international = false
    )
    val normalized = contact.phoneNormalized

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable { onOpen(contact) },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = contact.name,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = phoneDisplay,
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { dial(context, normalized.ifBlank { contact.phoneRaw }) }) {
                Text("Call")
            }
            Button(onClick = { sms(context, normalized.ifBlank { contact.phoneRaw }) }) {
                Text("SMS")
            }
            Button(onClick = { whatsapp(context, normalized) }) {
                Text("WhatsApp")
            }
            // Edit menu placeholder (owner/admin check to be implemented with auth state)
            // IconButton(onClick = { /* open edit screen if owner/admin */ }) {
            //     Icon(Icons.Default.MoreVert, contentDescription = "Edit")
            // }
        }
    }
}

private fun dial(context: android.content.Context, number: String) {
    val uri = Uri.parse("tel:$number")
    val intent = Intent(Intent.ACTION_DIAL, uri)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun sms(context: android.content.Context, number: String) {
    val uri = Uri.parse("smsto:$number")
    val intent = Intent(Intent.ACTION_SENDTO, uri)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun whatsapp(context: android.content.Context, e164: String) {
    // WhatsApp deep link via wa.me expects E.164 without '+'? It accepts both with and without '+'
    // Use encoded without '+' for compatibility
    val digitsOnly = e164.replace("+", "")
    val uri = Uri.parse("https://wa.me/$digitsOnly")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}