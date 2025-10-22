package com.sharedcrm.core

import com.sharedcrm.BuildConfig

/**
 * Supabase configuration sourced from BuildConfig.
 *
 * Values are provided via local.properties and exposed as buildConfigField in app/build.gradle.kts:
 * - SUPABASE_URL
 * - SUPABASE_ANON_KEY
 * - ORG_ID
 * - DEFAULT_REGION
 *
 * Do NOT store service_role keys in the app.
 */
object SupabaseConfig {

    // Supabase base URL (e.g., https://xxxx.supabase.co)
    val supabaseUrl: String
        get() = BuildConfig.SUPABASE_URL

    // Supabase anon/public key
    val supabaseAnonKey: String
        get() = BuildConfig.SUPABASE_ANON_KEY

    // Single-organization ID
    val orgId: String
        get() = BuildConfig.ORG_ID

    // Default region for phone normalization fallback; if missing, use "IR"
    val defaultRegion: String
        get() = BuildConfig.DEFAULT_REGION.ifBlank { "IR" }

    // Feature flags
    const val enableCallLogSync: Boolean = true
}