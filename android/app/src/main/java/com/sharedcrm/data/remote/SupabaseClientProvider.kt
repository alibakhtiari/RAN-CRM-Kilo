package com.sharedcrm.data.remote

import com.sharedcrm.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime

/**
 * Provides a singleton SupabaseClient configured with:
 * - Auth
 * - Postgrest
 * - Realtime
 *
 * URL and anon key are injected via BuildConfig (set in local.properties).
 *
 * local.properties example (do NOT commit secrets):
 * SUPABASE_URL=https://xxxx.supabase.co
 * SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6...
 * ORG_ID=00000000-0000-0000-0000-000000000000
 * DEFAULT_REGION=IR
 */
object SupabaseClientProvider {

    @Volatile
    private var instance: SupabaseClient? = null

    fun get(): SupabaseClient {
        return instance ?: synchronized(this) {
            instance ?: build().also { instance = it }
        }
    }

    private fun build(): SupabaseClient {
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_ANON_KEY
        require(url.isNotBlank()) { "BuildConfig.SUPABASE_URL not configured. Set SUPABASE_URL in local.properties." }
        require(key.isNotBlank()) { "BuildConfig.SUPABASE_ANON_KEY not configured. Set SUPABASE_ANON_KEY in local.properties." }

        return createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
}