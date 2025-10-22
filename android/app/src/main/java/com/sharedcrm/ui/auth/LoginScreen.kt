package com.sharedcrm.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sharedcrm.data.remote.AuthManager
import io.github.jan.supabase.auth.SessionStatus
import kotlinx.coroutines.launch

/**
 * LoginScreen — simple email/password sign-in for Supabase Auth.
 *
 * Per PRD, admin creates users via Supabase Dashboard. This screen lets users
 * authenticate with their provisioned credentials. On success, invoke onSignedIn()
 * so host can navigate to the app and optionally trigger an initial sync.
 */
@Composable
fun LoginScreen(
    onSignedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val sessionStatus by AuthManager.sessionStatus().collectAsState(initial = SessionStatus.NotAuthenticated)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Sign In",
            style = MaterialTheme.typography.headlineSmall
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (email.isBlank() || password.isBlank()) {
                        error = "Email and password are required"
                        return@Button
                    }
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            AuthManager.signInEmailPassword(email, password)
                            loading = false
                            onSignedIn()
                        } catch (t: Throwable) {
                            loading = false
                            error = t.message ?: "Sign-in failed"
                        }
                    }
                },
                enabled = !loading
            ) {
                Text(if (loading) "Signing in..." else "Sign In")
            }
        }

        when (sessionStatus) {
            is SessionStatus.Authenticated -> {
                Text(
                    text = "Authenticated",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            is SessionStatus.NotAuthenticated -> {
                Text(
                    text = "Not authenticated",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> {
                Text(
                    text = "Session: ${sessionStatus::class.simpleName}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Text(
            text = "Note: Users are provisioned by Admin via Supabase Dashboard.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}