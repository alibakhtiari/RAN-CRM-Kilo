Android Studio Run Guide and Troubleshooting

This guide resolves:
- No run configuration added in Android Studio
- Where to get SUPABASE_ANON_KEY
- Common Gradle sync and Compose Compiler issues (Kotlin 2.0+)


1) Open the correct project directory
- In Android Studio, use File → Open and select the android/ folder (not the repo root).
- Wait for Gradle sync to complete.

Why: Opening the repo root may prevent Android Studio from detecting the :app module automatically. The run configuration won’t appear unless the Android project (with settings.gradle.kts and the :app module) is detected.


2) Configure local.properties
Create or edit [android/local.properties](android/local.properties:1). Use [android/local.properties.example](android/local.properties.example:1) as a template.

Required keys (copy from Supabase Dashboard → Settings → API):
- SUPABASE_URL=https://YOUR-PROJECT.supabase.co (Project URL)
- SUPABASE_ANON_KEY=YOUR-ANON-PUBLIC-KEY (anon public key)
- ORG_ID=YOUR-ORG-UUID (query: SELECT id, name FROM organizations;)
- DEFAULT_REGION=IR

The app reads these via BuildConfig inside [SupabaseClientProvider.get()](android/app/src/main/java/com/sharedcrm/data/remote/SupabaseClientProvider.kt:32) and [SupabaseConfig](android/app/src/main/java/com/sharedcrm/core/SupabaseConfig.kt:1).


3) Compose Compiler (Kotlin 2.0+) already configured
We enabled the Kotlin Compose Compiler Gradle plugin:
- Root plugins: [android/build.gradle.kts](android/build.gradle.kts:1) adds id "org.jetbrains.kotlin.plugin.compose" version "2.0.20".
- App module: [android/app/build.gradle.kts](android/app/build.gradle.kts:3) applies id "org.jetbrains.kotlin.plugin.compose" version "2.0.20".

No composeOptions block is needed with Kotlin 2.x.


4) Run configuration options

A) Use the provided run configuration files:
- [android/.run/App.run.xml](android/.run/App.run.xml:1)
- [android/.idea/runConfigurations/App.run.xml](android/.idea/runConfigurations/App.run.xml:1)

After opening the android/ folder and syncing, the configuration named “App” should appear in the run configurations dropdown. If it doesn’t, proceed to (B).

B) Create a run configuration manually:
- Run → Edit Configurations → “+” → Android App
- Name: App
- Module: app
- Launch: Default Activity
- OK/Apply

Launcher activity is already declared in [AndroidManifest.xml](android/app/src/main/AndroidManifest.xml:23) → [com.sharedcrm.ui.MainActivity](android/app/src/main/java/com/sharedcrm/ui/MainActivity.kt:43).


5) Gradle sync tips
- File → Sync Project with Gradle Files
- File → Invalidate Caches / Restart if needed
- Ensure JDK setup: File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK (use Embedded JDK)
- Android Gradle Plugin and Kotlin versions are set in:
  - [android/build.gradle.kts](android/build.gradle.kts:1)
  - [android/app/build.gradle.kts](android/app/build.gradle.kts:3)

Deprecated Gradle features warning
- This is an informational warning when using Gradle 9.x. It does not block the build.
- You can run with --warning-mode all (from command line) to see details and identify which plugin emits them.
- The current configuration is compatible with AGP 8.6.0 and Kotlin 2.0.20.


6) First run flow in the app
- On launch: Privacy consent ([PrivacyConsentScreen()](android/app/src/main/java/com/sharedcrm/ui/privacy/PrivacyConsentScreen.kt:1)).
- Login screen: Use your Supabase email/password ([LoginScreen()](android/app/src/main/java/com/sharedcrm/ui/auth/LoginScreen.kt:1)).
- Manual sync: Top bar Refresh in [MainActivity](android/app/src/main/java/com/sharedcrm/ui/MainActivity.kt:46) or Settings → Manual Sync Now ([SettingsScreen()](android/app/src/main/java/com/sharedcrm/ui/settings/SettingsScreen.kt:44)).


7) Supabase provisioning quick steps
- Apply [supabase/schema.sql](supabase/schema.sql:1) in SQL Editor.
- Add users in Dashboard (Authentication → Users).
- Upsert profiles (role + org):
  - Use helpers in [supabase/seed.sql](supabase/seed.sql:71) → SELECT public.upsert_profile_by_email('admin@ran.com','Admin','admin','RAN Co');
  - Or use the direct DO block in [supabase/snippets.sql](supabase/snippets.sql:1).
- Copy your org_id from: SELECT id, name FROM organizations; and set ORG_ID in [android/local.properties](android/local.properties:1).


8) If “No configurations detected” persists
- Confirm the android/ folder is opened (not repo root).
- Confirm the :app module exists in [android/settings.gradle.kts](android/settings.gradle.kts:17) (include(":app")).
- Ensure Gradle sync finishes successfully (no red errors).
- Manually create the run configuration (step 4B).
- Try deleting .idea/ and re-opening the android/ folder if the IDE cache is stuck (last resort).


9) Command-line alternative (to verify build)
From a terminal opened at android/:
- GradleWrapper.sh.execute()
./gradlew :app:assembleDebug

- Install on a connected device:
./gradlew :app:installDebug

If these succeed, Android Studio should also run the project once the configuration is visible.


10) Contact points in code (for verification)
- Main entry and UI host:
  - [MainActivity.kt](android/app/src/main/java/com/sharedcrm/ui/MainActivity.kt:1)
- Background sync:
  - [SyncWorker.doWork()](android/app/src/main/java/com/sharedcrm/sync/SyncWorker.kt:37)
- Supabase client & auth:
  - [SupabaseClientProvider.get()](android/app/src/main/java/com/sharedcrm/data/remote/SupabaseClientProvider.kt:32)
  - [AuthManager.signInEmailPassword()](android/app/src/main/java/com/sharedcrm/data/remote/AuthManager.kt:26)


Summary checklist
- [ ] Open android/ in Android Studio (not repository root)
- [ ] local.properties configured with SUPABASE_URL, SUPABASE_ANON_KEY, ORG_ID, DEFAULT_REGION
- [ ] Gradle sync OK; Kotlin Compose plugin applied
- [ ] Run configuration “App” visible or created manually (Module: app)
- [ ] App launches → consent → login → sync