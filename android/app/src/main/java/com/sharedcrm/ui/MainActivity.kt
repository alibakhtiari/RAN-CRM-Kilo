package com.sharedcrm.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var currentScreen by remember { mutableStateOf(Screen.Contacts) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = "Shared Contact CRM") }
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
                            // Simple in-app navigation bar (no icons dependency)
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
                                Screen.Contacts -> ContactsScreen()
                                Screen.Settings -> SettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class Screen { Contacts, Settings }

@Composable
private fun ContactsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Contacts",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "This is a placeholder. Next steps: list shared contacts from Room cache, search, refresh, and actions (Call / SMS / WhatsApp).",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "This is a placeholder. Next steps: sync interval, Wi‑Fi‑only toggle, Sync Log, admin-only profiles list, and Logout.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}