Run Guide and Configuration (Android Studio + Supabase)

This document explains where to get SUPABASE_ANON_KEY and how to make Android Studio detect and run the app module.

Where to get SUPABASE_URL and SUPABASE_ANON_KEY
1) Open Supabase Dashboard for your project
2) Go to Settings → API
3) In the Project API keys section:
   - Project URL = SUPABASE_URL
   - anon public key = SUPABASE_ANON_KEY
4) Do NOT use the service_role key in the mobile app (security risk)

Configure android/local.properties
Add the following keys (create the file if it does not exist). Do not commit this file to version control.

[android/local.properties](android/local.properties:1)
SUPABASE_URL=https://YOUR-PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR-ANON-PUBLIC-KEY
ORG_ID=YOUR-ORG-UUID
DEFAULT_REGION=IR

- ORG_ID: after running the schema and seeding users, run:
  select id, name from organizations;
  Copy the id for your organization (e.g., "RAN Co") and paste in ORG_ID.

Make sure BuildConfig wiring exists (review only)
Your app reads these values from BuildConfig (already set up in the project). If you ever need to double‑check, ensure your app’s Gradle defines buildConfigField entries. The following snippet shows the pattern you should see inside app’s build.gradle.kts:

Kotlin.buildGradle()
android {
    defaultConfig {
        // Example: these pull from local.properties
        buildConfigField("String", "SUPABASE_URL", "\"${propertyOrEnv(\"SUPABASE_URL\")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${propertyOrEnv(\"SUPABASE_ANON_KEY\")}\"")
        buildConfigField("String", "ORG_ID", "\"${propertyOrEnv(\"ORG_ID\")}\"")
        buildConfigField("String", "DEFAULT_REGION", "\"${propertyOrEnv(\"DEFAULT_REGION\")}\"")
    }
}
// propertyOrEnv(...) is a helper pattern; the project already has equivalent wiring.
// If you don’t see these, ensure your app module defines buildConfigFields for those keys.

The app uses the values via:
- [SupabaseClientProvider.get()](android/app/src/main/java/com/sharedcrm/data/remote/SupabaseClientProvider.kt:32) → BuildConfig.SUPABASE_URL and BuildConfig.SUPABASE_ANON_KEY
- [SupabaseConfig](android/app/src/main/java/com/sharedcrm/core/SupabaseConfig.kt:1) → BuildConfig.ORG_ID and BuildConfig.DEFAULT_REGION

Android Studio: making the run configuration appear
If Android Studio opens the repository root and shows “No configurations detected”, do the following:

Option A (recommended): open the Android project directory
- File → Open → select the android/ folder
- Wait for Gradle sync to finish
- Ensure a run config called "app" appears (Android App → Module: app)

Option B (keep root open)
- From the Gradle tool window, make sure the project includes the :app module
- If not, re-import Gradle or open the android/ folder directly (Option A)

General steps
1) File → Sync Project with Gradle Files
2) Ensure SDK/Gradle/JDK are properly configured:
   - File → Project Structure
     - SDK Location: Android SDK path set
     - Gradle JDK: Use Embedded JDK (recommended)
3) Tools → Device Manager → create an emulator (if needed)
4) Run → Edit Configurations → Add New → Android App
   - Module: app
   - Launch: Default Activity
   - Save and Run

Entry points (for verification)
- App entry: [MainActivity.kt](android/app/src/main/java/com/sharedcrm/ui/MainActivity.kt:1)
- Launcher declaration: [AndroidManifest.xml](android/app/src/main/AndroidManifest.xml:23)
- Background scheduling:
  - [App.onCreate()](android/app/src/main/java/com/sharedcrm/App.kt:8)
  - [BootReceiver.onReceive()](android/app/src/main/java/com/sharedcrm/sync/BootReceiver.kt:13)
  - [ConnectivityReceiver.onReceive()](android/app/src/main/java/com/sharedcrm/sync/ConnectivityReceiver.kt:17)

Supabase setup checklist
1) Apply schema
   - Run [schema.sql](supabase/schema.sql:1) in SQL Editor
2) Provision users (admin, normal)
   - Authentication → Users → Add user (emails/passwords)
   - EITHER run helper calls from [seed.sql](supabase/seed.sql:71) (after running the helper definitions)
     - SELECT public.upsert_profile_by_email('admin@ran.com', 'Admin', 'admin', 'RAN Co');
     - SELECT public.upsert_profile_by_email('user@ran.com', 'User', 'user', 'RAN Co');
   - OR run the direct provisioning block [snippets.sql](supabase/snippets.sql:1) (no helper functions needed)
3) Retrieve org id
   - SELECT id, name FROM organizations ORDER BY created_at DESC;
   - Put the id into ORG_ID in [android/local.properties](android/local.properties:1)

First run flow (what you’ll see)
- On first launch, you will see a privacy consent screen [PrivacyConsentScreen()](android/app/src/main/java/com/sharedcrm/ui/privacy/PrivacyConsentScreen.kt:1).
  - If you disable Call Log sync, the app will not request READ_CALL_LOG
- After consent, sign in with your email/password (created in Supabase)
  - [LoginScreen()](android/app/src/main/java/com/sharedcrm/ui/auth/LoginScreen.kt:1)
- Manual sync:
  - Top bar Refresh in [MainActivity.kt](android/app/src/main/java/com/sharedcrm/ui/MainActivity.kt:46)
  - Settings → “Manual Sync Now” [SettingsScreen()](android/app/src/main/java/com/sharedcrm/ui/settings/SettingsScreen.kt:44)

Troubleshooting
- No run configuration detected
  - Open the android/ folder directly (Option A above)
  - Ensure :app module is included in [settings.gradle.kts](android/settings.gradle.kts:1)
  - Sync Gradle and re-open Android Studio if needed
- BuildConfig.* not found errors
  - Confirm keys exist in [android/local.properties](android/local.properties:1)
  - Sync Gradle; ensure the app module defines buildConfigFields
- Missing Internet permission / authentication issues
  - Confirm [AndroidManifest.xml](android/app/src/main/AndroidManifest.xml:13) has INTERNET; it’s already included
  - Confirm you signed in with a valid Supabase Auth user
- RLS denies access
  - Ensure you created profiles via [seed.sql](supabase/seed.sql:71) or [snippets.sql](supabase/snippets.sql:1), so role/org_id exist in profiles
  - Verify your ORG_ID matches the user’s org_id

Quick run checklist
- [ ] SUPABASE_URL and SUPABASE_ANON_KEY copied from Dashboard → Settings → API
- [ ] ORG_ID set from organizations table; DEFAULT_REGION set (IR or your country code)
- [ ] Android Studio opened the android/ folder; Gradle sync OK
- [ ] App run config set (Module: app)
- [ ] App launches, requests permissions, shows consent and login