Shared Contact CRM — Implementation Status and Run Guide

Overview
- Platform: Kotlin Android (Jetpack Compose)
- Backend: Supabase (Auth + Postgres) — no separate backend
- Core behaviors from PRD implemented: background sync with WorkManager, phone normalization (E.164), duplicate prevention (older wins), two screens (Contacts, Settings), offline cache, runtime permissions and privacy consent
- Project entry points:
  - App bootstrap: [App.kt](android/app/src/main/java/com/sharedcrm/App.kt:1)
  - Main UI host: [MainActivity.kt](android/app/src/main/java/com/sharedcrm/ui/MainActivity.kt:1)
  - Background sync: [SyncWorker.doWork()](android/app/src/main/java/com/sharedcrm/sync/SyncWorker.kt:37)

How to Run (Android Studio)
1) Open the root folder in Android Studio (Gradle sync should run automatically).
2) Configure environment keys in [local.properties](android/local.properties:1):
   - SUPABASE_URL=https://YOUR_PROJECT.supabase.co
   - SUPABASE_ANON_KEY=YOUR_ANON_KEY
   - ORG_ID=YOUR_ORG_UUID
   - DEFAULT_REGION=IR
3) Apply Supabase schema in SQL Editor:
   - Run [schema.sql](supabase/schema.sql:1) (this enables pgcrypto, creates tables, trigger, RLS policies).
4) Provision users (admin and normal user):
   - Create Auth users via Dashboard (Authentication → Users).
   - Then either:
     - Run helper functions from [seed.sql](supabase/seed.sql:1), and call: select public.upsert_profile_by_email('admin@ran.com', 'Admin', 'admin', 'RAN Co'); select public.upsert_profile_by_email('user@ran.com', 'User', 'user', 'RAN Co');
     - Or run the direct DO block in [snippets.sql](supabase/snippets.sql:1) to upsert profiles by email.
   - Verify org_id and set it in [local.properties](android/local.properties:1).
5) Build and run:
   - Select the app run configuration for module “app”.
   - Grant runtime permissions when prompted.
   - On first launch, accept the privacy consent screen [PrivacyConsentScreen()](android/app/src/main/java/com/sharedcrm/ui/privacy/PrivacyConsentScreen.kt:1). If Call Log sync is disabled, READ_CALL_LOG won’t be requested.

WorkManager/Background
- Periodic sync scheduled at boot and app start:
  - [BootReceiver.onReceive()](android/app/src/main/java/com/sharedcrm/sync/BootReceiver.kt:13)
  - [App.onCreate()](android/app/src/main/java/com/sharedcrm/App.kt:8)
- One-time sync triggered on:
  - App foreground: [MainActivity.onCreate()](android/app/src/main/java/com/sharedcrm/ui/MainActivity.kt:46)
  - Connectivity change: [ConnectivityReceiver.onReceive()](android/app/src/main/java/com/sharedcrm/sync/ConnectivityReceiver.kt:17)
- Helpers:
  - [SyncWorker.enqueuePeriodic()](android/app/src/main/java/com/sharedcrm/sync/SyncWorker.kt:150)
  - [SyncWorker.enqueueOneTime()](android/app/src/main/java/com/sharedcrm/sync/SyncWorker.kt:162)
  - [SyncWorker.cancelPeriodic()](android/app/src/main/java/com/sharedcrm/sync/SyncWorker.kt:176)

Tasks Completed (core features)
- Privacy / Permissions
  - First-launch consent: [PrivacyConsentScreen()](android/app/src/main/java/com/sharedcrm/ui/privacy/PrivacyConsentScreen.kt:1)
  - Conditional READ_CALL_LOG permission based on consent: [MainActivity.onCreate()](android/app/src/main/java/com/sharedcrm/ui/MainActivity.kt:46)
- Supabase Integration
  - Client provider: [SupabaseClientProvider.get()](android/app/src/main/java/com/sharedcrm/data/remote/SupabaseClientProvider.kt:32) with Auth, Postgrest, Realtime
  - Auth manager: [AuthManager.signInEmailPassword()](android/app/src/main/java/com/sharedcrm/data/remote/AuthManager.kt:26), [AuthManager.sessionStatus()](android/app/src/main/java/com/sharedcrm/data/remote/AuthManager.kt:43), [AuthManager.signOut()](android/app/src/main/java/com/sharedcrm/data/remote/AuthManager.kt:36)
- Database Schema (Supabase)
  - Tables, indexes, trigger (“older wins”), RLS policies: [schema.sql](supabase/schema.sql:1)
  - Insert policy fixes (USING removed for INSERT; org scoped in WITH CHECK)
- User Provisioning
  - Helper functions and direct snippets: [seed.sql](supabase/seed.sql:1), [snippets.sql](supabase/snippets.sql:1), guide [USERS.md](supabase/USERS.md:1)
- Phone Normalization (E.164) and display formatting
  - [PhoneNormalizer](android/app/src/main/java/com/sharedcrm/core/PhoneNormalizer.kt:1) with unit tests [PhoneNormalizerTest](android/app/src/test/java/com/sharedcrm/PhoneNormalizerTest.kt:1)
- Offline Cache (Room)
  - Entities/DAOs: [RoomDb.kt](android/app/src/main/java/com/sharedcrm/data/local/RoomDb.kt:1)
  - Sync log observation in Settings: [SettingsScreen()](android/app/src/main/java/com/sharedcrm/ui/settings/SettingsScreen.kt:44)
- Sync Orchestration
  - [SyncWorker.doWork()](android/app/src/main/java/com/sharedcrm/sync/SyncWorker.kt:37) coordinates contacts/calls push/pull, device writes, dedupe, checkpoints, and logs
- Contacts: Push/Pull and Device Contacts
  - Push dirty contacts with server trigger handling: [ContactRepository.pushDirtyContacts()](android/app/src/main/java/com/sharedcrm/data/repo/ContactRepository.kt:85)
  - Pull contacts since last sync: [ContactRepository.pullContactsSinceLastSync()](android/app/src/main/java/com/sharedcrm/data/repo/ContactRepository.kt:164)
  - Device write/updates and dedupe deletion: [DeviceContacts.syncLocalCacheToDevice()](android/app/src/main/java/com/sharedcrm/device/DeviceContacts.kt:36), [DeviceContacts.dedupeDeviceByNormalizedPhone()](android/app/src/main/java/com/sharedcrm/device/DeviceContacts.kt:132)
- Calls: Push/Pull and Dedupe
  - Read device CallLog: [CallRepository.readDeviceCallLog()](android/app/src/main/java/com/sharedcrm/data/repo/CallRepository.kt:36)
  - Prevent duplicate upsert: [CallsDao.existsByPhoneAndStart()](android/app/src/main/java/com/sharedcrm/data/local/RoomDb.kt:119)
  - Push and mark uploaded: [CallRepository.pushNewCalls()](android/app/src/main/java/com/sharedcrm/data/repo/CallRepository.kt:98)
  - Pull for UI cache: [CallRepository.pullCallsSinceLastSync()](android/app/src/main/java/com/sharedcrm/data/repo/CallRepository.kt:139)
- UI Screens
  - Contacts list and detail: [ContactsScreen()](android/app/src/main/java/com/sharedcrm/ui/contacts/ContactsScreen.kt:53), [ContactDetailScreen()](android/app/src/main/java/com/sharedcrm/ui/contacts/ContactDetailScreen.kt:40)
  - Settings: Sync interval, Wi‑Fi-only, Manual/Periodic sync, Sync Log, Logout, Admin-only profiles section: [SettingsScreen()](android/app/src/main/java/com/sharedcrm/ui/settings/SettingsScreen.kt:44)

Needs Improvements (short term)
- Contacts editing and owner/admin guard:
  - Add three-dot Edit menu, enforce “only owner or admin” edits per RLS on [ContactsScreen()](android/app/src/main/java/com/sharedcrm/ui/contacts/ContactsScreen.kt:53)
- Incremental paging and batching:
  - Contacts pull: implement pagination loops in [ContactRepository.pullContactsSinceLastSync()](android/app/src/main/java/com/sharedcrm/data/repo/ContactRepository.kt:164)
  - Calls push: batch inserts for performance in [CallRepository.pushNewCalls()](android/app/src/main/java/com/sharedcrm/data/repo/CallRepository.kt:98)
- Retry/backoff queue:
  - Implement SyncQueue processing with exponential backoff; insert failed operations and re-process
- Room Migrations:
  - Replace fallbackToDestructiveMigration with proper migrations in [AppDatabase.get()](android/app/src/main/java/com/sharedcrm/data/local/RoomDb.kt:39)
- Device contact linkage:
  - Persist device contact IDs to reduce ambiguous dedupe decisions and improve “older wins” fidelity

TODOs (from PRD and current state)
- [ ] Implement edit contact UI with owner/admin guard; wire updates through [ContactRepository.pushDirtyContacts()](android/app/src/main/java/com/sharedcrm/data/repo/ContactRepository.kt:85)
- [ ] Implement sync_queue processing with exponential backoff and error categorization; surface failures in Settings Sync Log
- [ ] Add Supabase Realtime subscriptions for contacts and calls to reduce polling
- [ ] Batch uploads, paginated pulls (contacts and calls) for large datasets
- [ ] Add onboarding copy for permissions rationale (Play Store review readiness) and privacy policy content
- [ ] Room migrations and versioning
- [ ] Performance testing: normalization across locales, duplicate resolution (older wins), offline-first flow, RLS enforcement
- [ ] CI-friendly build, app signing, release preparations

Known Blockers / Setup Notes
- Supabase user creation must be done in Dashboard (or a secure backend/Edge Function). The mobile app must not embed service_role keys.
- Ensure pgcrypto extension is enabled before using gen_random_uuid(): [schema.sql](supabase/schema.sql:5)
- If INSERT policies error with “using”, confirm you’re using WITH CHECK only for INSERT (already fixed in [schema.sql](supabase/schema.sql:131))

Quick References
- Main Activity and consent: [MainActivity.kt](android/app/src/main/java/com/sharedcrm/ui/MainActivity.kt:1), [PrivacyConsentScreen()](android/app/src/main/java/com/sharedcrm/ui/privacy/PrivacyConsentScreen.kt:1)
- Sync worker helpers: [SyncWorker.enqueueOneTime()](android/app/src/main/java/com/sharedcrm/sync/SyncWorker.kt:162), [SyncWorker.enqueuePeriodic()](android/app/src/main/java/com/sharedcrm/sync/SyncWorker.kt:150)
- Supabase client/auth: [SupabaseClientProvider.kt](android/app/src/main/java/com/sharedcrm/data/remote/SupabaseClientProvider.kt:1), [AuthManager.kt](android/app/src/main/java/com/sharedcrm/data/remote/AuthManager.kt:1)
- Users guide: [USERS.md](supabase/USERS.md:1)
- Seed helpers: [seed.sql](supabase/seed.sql:1)
- Direct provisioning snippet: [snippets.sql](supabase/snippets.sql:1)

Run Checklist (final)
- [ ] local.properties configured with SUPABASE_URL, SUPABASE_ANON_KEY, ORG_ID, DEFAULT_REGION
- [ ] Supabase schema applied: [schema.sql](supabase/schema.sql:1)
- [ ] Admin and normal users created in Auth, profiles upserted: [USERS.md](supabase/USERS.md:1), [seed.sql](supabase/seed.sql:71) or [snippets.sql](supabase/snippets.sql:1)
- [ ] Android Studio run config set to “app”, Gradle sync successful
- [ ] App launches, consent accepted, login via email/password, sync completes and Sync Log shows entries in [SettingsScreen()](android/app/src/main/java/com/sharedcrm/ui/settings/SettingsScreen.kt:44)