package com.sharedcrm.data.remote

import io.github.jan.supabase.auth.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.coroutines.flow.Flow

/**
 * AuthManager wraps Supabase Auth for login/logout and current user access.
 *
 * Methods:
 * - signInEmailPassword(email, password)
 * - signOut()
 * - currentUserId(): String?
 * - sessionStatus(): Flow<SessionStatus>
 * - currentUserInfo(): UserInfo?
 */
object AuthManager {

    private val client by lazy { SupabaseClientProvider.get() }
    private val auth get() = client.auth

    /**
     * Sign in with email and password.
     * Uses Supabase Auth standard email provider.
     */
    suspend fun signInEmailPassword(email: String, password: String) {
        auth.signInWith(io.github.jan.supabase.auth.provider.Email) {
            this.email = email
            this.password = password
        }
    }

    /**
     * Sign out current user (clears session).
     */
    suspend fun signOut() {
        auth.signOut()
    }

    /**
     * Observe session status (Authenticated, NotAuthenticated, Refreshing, etc.)
     */
    fun sessionStatus(): Flow<SessionStatus> = auth.sessionStatus

    /**
     * Current authenticated user's id (UUID) or null if not signed in.
     */
    fun currentUserId(): String? = auth.currentSessionOrNull()?.user?.id

    /**
     * Current user info if available.
     */
    fun currentUserInfo(): UserInfo? = auth.currentSessionOrNull()?.user
}