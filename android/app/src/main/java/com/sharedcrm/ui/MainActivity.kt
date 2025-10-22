package com.sharedcrm.ui

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sharedcrm.ui.contacts.ContactsScreen
import com.sharedcrm.ui.contacts.ContactDetailScreen
import com.sharedcrm.ui.settings.SettingsScreen
import com.sharedcrm.sync.SyncWorker
import com.sharedcrm.ui.auth.LoginScreen
import com.sharedcrm.data.remote.AuthManager
import io.github.jan.supabase.auth.SessionStatus
import android.content.Context
import com.sharedcrm.ui.privacy.PrivacyConsentScreen

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {

                // Privacy consent and call sync toggle state
                val prefs = remember { this@MainActivity.getSharedPreferences("shared_contact_crm_prefs", android.content.Context.MODE_PRIVATE) }
                var consentGiven by remember { mutableStateOf(prefs.getBoolean("consent_given", false)) }
                var callSyncEnabled by remember { mutableStateOf(prefs.getBoolean("call_sync_enabled", true)) }

                // Runtime permission request for Contacts and Call Log (per PRD)
                val requiredPermissions = arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS
                ).let { base ->
                    val list = mutableListOf(*base)
                    if (callSyncEnabled) {
                        list.add(Manifest.permission.READ_CALL_LOG)
                    }
                    list.toTypedArray()
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                    onResult = { /* handle individual results if needed */ }
                )

                LaunchedEffect(consentGiven, callSyncEnabled) {
                    if (!consentGiven) return@LaunchedEffect

                    // Build required permissions based on callSyncEnabled
                    val basePerms = mutableListOf(
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.WRITE_CONTACTS
                    )
                    if (callSyncEnabled) {
                        basePerms.add(Manifest.permission.READ_CALL_LOG)
                    }

                    val missing = basePerms.filter { perm ->
                        ContextCompat.checkSelfPermission(this@MainActivity, perm) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                    // Schedule initial one-time sync when app is brought to foreground (after consent)
                    SyncWorker.enqueueOneTime(this@MainActivity)
                }

                var currentScreen by remember { mutableStateOf(Screen.Contacts) }
                val sessionStatus by AuthManager.sessionStatus().collectAsState(initial = SessionStatus.NotAuthenticated)
var selectedContact by remember { mutableStateOf<com.sharedcrm.data.local.entities.ContactEntity?>(null) }

                if (!consentGiven) {
                    PrivacyConsentScreen(
                        initialCallSyncEnabled = callSyncEnabled,
                        onAccept = { enabled ->
                            // Persist consent and call sync toggle
                            callSyncEnabled = enabled
                            prefs.edit()
                                .putBoolean("consent_given", true)
                                .putBoolean("call_sync_enabled", enabled)
                                .apply()
                            consentGiven = true

                            // Request runtime permissions immediately after consent
                            val permList = mutableListOf(
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.WRITE_CONTACTS
                            )
                            if (enabled) {
                                permList.add(Manifest.permission.READ_CALL_LOG)
                            }
                            permissionLauncher.launch(permList.toTypedArray())

                            // Trigger initial sync
                            SyncWorker.enqueueOneTime(this@MainActivity)
                        }
                    )
                } else {
                    if (sessionStatus is SessionStatus.Authenticated) {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = { Text(text = "Shared Contact CRM") },
                                    actions = {
                                        if (currentScreen == Screen.Contacts && selectedContact != null) {
                                            TextButton(onClick = { selectedContact = null }) {
                                                Text("Back")
                                            }
                                        }
                                        TextButton(onClick = { SyncWorker.enqueueOneTime(this@MainActivity) }) {
                                            Text("Refresh")
                                        }
                                    }
                                )
                            }
                        ) { paddingValues ->
                            Surface(
                                modifier = Modifier
                                    .padding(paddingValues)
                                    .fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = { currentScreen = Screen.Contacts },
                                            enabled = currentScreen != Screen.Contacts
                                        ) {
                                            Text("Contacts")
                                        }
                                        Button(
                                            onClick = { currentScreen = Screen.Settings },
                                            enabled = currentScreen != Screen.Settings
                                        ) {
                                            Text("Settings")
                                        }
                                    }

                                    when (currentScreen) {
                                        Screen.Contacts -> {
                                            if (selectedContact == null) {
                                                ContactsScreen(onOpenContact = { selected ->
                                                    selectedContact = selected
                                                })
                                            } else {
                                                ContactDetailScreen(contact = selectedContact!!)
                                            }
                                        }
                                        Screen.Settings -> SettingsScreen()
                                    }
                                }
                            }
                        }
                    } else {
                        LoginScreen(onSignedIn = {
                            SyncWorker.enqueueOneTime(this@MainActivity)
                            currentScreen = Screen.Contacts
                        })
                    }
                }
            }
        }
    }
}

private enum class Screen { Contacts, Settings }

/* Using feature package ContactsScreen */

/* Using feature package SettingsScreen */